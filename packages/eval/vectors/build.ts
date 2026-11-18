/**
 * Cross-language test vectors. Every SDK that evaluates locally (Python today) must reproduce
 * `eval-vectors.json` exactly, and `test/vectors.test.ts` fails if this evaluator drifts from it.
 *
 * Regenerate (only when an evaluation change is intended): `node packages/eval/vectors/build.ts --write`
 */
import { writeFileSync } from 'node:fs';
import type { Snapshot, SnapshotFlag } from '@railflag/schema';
import { bucketFor } from '../src/bucket.ts';
import { evaluate, evaluateAll } from '../src/evaluate.ts';
import { murmur3, murmur3Latin1 } from '../src/murmur3.ts';

/** Small seeded PRNG so the file is stable between runs. */
function rng(seed: number) {
  let a = seed >>> 0;
  const next = () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  const pick = <T>(xs: readonly T[]): T => xs[Math.floor(next() * xs.length)]!;
  return { next, pick };
}

const CHARS = ['a', 'b', 'z', 'A', '0', '9', '-', '.', '_', ' ', '@', 'é', 'ü', 'ß', 'Ω', 'λ', 'Ж', '中', '文', 'ｚ', '😀', '🚀', '\u0000', 'ÿ', 'Ā', '\ud800', '\udfff'];

function randomString(r: ReturnType<typeof rng>, max = 24) {
  const n = Math.floor(r.next() * max);
  let s = '';
  for (let i = 0; i < n; i++) s += r.pick(CHARS);
  return s;
}

const B = [false, true];

function flag(key: string, over: Partial<SnapshotFlag>): SnapshotFlag {
  return {
    key, type: 'boolean', variations: B, salt: `s-${key}`, version: 3,
    enabled: true, offVariation: 0, fallthrough: { variation: 0 }, targets: [], rules: [], prerequisites: [],
    ...over,
  };
}

/** A flag with one rule made of `clauses`, serving `true` on a match and `false` otherwise. */
const ruleFlag = (key: string, clauses: SnapshotFlag['rules'][number]['clauses']) =>
  flag(key, { rules: [{ id: `r-${key}`, clauses, serve: { variation: 1 } }] });

