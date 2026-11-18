import { OpenFeature, ProviderEvents, ProviderStatus } from '@openfeature/web-sdk';
import { afterEach, describe, expect, it } from 'vitest';
import { RailflagWebProvider } from '../src/index.ts';

const answer = (flags: Record<string, unknown>, version = 1) =>
  new Response(JSON.stringify({ version, flags: Object.fromEntries(Object.entries(flags).map(([k, value]) => [k, { value, variationIndex: 1, reason: 'DEFAULT' }])) }), { status: 200 });

/** A fetch that answers when the test says so, recording each request. */
function heldFetch() {
  const calls: { url: string; body: unknown; release: (r: Response | Error) => void }[] = [];
  const f = ((url: string, init: RequestInit) =>
    new Promise<Response>((resolve, reject) => {
      calls.push({ url, body: JSON.parse(String(init.body)), release: (r) => (r instanceof Error ? reject(r) : resolve(r)) });
    })) as unknown as typeof fetch;
  return { f, calls };
}

const tick = () => new Promise((r) => setTimeout(r, 0));

afterEach(async () => {
  await OpenFeature.clearProviders();
  OpenFeature.clearHandlers();
});

describe('baseUrl', () => {
  it.each([undefined, '', 'relay.example.com', 'ftp://relay.example.com'])('rejects %j', (baseUrl) => {
    expect(() => new RailflagWebProvider({ clientKey: 'cli_x', baseUrl: baseUrl as string })).toThrow(/baseUrl must be your Railflag relay URL/);
  });

  it('calls the given relay, without a trailing slash', async () => {
    const { f, calls } = heldFetch();
    const p = new RailflagWebProvider({ clientKey: 'cli_x', baseUrl: 'https://relay.example.com/', stream: false, fetch: f });
    const ready = OpenFeature.setProviderAndWait(p, { targetingKey: 'u1' });
    await tick();
    calls[0]!.release(answer({ a: true }));
    await ready;
    expect(calls.map((c) => c.url)).toEqual(['https://relay.example.com/sdk/v1/evaluate']);
  });
});

describe('bootstrap', () => {
  it('serves bootstrapped values before the first evaluation returns, then the evaluated ones', async () => {
    const { f, calls } = heldFetch();
    const p = new RailflagWebProvider({ clientKey: 'cli_x', baseUrl: 'https://relay.example.com', stream: false, fetch: f, bootstrap: { banner: 'from server' } });
    const changed: unknown[] = [];
    OpenFeature.addHandler(ProviderEvents.ConfigurationChanged, (d) => changed.push(d));
    await OpenFeature.setProviderAndWait(p, { targetingKey: 'u1' });
    const client = OpenFeature.getClient();
    expect(client.providerStatus).toBe(ProviderStatus.READY);
    expect(client.getStringDetails('banner', 'default')).toMatchObject({ value: 'from server', reason: 'CACHED' });

    calls[0]!.release(answer({ banner: 'evaluated' }));
    await tick(); await tick();
    expect(client.getStringValue('banner', 'default')).toBe('evaluated');
    expect(changed).toHaveLength(1);
  });

  it('keeps bootstrapped values and reports STALE when Railflag cannot be reached', async () => {
    const { f, calls } = heldFetch();
    const p = new RailflagWebProvider({ clientKey: 'cli_x', baseUrl: 'https://relay.example.com', stream: false, fetch: f, bootstrap: { banner: 'from server' } });
    await OpenFeature.setProviderAndWait(p, {});
    calls[0]!.release(new Error('offline'));
    await tick(); await tick();
    const client = OpenFeature.getClient();
    expect(client.providerStatus).toBe(ProviderStatus.STALE);
    expect(client.getStringValue('banner', 'default')).toBe('from server');
  });

  it('keeps bootstrapped values but reports ERROR when the key is rejected', async () => {
    const { f, calls } = heldFetch();
    const p = new RailflagWebProvider({ clientKey: 'cli_bad', baseUrl: 'https://relay.example.com', stream: false, fetch: f, bootstrap: { banner: 'from server' } });
    const errors: (string | undefined)[] = [];
    OpenFeature.addHandler(ProviderEvents.Error, (d) => errors.push(d?.message));
    await OpenFeature.setProviderAndWait(p, {});
    calls[0]!.release(new Response('{}', { status: 401 }));
    await tick(); await tick();
    const client = OpenFeature.getClient();
    expect(client.providerStatus).toBe(ProviderStatus.ERROR);
    expect(errors).toEqual(['Railflag evaluate failed: 401']);
    expect(client.getStringValue('banner', 'default')).toBe('from server');
  });

  it('without bootstrap, initialization waits for the first evaluation', async () => {
    const { f, calls } = heldFetch();
    const p = new RailflagWebProvider({ clientKey: 'cli_x', baseUrl: 'https://relay.example.com', stream: false, fetch: f });
    let done = false;
    const ready = OpenFeature.setProviderAndWait(p, {}).then(() => (done = true));
    await tick();
    expect(done).toBe(false);
    calls[0]!.release(answer({ a: 1 }));
    await ready;
    expect(OpenFeature.getClient().getNumberValue('a', 0)).toBe(1);
  });
});

