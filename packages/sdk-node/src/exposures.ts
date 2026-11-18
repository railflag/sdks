/**
 * Counts flag evaluations per flag and variation and sends the totals to the relay once a minute, so Release
 * Watch can see how many users got each variation. Only counts leave the process: no contexts, no user ids.
 * Sending never delays a flag check, and a failed send is dropped rather than retried.
 */
export interface ExposureOptions {
  baseUrl: string;
  sdkKey: string;
  fetch: typeof fetch;
  intervalMs: number;
  logger?: { warn: (...a: unknown[]) => void };
}

/** More distinct flags than this in one minute are not counted (protects memory if keys are generated). */
const MAX_FLAGS = 2000;

export class ExposureCounter {
  private counts = new Map<string, Map<number, number>>();
  private timer?: ReturnType<typeof setInterval>;
  private readonly o: ExposureOptions;

  constructor(o: ExposureOptions) {
    this.o = o;
  }

  record(flagKey: string, variation: number) {
    let byVar = this.counts.get(flagKey);
    if (!byVar) {
      if (this.counts.size >= MAX_FLAGS) return;
      byVar = new Map();
      this.counts.set(flagKey, byVar);
    }
    byVar.set(variation, (byVar.get(variation) ?? 0) + 1);
  }

  start() {
    this.timer = setInterval(() => void this.flush(), this.o.intervalMs);
    (this.timer as { unref?: () => void }).unref?.();
  }

  /** Sends what was counted since the last flush. Nothing is sent when nothing was evaluated. */
  async flush() {
    if (!this.counts.size) return;
    const counts: Record<string, Record<string, number>> = {};
    for (const [flag, byVar] of this.counts) counts[flag] = Object.fromEntries([...byVar].map(([v, n]) => [String(v), n]));
    this.counts = new Map();
    try {
      const res = await this.o.fetch(`${this.o.baseUrl}/sdk/v1/exposures`, {
        method: 'POST',
        headers: { authorization: this.o.sdkKey, 'content-type': 'application/json' },
        body: JSON.stringify({ counts }),
        signal: AbortSignal.timeout(5_000),
      });
      if (!res.ok && res.status !== 404) this.o.logger?.warn(`railflag exposure counts not accepted: ${res.status}`);
    } catch (e) {
      this.o.logger?.warn('railflag exposure counts not sent', e);
    }
  }

  async stop() {
    clearInterval(this.timer);
    await this.flush();
  }
}
