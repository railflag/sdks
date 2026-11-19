import { readFileSync } from 'node:fs';
import { OpenFeature } from '@openfeature/server-sdk';
import { describe, expect, it } from 'vitest';
import { RailflagProvider, VERSION } from '../src/index.ts';

const vectors = JSON.parse(readFileSync(new URL('../../eval/vectors/events-vectors.json', import.meta.url), 'utf8'));

type Body = { sentAt: number; sdk: string; exposures: { flag: string; variation: number; key: string; ts: number }[]; events: { event: string; key: string; value?: number; ts: number }[] };

function relay(snapshot: unknown, status = 202) {
  const posts: { body: Body; auth: string }[] = [];
  const f = (async (url: string, init?: RequestInit) => {
    if (url.endsWith('/sdk/v1/events')) {
      posts.push({ body: JSON.parse(String(init!.body)), auth: (init!.headers as Record<string, string>).authorization! });
      return new Response('{"ok":true}', { status });
    }
    if (url.endsWith('/sdk/v1/exposures')) return new Response('{"ok":true}', { status: 202 });
    return new Response(JSON.stringify(snapshot), { status: 200 });
  }) as unknown as typeof fetch;
  return { f, posts };
}

const provider = (f: typeof fetch, extra: object = {}) =>
  new RailflagProvider({ sdkKey: 'srv_x', baseUrl: 'https://relay.example.com', stream: false, fetch: f, sendExposures: false, ...extra });

/** OpenFeature contexts from vector contexts: a numeric `key` stays an attribute, like the eval vectors. */
const ofContext = (c: Record<string, unknown>) => c as never;

describe('experiment events vectors', () => {
  it('records the expected exposure for every evaluation', async () => {
    const { f } = relay(vectors.snapshot);
    const p = provider(f);
    await p.initialize();
    const { exposureFor } = await import('@railflag/eval');
    for (const c of vectors.exposures) {
      const r = (await import('@railflag/eval')).evaluate(p.source.snapshot!, c.flag, c.context);
      expect({ reason: r.reason, variationIndex: r.variationIndex }).toEqual(c.result);
      expect(exposureFor(p.source.snapshot!.experiments, c.flag, c.context, r)).toEqual(c.expect);
    }
    await p.onClose();
  });

  it('records the expected track events', async () => {
    const { trackFor } = await import('@railflag/eval');
    for (const c of vectors.tracks) expect(trackFor(vectors.snapshot.experiments, c.event, c.context, c.details?.value)).toEqual(c.expect);
  });

  for (const seq of vectors.sequences) {
    it(`sends what the vectors expect: ${seq.name}`, async () => {
      const { f, posts } = relay(vectors.snapshot);
      const p = provider(f);
      await OpenFeature.setProviderAndWait(`vec-${seq.name}`, p);
      const client = OpenFeature.getClient(`vec-${seq.name}`);
      for (const s of seq.steps) {
        if ('evaluate' in s) {
          if (s.evaluate === 'exp-three') await client.getStringValue(s.evaluate, 'x', ofContext(s.context));
          else await client.getBooleanValue(s.evaluate, false, ofContext(s.context));
        } else client.track(s.track, ofContext(s.context), s.details ?? {});
      }
      await p.tracking!.flush();
      const sent = posts.flatMap((x) => x.body.exposures).map(({ ts: _t, ...r }) => r);
      const events = posts.flatMap((x) => x.body.events).map(({ ts: _t, ...r }) => ({ ...r, value: r.value ?? 0 }));
      expect({ exposures: sent, events }).toEqual(seq.expect);
      await OpenFeature.clearProviders();
    });
  }
});

describe('experiment event sending', () => {
  it('sends batches with the key, sdk, sentAt and timestamps, and flushes on close', async () => {
    const { f, posts } = relay(vectors.snapshot);
    const p = provider(f);
    await p.initialize();
    await p.resolveBooleanEvaluation('exp-split', false, { targetingKey: 'user-1' });
    p.track('purchase', { targetingKey: 'user-1' }, { value: 12 });
    await p.onClose();
    expect(posts).toHaveLength(1);
    expect(posts[0]!.auth).toBe('srv_x');
    expect(posts[0]!.body.sdk).toBe(`node/${VERSION}`);
    expect(posts[0]!.body.sentAt).toBeGreaterThan(1.7e12);
    expect(posts[0]!.body.exposures[0]!.ts).toBeGreaterThan(1.7e12);
    expect(posts[0]!.body.events).toMatchObject([{ event: 'purchase', key: 'user-1', value: 12 }]);
  });

  it('splits into requests of at most 1,000 of each kind', async () => {
    const { f, posts } = relay(vectors.snapshot);
    const p = provider(f, { eventsIntervalMs: 3_600_000 });
    await p.initialize();
    for (let i = 0; i < 2_300; i++) p.track('purchase', { targetingKey: `u-${i}` }, {});
    await p.tracking!.flush();
    expect(posts.map((x) => x.body.events.length).reduce((a, b) => a + b, 0)).toBe(2_300);
    expect(Math.max(...posts.map((x) => x.body.events.length))).toBeLessThanOrEqual(1_000);
    await p.onClose();
  });

  it('sends nothing without a running experiment or when turned off', async () => {
    const { experiments: _e, ...plain } = vectors.snapshot;
    const a = relay(plain);
    const p = provider(a.f);
    await p.initialize();
    await p.resolveBooleanEvaluation('exp-split', false, { targetingKey: 'user-1' });
    p.track('purchase', { targetingKey: 'user-1' }, { value: 1 });
    await p.onClose();
    expect(a.posts).toEqual([]);

    const b = relay(vectors.snapshot);
    const off = provider(b.f, { sendEvents: false });
    await off.initialize();
    await off.resolveBooleanEvaluation('exp-split', false, { targetingKey: 'user-1' });
    off.track('purchase', { targetingKey: 'user-1' }, {});
    await off.onClose();
    expect(off.tracking).toBeNull();
    expect(b.posts).toEqual([]);
  });

  it('drops a batch the relay refuses and never throws', async () => {
    const { f, posts } = relay(vectors.snapshot, 429);
    const warnings: unknown[] = [];
    const p = provider(f, { logger: { warn: (...a: unknown[]) => warnings.push(a) } });
    await p.initialize();
    p.track('purchase', { targetingKey: 'user-1' }, {});
    await p.tracking!.flush();
    await p.tracking!.flush();
    expect(posts).toHaveLength(1);
    expect(warnings).toHaveLength(1);

    const down = (async (url: string) => {
      if (url.endsWith('/events')) throw new Error('ECONNREFUSED');
      return new Response(JSON.stringify(vectors.snapshot), { status: 200 });
    }) as unknown as typeof fetch;
    const q = provider(down, { logger: { warn: () => {} } });
    await q.initialize();
    q.track('purchase', { targetingKey: 'user-1' }, {});
    await expect(q.onClose()).resolves.toBeUndefined();
    await p.onClose();
  });

  it('VERSION matches package.json', () => {
    const pkg = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'));
    expect(VERSION).toBe(pkg.version);
  });
});
