import { describe, expect, it } from 'vitest';
import { RailflagProvider } from '../src/index.ts';

describe('baseUrl', () => {
  it.each([undefined, '', 'relay.example.com', 'ftp://relay.example.com'])('rejects %j', (baseUrl) => {
    expect(() => new RailflagProvider({ sdkKey: 'srv_x', baseUrl: baseUrl as string })).toThrow(/baseUrl must be your Railflag relay URL/);
  });

  it('calls only the given relay, without a trailing slash', async () => {
    const urls: string[] = [];
    const f = (async (url: string) => {
      urls.push(url);
      return new Response(JSON.stringify({ version: 1, flags: {}, segments: {} }), { status: 200 });
    }) as unknown as typeof fetch;
    const p = new RailflagProvider({ sdkKey: 'srv_x', baseUrl: 'https://relay.example.com/', stream: false, fetch: f });
    await p.initialize();
    await p.onClose();
    expect(urls).toEqual(['https://relay.example.com/sdk/v1/config']);
  });
});
