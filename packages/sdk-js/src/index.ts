import {
  ErrorCode, OpenFeatureEventEmitter, ProviderEvents, StandardResolutionReasons,
  type EvaluationContext, type JsonValue, type Provider, type ResolutionDetails, type TrackingEventDetails,
} from '@openfeature/web-sdk';

/** This package's version, sent with experiment events. The release job checks it against package.json. */
export const VERSION = '0.3.0';

/** Flags and events of the environment's running experiments, from the relay. */
interface Experiments { flags: string[]; events: string[] }

interface Evaluated {
  value: unknown;
  variationIndex: number | null;
  reason: string;
  flagVersion?: number;
}

export interface WebProviderOptions {
  /** Client key: public, safe to ship in a web page. Rules stay on the server. */
  clientKey: string;
  /** Your Railflag relay: https://relay.railflag.com. Required: there is no default host. */
  baseUrl: string;
  /** Re-evaluate when the config changes (default true). */
  stream?: boolean;
  /**
   * Values rendered by your server. With these, the provider is ready at once and serves them
   * while the first evaluation runs in the background, so the page doesn't flicker.
   */
  bootstrap?: Record<string, unknown>;
  /**
   * Send evaluation counts (per flag and variation, once a minute and when the page is hidden) so Release
   * Watch can measure rollouts. Counts only: no context or user ids. Default true.
   */
  sendExposures?: boolean;
  /** How often counts are sent. Default 60,000 ms. */
  exposureIntervalMs?: number;
  /**
   * Send experiment data: which variation this user got, for flags in a running experiment, and the
   * `track()` events an experiment counts. This sends the context's targeting key (Railflag hashes it before
   * storing it), and only while you run an experiment. Default true.
   */
  sendEvents?: boolean;
  /** How often experiment data is sent (and always when the page is hidden). Default 10,000 ms. */
  eventsIntervalMs?: number;
  fetch?: typeof fetch;
}

/** More distinct flags than this between two sends are not counted. */
const MAX_EXPOSURE_FLAGS = 2000;
/** Items per request and kind. Small, because keepalive requests sent while a page unloads are capped at 64 KB in total. */
const EVENT_BATCH = 200;
/** Items kept while the relay can't be reached; newer ones are dropped. */
const MAX_EVENT_QUEUE = 2000;

const keyOf = (ctx: EvaluationContext): string | undefined => {
  const k = ctx.targetingKey ?? (ctx as { key?: unknown }).key;
  return k === undefined || k === null || String(k) === '' ? undefined : String(k);
};

/**
 * OpenFeature provider for browsers. Sends the context to Railflag and gets values back,
 * so targeting rules and segment lists never reach the client.
 */
export class RailflagWebProvider implements Provider {
  readonly metadata = { name: 'railflag-web' } as const;
  readonly runsOn = 'client' as const;
  readonly events = new OpenFeatureEventEmitter();
  private opts: { clientKey: string; baseUrl: string; stream: boolean };
  private f: typeof fetch;
  private values: Record<string, Evaluated> = {};
  private context: EvaluationContext = {};
  private es: EventSource | null = null;
  private version = -1;
  /** True once Railflag has answered at least one evaluation. */
  private evaluated = false;
  private lastError: string | null = null;
  /** Set when a background refresh failed; cleared (with a READY event) by the next good answer. */
  private degraded = false;
  /** Only the newest request may write `values`, so a slow answer for an old context can't win. */
  private seq = 0;
  private exposures: Map<string, Map<number, number>> | null;
  private exposureTimer: ReturnType<typeof setInterval> | null = null;
  private exposureIntervalMs: number;
  private experiments: Experiments | undefined;
  /** (flag, variation, key) already sent, so each is sent once per page. */
  private sentExposures = new Set<string>();
  private eventQueue: { exposures: { flag: string; variation: number; key: string; ts: number }[]; events: { event: string; key: string; value: number; ts: number }[] } | null;
  private eventTimer: ReturnType<typeof setInterval> | null = null;
  private eventsIntervalMs: number;
  private onHide = () => {
    if (typeof document !== 'undefined' && document.visibilityState !== 'hidden') return;
    void this.flushExposures();
    void this.flushEvents();
  };

