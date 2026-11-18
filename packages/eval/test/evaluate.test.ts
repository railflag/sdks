import { describe, expect, it } from 'vitest';
import type { Snapshot, SnapshotFlag } from '@railflag/schema';
import { bucketFor, evaluate, evaluateAll } from '../src/index.ts';

function flag(over: Partial<SnapshotFlag> = {}): SnapshotFlag {
  return {
    key: 'new-checkout', type: 'boolean', variations: [false, true], salt: 'a91f', version: 9,
    enabled: true, offVariation: 0, fallthrough: { variation: 0 }, targets: [], rules: [], prerequisites: [],
    ...over,
  };
}

function snap(flags: SnapshotFlag[], segments: Snapshot['segments'] = {}): Snapshot {
  return { env: 'prod', version: 1, generatedAt: '2026-11-06T00:00:00Z', flags: Object.fromEntries(flags.map((f) => [f.key, f])), segments };
}

describe('evaluate', () => {
  it('serves the off variation when disabled', () => {
    const r = evaluate(snap([flag({ enabled: false, fallthrough: { variation: 1 } })]), 'new-checkout', { key: 'u1' });
    expect(r).toMatchObject({ value: false, reason: 'DISABLED', variationIndex: 0 });
  });

  it('returns FLAG_NOT_FOUND without throwing', () => {
    expect(evaluate(snap([]), 'nope')).toMatchObject({ reason: 'ERROR', errorCode: 'FLAG_NOT_FOUND' });
  });

  it('reports TYPE_MISMATCH', () => {
    expect(evaluate(snap([flag()]), 'new-checkout', {}, { expectedType: 'string' })).toMatchObject({ reason: 'ERROR', errorCode: 'TYPE_MISMATCH' });
  });

  it('checks individual targets before rules', () => {
    const f = flag({
      targets: [{ variation: 1, values: ['user-17'] }],
      rules: [{ id: 'r1', clauses: [{ attr: 'country', op: 'in', values: ['GR'] }], serve: { variation: 0 } }],
    });
    expect(evaluate(snap([f]), 'new-checkout', { targetingKey: 'user-17', country: 'GR' })).toMatchObject({ value: true, reason: 'TARGETING_MATCH', ruleId: 'target' });
  });

  it('applies the first matching rule', () => {
    const f = flag({
      rules: [
        { id: 'r1', clauses: [{ attr: 'email', op: 'endsWith', values: ['@acme.gr'] }], serve: { variation: 1 } },
        { id: 'r2', clauses: [{ attr: 'country', op: 'in', values: ['GR'] }], serve: { variation: 0 } },
      ],
    });
    const r = evaluate(snap([f]), 'new-checkout', { key: 'u', email: 'maria@acme.gr', country: 'GR' });
    expect(r).toMatchObject({ value: true, ruleId: 'r1', reason: 'TARGETING_MATCH' });
  });

  it('missing attributes never match, even when negated', () => {
    const f = flag({ rules: [{ id: 'r', clauses: [{ attr: 'country', op: 'in', values: ['GR'], negate: true }], serve: { variation: 1 } }] });
    expect(evaluate(snap([f]), 'new-checkout', { key: 'u' }).value).toBe(false);
    expect(evaluate(snap([f]), 'new-checkout', { key: 'u', country: 'FR' }).value).toBe(true);
  });

  it('matches segments by membership and by rules', () => {
    const f = flag({ rules: [{ id: 'beta', clauses: [{ attr: 'segment', op: 'segmentMatch', values: ['beta-testers'] }], serve: { variation: 1 } }] });
    const s = snap([f], { 'beta-testers': { included: ['u-1'], excluded: ['u-2'], rules: [{ clauses: [{ attr: 'email', op: 'endsWith', values: ['@acme.gr'] }] }] } });
    expect(evaluate(s, 'new-checkout', { key: 'u-1' }).value).toBe(true);
    expect(evaluate(s, 'new-checkout', { key: 'u-2', email: 'x@acme.gr' }).value).toBe(false);
    expect(evaluate(s, 'new-checkout', { key: 'u-3', email: 'x@acme.gr' }).value).toBe(true);
  });

  it('honours prerequisites', () => {
    const base = flag({ key: 'base', fallthrough: { variation: 1 } });
    const f = flag({ prerequisites: [{ key: 'base', variation: 1 }], fallthrough: { variation: 1 } });
    expect(evaluate(snap([base, f]), 'new-checkout', { key: 'u' }).value).toBe(true);
    const off = flag({ key: 'base', enabled: false });
    expect(evaluate(snap([off, f]), 'new-checkout', { key: 'u' })).toMatchObject({ value: false, ruleId: 'prerequisite:base' });
  });

  it('survives prerequisite cycles', () => {
    const a = flag({ key: 'a', prerequisites: [{ key: 'b', variation: 1 }], fallthrough: { variation: 1 } });
    const b = flag({ key: 'b', prerequisites: [{ key: 'a', variation: 1 }], fallthrough: { variation: 1 } });
    expect(evaluate(snap([a, b]), 'a', { key: 'u' }).value).toBe(false);
  });
});

describe('rollouts', () => {
  const users = Array.from({ length: 20_000 }, (_, i) => `user-${i}`);
  const rolled = (pct: number) => {
    const f = flag({ fallthrough: { rollout: { weights: [100_000 - pct * 1000, pct * 1000] } } });
    const s = snap([f]);
    return new Set(users.filter((u) => evaluate(s, 'new-checkout', { key: u }).value === true));
  };

  it('is sticky and close to the requested share', () => {
    const a = rolled(20);
    const b = rolled(20);
    expect([...a]).toEqual([...b]);
    expect(a.size / users.length).toBeGreaterThan(0.18);
    expect(a.size / users.length).toBeLessThan(0.22);
  });

  it('only adds users when ramping up', () => {
    const r10 = rolled(10);
    const r20 = rolled(20);
    for (const u of r10) expect(r20.has(u)).toBe(true);
  });

  it('reshuffles when the salt changes', () => {
    const b1 = bucketFor('new-checkout', 'a91f', 'user-1');
    const b2 = bucketFor('new-checkout', 'zzzz', 'user-1');
    expect(b1).not.toBe(b2);
  });

  it('continues to the next rule when a partial allocation leaves a user out', () => {
    const f = flag({
      rules: [{ id: 'ab', clauses: [], serve: { rollout: { weights: [0, 50_000], bucketing: { algorithm: 'abtasty', seed: 'vg1' } } } }],
      fallthrough: { variation: 0 },
    });
    const s = snap([f]);
    const results = users.slice(0, 2000).map((u) => evaluate(s, 'new-checkout', { key: u }));
    const inRule = results.filter((r) => r.ruleId === 'ab');
    expect(inRule.length).toBeGreaterThan(800);
    expect(inRule.length).toBeLessThan(1200);
    expect(results.filter((r) => r.ruleId === undefined).every((r) => r.value === false)).toBe(true);
  });

  it('evaluateAll returns every flag', () => {
    expect(Object.keys(evaluateAll(snap([flag(), flag({ key: 'other' })]), { key: 'u' }))).toEqual(['new-checkout', 'other']);
  });
});
