import {
  ErrorCode, OpenFeatureEventEmitter, ProviderEvents, StandardResolutionReasons,
  type EvaluationContext, type JsonValue, type Provider, type ResolutionDetails,
} from '@openfeature/server-sdk';
import { evaluate } from '@railflag/eval';
import type { EvalResult, FlagType } from '@railflag/schema';
import { ExposureCounter } from './exposures.ts';
import { ConfigSource, type SourceOptions } from './source.ts';

export { ConfigSource, ExposureCounter, type SourceOptions };

export interface ProviderOptions extends SourceOptions {
  /**
   * Send evaluation counts (per flag and variation, once a minute) so Release Watch can measure rollouts.
   * Counts only: no contexts or user ids leave your process. Default true.
   */
  sendExposures?: boolean;
  /** How often counts are sent. Default 60,000 ms. */
  exposureIntervalMs?: number;
}

const codes: Record<string, ErrorCode> = {
  FLAG_NOT_FOUND: ErrorCode.FLAG_NOT_FOUND,
  TYPE_MISMATCH: ErrorCode.TYPE_MISMATCH,
  PARSE_ERROR: ErrorCode.PARSE_ERROR,
  GENERAL: ErrorCode.GENERAL,
};

/** OpenFeature provider for Node.js. Evaluates in process; no network call per flag check. */
export class RailflagProvider implements Provider {
  readonly metadata = { name: 'railflag' } as const;
  readonly runsOn = 'server' as const;
  readonly events = new OpenFeatureEventEmitter();
  readonly source: ConfigSource;
  readonly exposures: ExposureCounter | null;

  constructor(opts: ProviderOptions) {
    this.source = new ConfigSource(opts);
    this.exposures = opts.sendExposures === false ? null : new ExposureCounter({
      baseUrl: opts.baseUrl.replace(/\/+$/, ''), sdkKey: opts.sdkKey, fetch: opts.fetch ?? fetch,
      intervalMs: opts.exposureIntervalMs ?? 60_000, logger: opts.logger,
    });
    this.source.onChange((s) => this.events.emit(ProviderEvents.ConfigurationChanged, { message: `config version ${s.version}` }));
  }

  async initialize() {
    await this.source.start();
    this.exposures?.start();
  }

  async onClose() {
    this.source.stop();
    await this.exposures?.stop();
  }

  private resolve<T>(flagKey: string, def: T, ctx: EvaluationContext, type: FlagType): ResolutionDetails<T> {
    const snap = this.source.snapshot;
    if (!snap) return { value: def, reason: StandardResolutionReasons.ERROR, errorCode: ErrorCode.PROVIDER_NOT_READY };
    const r: EvalResult = evaluate(snap, flagKey, ctx as never, { expectedType: type });
    if (r.reason === 'ERROR') {
      return { value: def, reason: StandardResolutionReasons.ERROR, errorCode: codes[r.errorCode ?? 'GENERAL'] ?? ErrorCode.GENERAL, errorMessage: r.errorMessage };
    }
    if (r.variationIndex !== null) this.exposures?.record(flagKey, r.variationIndex);
    return {
      value: r.value as T,
      variant: r.variationIndex === null ? undefined : String(r.variationIndex),
      reason: r.reason,
      flagMetadata: { flagVersion: r.flagVersion ?? 0, ...(r.ruleId ? { ruleId: r.ruleId } : {}), configVersion: snap.version },
    };
  }

  resolveBooleanEvaluation(k: string, d: boolean, c: EvaluationContext) { return Promise.resolve(this.resolve(k, d, c, 'boolean')); }
  resolveStringEvaluation(k: string, d: string, c: EvaluationContext) { return Promise.resolve(this.resolve(k, d, c, 'string')); }
  resolveNumberEvaluation(k: string, d: number, c: EvaluationContext) { return Promise.resolve(this.resolve(k, d, c, 'number')); }
  resolveObjectEvaluation<T extends JsonValue>(k: string, d: T, c: EvaluationContext) { return Promise.resolve(this.resolve(k, d, c, 'json')); }
}