  constructor(opts: WebProviderOptions) {
    // Checked at runtime too: JavaScript callers and unset env vars (`baseUrl: import.meta.env.X`) skip the type.
    if (typeof opts.baseUrl !== 'string' || !/^https?:\/\//.test(opts.baseUrl)) {
      throw new TypeError(`baseUrl must be your Railflag relay URL, like https://relay.example.com (got ${JSON.stringify(opts.baseUrl)})`);
    }
    this.opts = { clientKey: opts.clientKey, baseUrl: opts.baseUrl.replace(/\/+$/, ''), stream: opts.stream ?? true };
    this.f = opts.fetch ?? ((...a) => fetch(...a));
    for (const [k, v] of Object.entries(opts.bootstrap ?? {})) this.values[k] = { value: v, variationIndex: null, reason: StandardResolutionReasons.CACHED };
    this.exposures = opts.sendExposures === false ? null : new Map();
    this.exposureIntervalMs = opts.exposureIntervalMs ?? 60_000;
    this.eventQueue = opts.sendEvents === false ? null : { exposures: [], events: [] };
    this.eventsIntervalMs = opts.eventsIntervalMs ?? 10_000;
  }

  /** Sends queued experiment exposures and events. keepalive lets it finish while the page unloads. */
  async flushEvents() {
    const q = this.eventQueue;
    while (q && (q.exposures.length || q.events.length)) {
      const body = JSON.stringify({ sentAt: Date.now(), sdk: `js/${VERSION}`, exposures: q.exposures.splice(0, EVENT_BATCH), events: q.events.splice(0, EVENT_BATCH) });
      try {
        await this.f(`${this.opts.baseUrl}/sdk/v1/events`, {
          method: 'POST', keepalive: true,
          headers: { authorization: this.opts.clientKey, 'content-type': 'application/json' },
          body,
        });
      } catch {
        // Best effort, like exposure counts.
      }
    }
  }

  private queueEvent(kind: 'exposures' | 'events', item: never) {
    const q = this.eventQueue;
    if (!q || q[kind].length >= MAX_EVENT_QUEUE) return;
    (q[kind] as unknown[]).push(item);
  }

  /** OpenFeature tracking: `client.track('purchase', { value: 49.9 })`. Sent only when a running experiment counts the event. */
  track(eventName: string, context: EvaluationContext, details: TrackingEventDetails) {
    if (!this.experiments?.events.includes(eventName)) return;
    const key = keyOf(context ?? this.context);
    if (key === undefined) return;
    const value = typeof details?.value === 'number' && Number.isFinite(details.value) ? details.value : 0;
    this.queueEvent('events', { event: eventName, key, value, ts: Date.now() } as never);
  }

  /** Sends the counts gathered since the last send. keepalive lets it finish while the page unloads. */
  async flushExposures() {
    if (!this.exposures?.size) return;
    const counts: Record<string, Record<string, number>> = {};
    for (const [flag, byVar] of this.exposures) counts[flag] = Object.fromEntries([...byVar].map(([v, n]) => [String(v), n]));
    this.exposures = new Map();
    try {
      await this.f(`${this.opts.baseUrl}/sdk/v1/exposures`, {
        method: 'POST', keepalive: true,
        headers: { authorization: this.opts.clientKey, 'content-type': 'application/json' },
        body: JSON.stringify({ counts }),
      });
    } catch {
      // Counts are best effort: a lost minute never matters to the page.
    }
  }

  private countExposure(key: string, variation: number) {
    const m = this.exposures;
    if (!m) return;
    let byVar = m.get(key);
    if (!byVar) {
      if (m.size >= MAX_EXPOSURE_FLAGS) return;
      byVar = new Map();
      m.set(key, byVar);
    }
    byVar.set(variation, (byVar.get(variation) ?? 0) + 1);
  }

  async initialize(context?: EvaluationContext) {
    this.context = context ?? {};
    if (this.eventQueue) this.eventTimer = setInterval(() => void this.flushEvents(), this.eventsIntervalMs);
    if (this.exposures || this.eventQueue) {
      if (this.exposures) this.exposureTimer = setInterval(() => void this.flushExposures(), this.exposureIntervalMs);
      if (typeof addEventListener === 'function') {
        addEventListener('pagehide', this.onHide);
        addEventListener('visibilitychange', this.onHide);
      }
    }
    if (this.opts.stream && typeof EventSource !== 'undefined') {
      this.es = new EventSource(`${this.opts.baseUrl}/sdk/v1/stream?key=${encodeURIComponent(this.opts.clientKey)}`);
      this.es.addEventListener('config', (e) => {
        const v = Number(JSON.parse((e as MessageEvent).data || '{}').version);
        if (v > this.version) void this.refreshAndAnnounce();
      });
    }
    // OpenFeature answers PROVIDER_NOT_READY until initialize() resolves, so waiting here would hide the bootstrap.
    if (Object.keys(this.values).length > 0) void this.refreshAndAnnounce();
    else await this.refresh();
  }