describe('errors', () => {
  it('says the provider has no values yet, not FLAG_NOT_FOUND, when the first evaluation failed', async () => {
    const { f, calls } = heldFetch();
    const p = new RailflagWebProvider({ clientKey: 'cli_bad', baseUrl: 'https://relay.example.com', stream: false, fetch: f });
    const ready = OpenFeature.setProviderAndWait(p, {}).catch((e) => e);
    await tick();
    calls[0]!.release(new Response('{}', { status: 401 }));
    expect(String(await ready)).toMatch(/401/);
    const d = OpenFeature.getClient().getBooleanDetails('anything', true);
    expect(d).toMatchObject({ value: true, errorCode: 'PROVIDER_NOT_READY' });
    expect(d.errorMessage).toMatch(/401/);
  });

  it('reports FLAG_NOT_FOUND once Railflag has answered', async () => {
    const { f, calls } = heldFetch();
    const p = new RailflagWebProvider({ clientKey: 'cli_x', baseUrl: 'https://relay.example.com', stream: false, fetch: f });
    const ready = OpenFeature.setProviderAndWait(p, {});
    await tick();
    calls[0]!.release(answer({ a: true }));
    await ready;
    expect(OpenFeature.getClient().getBooleanDetails('missing', false).errorCode).toBe('FLAG_NOT_FOUND');
  });
});

describe('context changes', () => {
  it('a slow answer for an old context does not overwrite the newer one', async () => {
    const { f, calls } = heldFetch();
    const p = new RailflagWebProvider({ clientKey: 'cli_x', baseUrl: 'https://relay.example.com', stream: false, fetch: f, bootstrap: { plan: 'boot' } });
    await OpenFeature.setProviderAndWait(p, { targetingKey: 'u1', plan: 'free' });
    const switching = OpenFeature.setContext({ targetingKey: 'u1', plan: 'pro' });
    await tick();
    expect(calls.map((c) => (c.body as { context: { plan: string } }).context.plan)).toEqual(['free', 'pro']);
    calls[1]!.release(answer({ plan: 'pro' }, 2));
    await switching;
    calls[0]!.release(answer({ plan: 'free' }, 1));
    await tick(); await tick();
    expect(OpenFeature.getClient().getStringValue('plan', 'x')).toBe('pro');
  });
});

describe('exposure counts', () => {
  it('counts evaluations per variation and sends only counts', async () => {
    const posts: { url: string; body: unknown; keepalive?: boolean }[] = [];
    const f = (async (url: string, init: RequestInit) => {
      if (url.endsWith('/exposures')) { posts.push({ url, body: JSON.parse(String(init.body)), keepalive: init.keepalive }); return new Response('', { status: 202 }); }
      return answer({ 'new-checkout': true, banner: 'x' });
    }) as unknown as typeof fetch;
    const p = new RailflagWebProvider({ clientKey: 'cli_x', baseUrl: 'https://relay.example.com', stream: false, fetch: f });
    await OpenFeature.setProviderAndWait(p, { targetingKey: 'user-1', email: 'a@b.c' });
    const c = OpenFeature.getClient();
    c.getBooleanValue('new-checkout', false);
    c.getBooleanValue('new-checkout', false);
    c.getStringValue('new-checkout', 'x'); // wrong type: not an exposure
    await p.flushExposures();
    expect(posts).toEqual([{ url: 'https://relay.example.com/sdk/v1/exposures', body: { counts: { 'new-checkout': { 1: 2 } } }, keepalive: true }]);
    expect(JSON.stringify(posts)).not.toContain('user-1');
    await p.flushExposures();
    expect(posts).toHaveLength(1);
  });

  it('sends nothing with sendExposures: false', async () => {
    const urls: string[] = [];
    const f = (async (url: string) => { urls.push(url); return answer({ a: true }); }) as unknown as typeof fetch;
    const p = new RailflagWebProvider({ clientKey: 'cli_x', baseUrl: 'https://relay.example.com', stream: false, fetch: f, sendExposures: false });
    await OpenFeature.setProviderAndWait(p, {});
    OpenFeature.getClient().getBooleanValue('a', false);
    await p.flushExposures();
    expect(urls).toEqual(['https://relay.example.com/sdk/v1/evaluate']);
  });
});
