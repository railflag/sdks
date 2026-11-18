import type { Snapshot } from '@railflag/schema';
import { readSse } from './sse.ts';

export interface SourceOptions {
  sdkKey: string;
  /** Your Railflag relay: https://relay.railflag.com. Required: there is no default host. */
  baseUrl: string;
  /** Stream updates over SSE (default true). Polling still runs as a safety net. */
  stream?: boolean;
  pollIntervalMs?: number;
  fetch?: typeof fetch;
  logger?: { warn: (...a: unknown[]) => void };
}

/**
 * Keeps the latest environment snapshot in memory. Flag checks never wait on the network:
 * if we can't reach Railflag, the last config we had keeps serving.
 */
export class ConfigSource {
  snapshot: Snapshot | null = null;
  private etag: string | null = null;
  private opts: Required<Omit<SourceOptions, 'logger' | 'fetch'>> & Pick<SourceOptions, 'logger'>;
  private f: typeof fetch;
  private abort = new AbortController();
  private pollTimer?: ReturnType<typeof setInterval>;
  private listeners = new Set<(s: Snapshot) => void>();
  streaming = false;

  constructor(o: SourceOptions) {
    // Checked at runtime too: JavaScript callers and unset env vars (`baseUrl: process.env.X`) skip the type.
    if (typeof o.baseUrl !== 'string' || !/^https?:\/\//.test(o.baseUrl)) {
      throw new TypeError(`baseUrl must be your Railflag relay URL, like https://relay.example.com (got ${JSON.stringify(o.baseUrl)})`);
    }
    this.opts = { stream: true, pollIntervalMs: 30_000, ...o, baseUrl: o.baseUrl.replace(/\/+$/, '') };
    this.f = o.fetch ?? fetch;
  }

  onChange(l: (s: Snapshot) => void) {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  }

  async start() {
    await this.fetchConfig();
    this.pollTimer = setInterval(() => void this.fetchConfig().catch((e) => this.opts.logger?.warn('railflag poll failed', e)), this.opts.pollIntervalMs);
    (this.pollTimer as { unref?: () => void }).unref?.();
    if (this.opts.stream) void this.streamLoop();
  }

  stop() {
    this.abort.abort();
    clearInterval(this.pollTimer);
  }

  async fetchConfig() {
    const headers: Record<string, string> = { authorization: this.opts.sdkKey };
    if (this.etag) headers['if-none-match'] = this.etag;
    const res = await this.f(`${this.opts.baseUrl}/sdk/v1/config`, { headers, signal: this.abort.signal });
    if (res.status === 304) return;
    if (!res.ok) throw new Error(`Railflag config request failed: ${res.status}`);
    const snap = (await res.json()) as Snapshot;
    if (this.snapshot && snap.version < this.snapshot.version) return;
    this.snapshot = snap;
    this.etag = res.headers.get('etag');
    for (const l of this.listeners) l(snap);
  }

  private async streamLoop() {
    let backoff = 500;
    while (!this.abort.signal.aborted) {
      try {
        const res = await this.f(`${this.opts.baseUrl}/sdk/v1/stream`, { headers: { authorization: this.opts.sdkKey }, signal: this.abort.signal });
        if (!res.ok) throw new Error(`stream ${res.status}`);
        this.streaming = true;
        backoff = 500;
        await readSse(res, (e) => {
          if (e.event === 'config') {
            const v = Number(JSON.parse(e.data || '{}').version);
            if (!this.snapshot || v > this.snapshot.version) void this.fetchConfig().catch(() => {});
          }
        }, this.abort.signal);
      } catch (e) {
        if (this.abort.signal.aborted) return;
        this.opts.logger?.warn('railflag stream dropped, reconnecting', e);
      }
      this.streaming = false;
      if (this.abort.signal.aborted) return;
      await new Promise((r) => setTimeout(r, backoff));
      backoff = Math.min(backoff * 2, 30_000);
    }
  }
}
