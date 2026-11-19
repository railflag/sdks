import type { ExposureRecord, TrackRecord } from '@railflag/eval';

/**
 * Sends experiment data to the relay: one exposure per user and variation of a flag in a running experiment,
 * and the track() events an experiment counts. User keys leave the process here, only for those flags and
 * events; Railflag hashes them before storing them. Sending never delays a flag check, and a failed send is
 * dropped rather than retried.
 */
export interface EventSenderOptions {
  baseUrl: string;
  sdkKey: string;
  sdk: string;
  fetch: typeof fetch;
  intervalMs: number;
  logger?: { warn: (...a: unknown[]) => void };
  now?: () => number;
}

/** Items per request, per kind: the relay accepts up to 1,000 of each. */
export const BATCH = 1000;
/** Send early once this many items wait, so a busy process doesn't hold big queues for the whole interval. */
const SEND_AT = 500;
/** Items kept while the relay can't be reached; newer ones are dropped. */
const MAX_QUEUE = 10_000;

type Timed<T> = T & { ts: number };

export class EventSender {
  private exposures: Timed<ExposureRecord>[] = [];
  private events: Timed<TrackRecord>[] = [];
  private timer?: ReturnType<typeof setInterval>;
  private sending: Promise<void> | null = null;
  private warnedFull = false;
  private lastWarn = 0;
  private readonly o: EventSenderOptions;
  private readonly now: () => number;

  constructor(o: EventSenderOptions) {
    this.o = o;
    this.now = o.now ?? Date.now;
  }

  exposure(r: ExposureRecord) {
    this.push(this.exposures, { ...r, ts: this.now() });
  }

  event(r: TrackRecord) {
    this.push(this.events, { ...r, ts: this.now() });
  }

  private push<T>(q: Timed<T>[], item: Timed<T>) {
    if (q.length >= MAX_QUEUE) {
      if (!this.warnedFull) this.o.logger?.warn('railflag experiment events: queue full, dropping new events until the relay can be reached');
      this.warnedFull = true;
      return;
    }
    q.push(item);
    if (this.exposures.length + this.events.length >= SEND_AT && !this.sending) void this.flush();
  }

  start() {
    this.timer = setInterval(() => void this.flush(), this.o.intervalMs);
    (this.timer as { unref?: () => void }).unref?.();
  }

  /** Sends everything queued, in requests of at most BATCH exposures and BATCH events. */
  flush(): Promise<void> {
    if (this.sending) return this.sending.then(() => this.flush());
    if (!this.exposures.length && !this.events.length) return Promise.resolve();
    this.sending = this.sendAll().finally(() => { this.sending = null; });
    return this.sending;
  }

  private async sendAll() {
    while (this.exposures.length || this.events.length) {
      const exposures = this.exposures.splice(0, BATCH);
      const events = this.events.splice(0, BATCH);
      this.warnedFull = false;
      try {
        const res = await this.o.fetch(`${this.o.baseUrl}/sdk/v1/events`, {
          method: 'POST',
          headers: { authorization: this.o.sdkKey, 'content-type': 'application/json' },
          body: JSON.stringify({ sentAt: this.now(), sdk: this.o.sdk, exposures, events }),
          signal: AbortSignal.timeout(5_000),
        });
        if (!res.ok && res.status !== 404) this.warn(`railflag experiment events not accepted: ${res.status}`);
      } catch (e) {
        this.warn('railflag experiment events not sent', e);
      }
    }
  }

  private warn(...a: unknown[]) {
    const t = this.now();
    if (t - this.lastWarn < 60_000) return;
    this.lastWarn = t;
    this.o.logger?.warn(...a);
  }

  async stop() {
    clearInterval(this.timer);
    await this.flush();
  }
}