function snapshot(): Snapshot {
  const flags: SnapshotFlag[] = [
    flag('off', { enabled: false }),
    flag('on-default', { fallthrough: { variation: 1 } }),
    flag('targets', { targets: [{ variation: 1, values: ['user-1', 'user-2'] }, { variation: 0, values: ['user-3'] }], fallthrough: { variation: 1 } }),
    ruleFlag('in-country', [{ attr: 'country', op: 'in', values: ['GR', 'CY'] }]),
    ruleFlag('in-country-not', [{ attr: 'country', op: 'in', values: ['GR'], negate: true }]),
    ruleFlag('in-number', [{ attr: 'age', op: 'in', values: [30] }]),
    ruleFlag('in-bool', [{ attr: 'beta', op: 'in', values: [true] }]),
    ruleFlag('in-key', [{ attr: 'key', op: 'in', values: ['user-4', '123'] }]),
    ruleFlag('in-array-attr', [{ attr: 'tags', op: 'in', values: ['vip'] }]),
    ruleFlag('starts', [{ attr: 'email', op: 'startsWith', values: ['maria', 'ΑΝ'] }]),
    ruleFlag('ends', [{ attr: 'email', op: 'endsWith', values: ['@acme.gr'] }]),
    ruleFlag('contains', [{ attr: 'email', op: 'contains', values: ['acme', 'é'] }]),
    ruleFlag('contains-numbers', [{ attr: 'code', op: 'contains', values: [1e21, 1e-7, 1.5, 100, true] }]),
    ruleFlag('matches', [{ attr: 'email', op: 'matches', values: ['^[a-z]+@acme\\.(gr|com)$'] }]),
    ruleFlag('matches-digits', [{ attr: 'code', op: 'matches', values: ['^\\d{3}$'] }]),
    ruleFlag('matches-invalid', [{ attr: 'email', op: 'matches', values: ['('] }]),
    ruleFlag('gt-age', [{ attr: 'age', op: 'gt', values: [18] }]),
    ruleFlag('lte-age', [{ attr: 'age', op: 'lte', values: [30] }]),
    ruleFlag('gte-score', [{ attr: 'score', op: 'gte', values: [1.5] }]),
    ruleFlag('lt-name', [{ attr: 'name', op: 'lt', values: ['m'] }]),
    ruleFlag('lt-name-utf16', [{ attr: 'name', op: 'lt', values: ['ｚ'] }]),
    ruleFlag('gt-name', [{ attr: 'name', op: 'gt', values: ['Zoe'] }]),
    ruleFlag('semver-gt', [{ attr: 'appVersion', op: 'semverGt', values: ['2.1.0'] }]),
    ruleFlag('semver-eq', [{ attr: 'appVersion', op: 'semverEq', values: ['2'] }]),
    ruleFlag('semver-lt', [{ attr: 'appVersion', op: 'semverLt', values: ['v1.10'] }]),
    ruleFlag('before', [{ attr: 'signupDate', op: 'before', values: ['2024-01-01T00:00:00Z'] }]),
    ruleFlag('after-ms', [{ attr: 'signupDate', op: 'after', values: [1700000000000] }]),
    ruleFlag('after-date', [{ attr: 'lastSeen', op: 'after', values: ['2025-06-01'] }]),
    ruleFlag('exists', [{ attr: 'company', op: 'exists', values: [true] }]),
    ruleFlag('exists-not', [{ attr: 'company', op: 'exists', values: [true], negate: true }]),
    ruleFlag('and', [{ attr: 'country', op: 'in', values: ['GR'] }, { attr: 'age', op: 'gte', values: [18] }]),
    ruleFlag('segment', [{ attr: '', op: 'segmentMatch', values: ['beta-testers'] }]),
    ruleFlag('segment-nested', [{ attr: '', op: 'segmentMatch', values: ['nested'] }]),
    ruleFlag('segment-not', [{ attr: '', op: 'segmentMatch', values: ['beta-testers'], negate: true }]),
    ruleFlag('segment-loop', [{ attr: '', op: 'segmentMatch', values: ['loop'] }]),
    ruleFlag('segment-missing', [{ attr: '', op: 'segmentMatch', values: ['nope'] }]),
    flag('rollout', { fallthrough: { rollout: { weights: [50_000, 50_000] } } }),
    flag('rollout-org', { fallthrough: { rollout: { bucketBy: 'orgId', weights: [30_000, 70_000] } } }),
    flag('partial', {
      type: 'string', variations: ['control', 'a', 'b'],
      rules: [
        { id: 'gr-half', clauses: [{ attr: 'country', op: 'in', values: ['GR'] }], serve: { rollout: { weights: [0, 20_000, 30_000] } } },
        { id: 'pro', clauses: [{ attr: 'plan', op: 'in', values: ['pro'] }], serve: { variation: 2 } },
      ],
      fallthrough: { rollout: { weights: [10_000, 10_000, 10_000] } },
    }),
    flag('abtasty', {
      type: 'string', variations: ['original', 'v1', 'v2'],
      fallthrough: { rollout: { weights: [33_000, 33_000, 34_000], bucketing: { algorithm: 'abtasty', seed: 'c8vg1ke' } } },
    }),
    flag('abtasty-partial', {
      type: 'string', variations: ['original', 'v1'],
      rules: [{ id: 'ab', clauses: [], serve: { rollout: { weights: [25_000, 25_000], bucketing: { algorithm: 'abtasty', seed: 'vg-Ω' } } } }],
      fallthrough: { variation: 0 },
    }),
    flag('unleash-rollout', {
      rules: [{ id: 'gr', clauses: [{ attr: 'country', op: 'in', values: ['GR'] }], serve: { rollout: { weights: [0, 30_000], bucketing: { algorithm: 'unleash', seed: 'checkout-v2' } } } }],
      fallthrough: { variation: 0 },
    }),
    flag('unleash-variant', {
      type: 'string', variations: ['disabled', 'control', 'annual-first'],
      fallthrough: { rollout: { weights: [0, 50_000, 50_000], bucketing: { algorithm: 'unleash-variant', seed: 'pricing-page', modulus: 1000 } } },
    }),
    flag('ordered', {
      type: 'string', variations: ['disabled', 'a', 'b', 'c'],
      fallthrough: { rollout: { weights: [0, 20_000, 30_000, 50_000], order: [3, 1, 2], bucketing: { algorithm: 'unleash-variant', seed: 'ord', modulus: 100 } } },
    }),
    flag('gated', {
      type: 'string', variations: ['off', 'a', 'b'],
      rules: [
        { id: 'gated-plan', clauses: [{ attr: 'plan', op: 'in', values: ['pro'] }], serve: { rollout: { weights: [0, 70_000, 30_000], bucketBy: 'tenant', bucketing: { algorithm: 'unleash-variant', seed: 'g', modulus: 10 }, gate: { weight: 40_000, bucketing: { algorithm: 'unleash', seed: 'g' } } } } },
        { id: 'gated-all', clauses: [], serve: { rollout: { weights: [0, 50_000, 50_000], gate: { weight: 25_000, bucketing: { algorithm: 'unleash', seed: 'other' } } } } },
      ],
      fallthrough: { variation: 0 },
    }),
    flag('number', { type: 'number', variations: [0, 1.5, -3, 1e21], rules: [{ id: 'pro', clauses: [{ attr: 'plan', op: 'in', values: ['pro'] }], serve: { variation: 3 } }], fallthrough: { rollout: { weights: [25_000, 25_000, 50_000] } } }),
    flag('json', { type: 'json', variations: [{ theme: 'light' }, { theme: 'dark', n: [1, 2] }, null], fallthrough: { rollout: { weights: [40_000, 40_000, 20_000] } } }),
    flag('prereq', { prerequisites: [{ key: 'in-country', variation: 1 }], fallthrough: { variation: 1 } }),
    flag('prereq-off', { prerequisites: [{ key: 'off', variation: 0 }], fallthrough: { variation: 1 } }),
    flag('prereq-missing', { prerequisites: [{ key: 'ghost', variation: 1 }], fallthrough: { variation: 1 } }),
    flag('cycle-a', { prerequisites: [{ key: 'cycle-b', variation: 1 }], fallthrough: { variation: 1 } }),
    flag('cycle-b', { prerequisites: [{ key: 'cycle-a', variation: 1 }], fallthrough: { variation: 1 } }),
    flag('bad-index', { enabled: false, offVariation: 5 }),
  ];
  return {
    env: 'development', version: 7, generatedAt: '2026-11-07T00:00:00.000Z',
    flags: Object.fromEntries(flags.map((f) => [f.key, f])),
    segments: {
      'beta-testers': { included: ['user-1'], excluded: ['user-2'], rules: [{ clauses: [{ attr: 'country', op: 'in', values: ['GR'] }, { attr: 'plan', op: 'in', values: ['pro'] }] }] },
      nested: { included: [], excluded: ['user-5'], rules: [{ clauses: [{ attr: '', op: 'segmentMatch', values: ['beta-testers'] }] }, { clauses: [{ attr: 'age', op: 'gt', values: [60] }] }] },
      loop: { included: [], excluded: [], rules: [{ clauses: [{ attr: '', op: 'segmentMatch', values: ['loop'] }] }] },
    },
  };
}

