import { describe, expect, it } from 'vitest';
import { RailflagProvider } from '../src/index.ts';

const snapshot = {
  env: 'production', version: 3, generatedAt: '2026-11-18T10:00:00Z', segments: {},
  flags: {
    'new-checkout': { key: 'new-checkout', type: 'boolean', variations: [false, true], salt: 's', version: 2, enabled: true, offVariation: 0, fallthrough: { variation: 1 }, targets: [], rules: [], prerequisites: [] },
  },
};

function relay() {
  const posts: { url: string; body: unknown; auth: string }[] = [];
  const f = (async (url: string, init?: RequestInit) => {
    if (url.endsWith('/sdk/v1/exposures')) {
      posts.push({ url, body: JSON.parse(String(init!.body)), auth: (init!.headers as Record<string, string>).authorization! });
      return new Response('{"ok":true}', { status: 202 });
    }
    return new Response(JSON.stringify(snapshot), { status: 200 });
  }) as unknown as typeof fetch;
  return { f, posts };
}

describe('exposure counts', () => {
  it('counts each evaluation per variation and sends the totals, counts only', async () => {
    const { f, posts } = relay();
    const p = new RailflagProvider({ sdkKey: 'srv_x', baseUrl: 'https://relay.example.com', stream: false, fetch: f, exposureIntervalMs: 60_000 });
    await p.initialize();
    for (let i = 0; i < 3; i++) await p.resolveBooleanEvaluation('new-checkout', false, { targetingKey: `user-${i}`, email: 'secret@example.com' });
    await p.resolveBooleanEvaluation('missing-flag', false, { targetingKey: 'u' }); // errors aren't exposures
    await p.exposures!.flush();
    expect(posts).toEqual([{ url: 'https://relay.example.com/sdk/v1/exposures', body: { counts: { 'new-checkout': { 1: 3 } } }, auth: 'srv_x' }]);
    expect(JSON.stringify(posts)).not.toContain('user-');
    await p.exposures!.flush();
    expect(posts).toHaveLength(1); // nothing new, nothing sent
    await p.resolveBooleanEvaluation('new-checkout', false, {});
    await p.onClose(); // flushes on close
    expect(posts).toHaveLength(2);
  });

  it('sends nothing when turned off, and never throws when the relay is down', async () => {
    const { f, posts } = relay();
    const off = new RailflagProvider({ sdkKey: 'srv_x', baseUrl: 'https://relay.example.com', stream: false, fetch: f, sendExposures: false });
    await off.initialize();
    await off.resolveBooleanEvaluation('new-checkout', false, {});
    await off.onClose();
    expect(off.exposures).toBeNull();
    expect(posts).toEqual([]);

    const down = (async (url: string) => {
      if (url.endsWith('/exposures')) throw new Error('ECONNREFUSED');
      return new Response(JSON.stringify(snapshot), { status: 200 });
    }) as unknown as typeof fetch;
    const p = new RailflagProvider({ sdkKey: 'srv_x', baseUrl: 'https://relay.example.com', stream: false, fetch: down, logger: { warn: () => {} } });
    await p.initialize();
    await p.resolveBooleanEvaluation('new-checkout', false, {});
    await expect(p.onClose()).resolves.toBeUndefined();
  });
});
