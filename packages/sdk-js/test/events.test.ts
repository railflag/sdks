import { readFileSync } from 'node:fs';
import { OpenFeature } from '@openfeature/web-sdk';
import { evaluateAll } from '@railflag/eval';
import { afterEach, describe, expect, it } from 'vitest';
import { RailflagWebProvider, VERSION } from '../src/index.ts';

const vectors = JSON.parse(readFileSync(new URL('../../eval/vectors/events-vectors.json', import.meta.url), 'utf8'));

type Body = { sentAt: number; sdk: string; exposures: { flag: string; variation: number; key: string; ts: number }[]; events: { event: string; key: string; value?: number; ts: number }[] };

/** A relay that evaluates like the real one (all flags, plus the experiments list) and records event posts. */
function relay(snapshot = vectors.snapshot) {
  const posts: { body: Body; auth: string; keepalive: boolean }[] = [];
  const f = (async (url: string, init: RequestInit) => {
    if (url.endsWith('/sdk/v1/events')) {
      posts.push({ body: JSON.parse(String(init.body)), auth: (init.headers as Record<string, string>).authorization!, keepalive: !!init.keepalive });
      return new Response('{"ok":true}', { status: 202 });
    }
    if (url.endsWith('/sdk/v1/exposures')) return new Response('{"ok":true}', { status: 202 });
    const { context } = JSON.parse(String(init.body));
    const flags = Object.fromEntries(Object.entries(evaluateAll(snapshot, context)).map(([k, r]) => [k, { value: r.value, variationIndex: r.variationIndex, reason: r.reason, flagVersion: r.flagVersion }]));
    return new Response(JSON.stringify({ version: snapshot.version, flags, ...(snapshot.experiments ? { experiments: snapshot.experiments } : {}) }), { status: 200 });
  }) as unknown as typeof fetch;
  return { f, posts };
}

const provider = (f: typeof fetch, extra: object = {}) =>
  new RailflagWebProvider({ clientKey: 'cli_x', baseUrl: 'https://relay.example.com', stream: false, fetch: f, sendExposures: false, ...extra });

afterEach(async () => {
  await OpenFeature.clearProviders();
  await OpenFeature.clearContext();
});

describe('experiment events vectors', () => {
  for (const seq of vectors.sequences) {
    it(`sends what the vectors expect: ${seq.name}`, async () => {
      const { f, posts } = relay();
      const p = provider(f);
      await OpenFeature.setProviderAndWait(p, {});
      const client = OpenFeature.getClient();
      for (const s of seq.steps) {
        await OpenFeature.setContext(s.context);
        if ('evaluate' in s) {
          if (s.evaluate === 'exp-three') client.getStringValue(s.evaluate, 'x');
          else client.getBooleanValue(s.evaluate, false);
        } else client.track(s.track, s.details ?? {});
      }
      await p.flushEvents();
      const exposures = posts.flatMap((x) => x.body.exposures).map(({ ts: _t, ...r }) => r);
      const events = posts.flatMap((x) => x.body.events).map(({ ts: _t, ...r }) => ({ ...r, value: r.value ?? 0 }));
      expect({ exposures, events }).toEqual(seq.expect);
    });
  }
});

describe('experiment event sending', () => {
  it('sends with the client key, sdk, sentAt and keepalive', async () => {
    const { f, posts } = relay();
    const p = provider(f);
    await OpenFeature.setProviderAndWait(p, { targetingKey: 'user-1' });
    const client = OpenFeature.getClient();
    client.getBooleanValue('exp-split', false);
    client.getBooleanValue('exp-split', false);
    client.track('purchase', { value: 9.5 });
    await p.flushEvents();
    expect(posts).toHaveLength(1);
    expect(posts[0]).toMatchObject({ auth: 'cli_x', keepalive: true, body: { sdk: `js/${VERSION}`, events: [{ event: 'purchase', key: 'user-1', value: 9.5 }] } });
    expect(posts[0]!.body.exposures).toHaveLength(1);
    expect(posts[0]!.body.sentAt).toBeGreaterThan(1.7e12);
  });

  it('keeps requests small enough for keepalive', async () => {
    const { f, posts } = relay();
    const p = provider(f);
    await OpenFeature.setProviderAndWait(p, { targetingKey: 'user-1' });
    for (let i = 0; i < 450; i++) OpenFeature.getClient().track('purchase', { value: i });
    await p.flushEvents();
    expect(posts.map((x) => x.body.events.length)).toEqual([200, 200, 50]);
    expect(Math.max(...posts.map((x) => JSON.stringify(x.body).length))).toBeLessThan(64 * 1024);
  });

  it('sends nothing without a running experiment or when turned off', async () => {
    const { experiments: _e, ...plain } = vectors.snapshot;
    const a = relay(plain);
    const p = provider(a.f);
    await OpenFeature.setProviderAndWait(p, { targetingKey: 'user-1' });
    OpenFeature.getClient().getBooleanValue('exp-split', false);
    OpenFeature.getClient().track('purchase', { value: 1 });
    await p.flushEvents();
    expect(a.posts).toEqual([]);
    await OpenFeature.clearProviders();

    const b = relay();
    const off = provider(b.f, { sendEvents: false });
    await OpenFeature.setProviderAndWait(off, { targetingKey: 'user-1' });
    OpenFeature.getClient().getBooleanValue('exp-split', false);
    OpenFeature.getClient().track('purchase', {});
    await off.flushEvents();
    expect(b.posts).toEqual([]);
  });

  it('VERSION matches package.json', () => {
    const pkg = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'));
    expect(VERSION).toBe(pkg.version);
  });
});