const POOLS: Record<string, unknown[]> = {
  country: ['GR', 'CY', 'DE', 'gr', '', null, 30, ['GR', 'DE']],
  age: [17, 18, 30, 30.0, 61, '30', -1, 1e21, true, null],
  beta: [true, false, 'true', 1],
  tags: [['vip', 'new'], ['new'], 'vip', [], [1, 'vip']],
  email: ['maria@acme.gr', 'nikos@acme.com', 'ΑΝΝΑ@example.gr', 'josé@acme.gr', 'MARIA@acme.gr', 'x@y', 5, ''],
  code: ['x1e+21y', 'a1e-7', '1.5', '100', '007', '12345', 'true', 42, 'x1e21'],
  score: [1.5, 1.4999, 2, '2', null],
  name: ['alice', 'Zoe', 'zoe', 'Ölaf', '😀', 'ｚ', '￿', 'm', ''],
  appVersion: ['2.1.0', '2.1.1', '2', 'v2.0.0', '1.9', '1.10.0', ' 3.0 ', 'beta', 2, '10.0.0-rc1'],
  signupDate: ['2023-06-15T10:00:00Z', '2024-01-01T00:00:00Z', '2024-02-01T00:00:00.000+02:00', 1690000000000, 1800000000000, 'not a date', '2023-12-31'],
  lastSeen: ['2025-06-01', '2025-06-02', '2025-05-31T23:59:59Z', '2025-06-01T00:00:01Z'],
  company: ['Acme', '', null, 0, false],
  plan: ['pro', 'free', 'PRO'],
  orgId: ['org-1', 'org-2', 42, 4.2, 1e21, 1e-7, -0, true, ['a', 'b'], [1, null, 'x'], { x: 1 }, null],
};