  async onContextChange(_old: EvaluationContext, next: EvaluationContext) {
    this.context = next;
    await this.refresh();
  }

  async onClose() {
    this.es?.close();
    if (this.exposureTimer) clearInterval(this.exposureTimer);
    if (this.eventTimer) clearInterval(this.eventTimer);
    if (typeof removeEventListener === 'function') {
      removeEventListener('pagehide', this.onHide);
      removeEventListener('visibilitychange', this.onHide);
    }
    void this.flushExposures(); // never holds up closing
    void this.flushEvents();
  }

  /**
   * Background refresh: announces new values. If it fails, the values already held keep serving and the
   * provider reports ERROR for a rejected key (a setup mistake) or STALE when Railflag can't be reached.
   */
  private async refreshAndAnnounce() {
    try {
      if (!(await this.refresh())) return;
      if (this.degraded) {
        this.degraded = false;
        this.events.emit(ProviderEvents.Ready);
      }
      this.events.emit(ProviderEvents.ConfigurationChanged);
    } catch (e) {
      this.degraded = true;
      const message = String((e as Error)?.message ?? e);
      const status = (e as { status?: number }).status;
      this.events.emit(status === 401 || status === 403 ? ProviderEvents.Error : ProviderEvents.Stale, { message });
    }
  }

  /** Returns false when a newer request superseded this one. */
  private async refresh(): Promise<boolean> {
    const mine = ++this.seq;
    try {
      const res = await this.f(`${this.opts.baseUrl}/sdk/v1/evaluate`, {
        method: 'POST',
        headers: { authorization: this.opts.clientKey, 'content-type': 'application/json' },
        body: JSON.stringify({ context: this.context }),
      });
      if (!res.ok) throw Object.assign(new Error(`Railflag evaluate failed: ${res.status}`), { status: res.status });
      const body = (await res.json()) as { version: number; flags: Record<string, Evaluated>; experiments?: Experiments };
      if (mine !== this.seq) return false;
      this.values = body.flags;
      this.version = body.version;
      this.experiments = body.experiments;
      this.evaluated = true;
      this.lastError = null;
      return true;
    } catch (e) {
      if (mine === this.seq) this.lastError = String((e as Error)?.message ?? e);
      throw e;
    }
  }

  private resolve<T>(key: string, def: T, type: string): ResolutionDetails<T> {
    const v = this.values[key];
    if (!v && !this.evaluated) {
      const why = this.lastError ? `: ${this.lastError}` : '';
      return { value: def, reason: StandardResolutionReasons.ERROR, errorCode: ErrorCode.PROVIDER_NOT_READY, errorMessage: `No values from Railflag yet${why}` };
    }
    if (!v) return { value: def, reason: StandardResolutionReasons.ERROR, errorCode: ErrorCode.FLAG_NOT_FOUND };
    if (v.reason === 'ERROR' || v.value === undefined) return { value: def, reason: StandardResolutionReasons.ERROR, errorCode: ErrorCode.GENERAL };
    const actual = typeof v.value === 'object' ? 'object' : typeof v.value;
    if (actual !== type) return { value: def, reason: StandardResolutionReasons.ERROR, errorCode: ErrorCode.TYPE_MISMATCH };
    if (v.variationIndex !== null) this.countExposure(key, v.variationIndex);
    if (this.eventQueue && v.reason === 'SPLIT' && v.variationIndex !== null && this.experiments?.flags.includes(key)) {
      const user = keyOf(this.context);
      const id = `${key}\u0000${v.variationIndex}\u0000${user}`;
      if (user !== undefined && !this.sentExposures.has(id)) {
        if (this.sentExposures.size >= 10_000) this.sentExposures.clear();
        this.sentExposures.add(id);
        this.queueEvent('exposures', { flag: key, variation: v.variationIndex, key: user, ts: Date.now() } as never);
      }
    }
    return { value: v.value as T, reason: v.reason, variant: v.variationIndex === null ? undefined : String(v.variationIndex), flagMetadata: v.flagVersion ? { flagVersion: v.flagVersion } : {} };
  }

  resolveBooleanEvaluation(k: string, d: boolean) { return this.resolve(k, d, 'boolean'); }
  resolveStringEvaluation(k: string, d: string) { return this.resolve(k, d, 'string'); }
  resolveNumberEvaluation(k: string, d: number) { return this.resolve(k, d, 'number'); }
  resolveObjectEvaluation<T extends JsonValue>(k: string, d: T) { return this.resolve(k, d, 'object'); }
}