const KEYS: unknown[] = ['user-1', 'user-2', 'user-3', 'user-4', 'user-5', 123, 12.5, '', 'Μαρία', '😀-user', 'x'.repeat(40)];

function contexts(n: number) {
  const r = rng(20261107);
  const out: Record<string, unknown>[] = [];
  for (let i = 0; i < n; i++) {
    const ctx: Record<string, unknown> = {};
    const k = r.next();
    if (k < 0.45) ctx.targetingKey = r.pick(KEYS);
    else if (k < 0.6) ctx.key = r.pick(KEYS);
    else if (k < 0.9) ctx.targetingKey = `u-${i}-${randomString(r, 10)}`;
    for (const [attr, pool] of Object.entries(POOLS)) if (r.next() < 0.7) ctx[attr] = r.pick(pool);
    out.push(ctx);
  }
  return out;
}

export function buildVectors() {
  const r = rng(42);
  const hashInputs = ['', 'a', 'ab', 'abc', 'abcd', 'abcde', 'hello world', 'Γειά σου κόσμε', 'josé', '😀', 'a😀b', '\ud800x', 'x\udfff', '中文字符'];
  for (let i = 0; i < 150; i++) hashInputs.push(randomString(r, 40));
  const hashes = hashInputs.flatMap((input) => [0, 42, 0xffffffff].map((seed) => ({ input, seed, murmur3: murmur3(input, seed), latin1: murmur3Latin1(input, seed) })));

  const buckets = [];
  for (let i = 0; i < 400; i++) {
    const flagKey = `flag-${i % 7}`;
    const salt = i % 3 ? `salt${i % 5}` : '';
    const value = i % 4 ? `user-${i}` : randomString(r, 16);
    const abtasty = i % 5 === 0;
    const bucketing = abtasty ? { algorithm: 'abtasty' as const, seed: i % 10 ? `vg${i}` : undefined } : undefined;
    buckets.push({ flagKey, salt, value, bucketing, bucket: bucketFor(flagKey, salt, value, bucketing) });
  }
  // Unleash modes, appended so earlier vectors keep their values.
  const u = rng(7);
  for (let i = 0; i < 200; i++) {
    const flagKey = `flag-${i % 7}`;
    const value = i % 3 ? `user-${i}` : randomString(u, 16);
    const bucketing = i % 2
      ? { algorithm: 'unleash' as const, seed: i % 6 ? `group-${i % 9}` : undefined }
      : { algorithm: 'unleash-variant' as const, seed: `group-${i % 9}`, modulus: [1000, 100, 10, 1, 99, 7, 333][i % 7]! };
    buckets.push({ flagKey, salt: '', value, bucketing, bucket: bucketFor(flagKey, '', value, bucketing) });
  }

  const snap = snapshot();
  const cases = contexts(160).map((context) => ({ context, results: evaluateAll(snap, context) }));
  const typed = (['boolean', 'string', 'number', 'json'] as const).flatMap((expectedType) =>
    [...Object.keys(snap.flags), 'not-a-flag'].map((flagKey) => {
      const context = { targetingKey: 'user-1', country: 'GR', plan: 'pro', age: 40 };
      return { flagKey, context, expectedType, result: evaluate(snap, flagKey, context, { expectedType }) };
    }),
  );
  return { note: 'Generated by packages/eval/vectors/build.ts. Do not edit by hand.', hashes, buckets, snapshot: snap, cases, typed };
}

if (process.argv.includes('--write')) {
  const file = new URL('./eval-vectors.json', import.meta.url);
  writeFileSync(file, `${JSON.stringify(buildVectors())}\n`);
  console.log(`wrote ${file.pathname}`);
}
