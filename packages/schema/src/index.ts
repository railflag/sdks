import { z } from 'zod';

/** Rollout weights are integers out of WEIGHT_SCALE (100,000 = 100%, so 0.001% steps). */
export const WEIGHT_SCALE = 100_000;

export const flagKey = z
  .string()
  .min(1)
  .max(128)
  .regex(/^[A-Za-z0-9][A-Za-z0-9._-]*$/, 'Use letters, digits, dot, dash or underscore, starting with a letter or digit');

export const flagType = z.enum(['boolean', 'string', 'number', 'json']);
export type FlagType = z.infer<typeof flagType>;

export const flagKind = z.enum(['release', 'ops', 'experiment', 'permission']);
export type FlagKind = z.infer<typeof flagKind>;

export const clauseOp = z.enum([
  'in',
  'exists',
  'startsWith',
  'endsWith',
  'contains',
  'matches',
  'lt',
  'lte',
  'gt',
  'gte',
  'semverEq',
  'semverLt',
  'semverGt',
  'before',
  'after',
  'segmentMatch',
]);
export type ClauseOp = z.infer<typeof clauseOp>;

const scalar = z.union([z.string(), z.number(), z.boolean()]);

export const clause = z.object({
  attr: z.string().min(1),
  op: clauseOp,
  values: z.array(scalar).min(1),
  negate: z.boolean().optional(),
});
export type Clause = z.infer<typeof clause>;

/** How a context is mapped to a bucket. `murmur3` is ours; others reproduce a previous vendor so migrated users keep their variation. */
export const bucketing = z.object({
  algorithm: z.enum(['murmur3', 'abtasty', 'unleash', 'unleash-variant']),
  /** abtasty: the variation group id that seeds the hash. unleash, unleash-variant: the strategy's groupId. */
  seed: z.string().optional(),
  /**
   * unleash-variant: the total variant weight Unleash takes the hash modulo (usually 1000). Unleash bucket x maps
   * to floor(x * WEIGHT_SCALE / modulus), so cumulative boundaries at floor(C * WEIGHT_SCALE / modulus) are exact.
   */
  modulus: z.number().int().positive().max(WEIGHT_SCALE).optional(),
});
export type Bucketing = z.infer<typeof bucketing>;

/**
 * Weights normally add up to WEIGHT_SCALE. A total below it leaves the remainder unallocated:
 * those users skip this rule and continue to the next one (needed to reproduce AB Tasty partial allocation).
 */
export const rollout = z
  .object({
    bucketBy: z.string().optional(),
    weights: z.array(z.number().int().min(0).max(WEIGHT_SCALE)).min(1),
    bucketing: bucketing.optional(),
    /** Variation indexes in the order their ranges are laid out (default: index order). Vendors such as Unleash walk their own order. */
    order: z.array(z.number().int().min(0)).optional(),
    /**
     * Traffic allocation before the split, hashed separately: only users whose gate bucket is below
     * `weight` get a variation; the rest continue to the next rule. Reproduces vendors that hash twice
     * (Unleash rollout + variants, Split traffic allocation, DevCycle rollout).
     */
    gate: z.object({
      weight: z.number().int().min(0).max(WEIGHT_SCALE),
      bucketBy: z.string().optional(),
      bucketing: bucketing.optional(),
    }).optional(),
  })
  .refine((r) => r.weights.reduce((a, b) => a + b, 0) <= WEIGHT_SCALE, {
    message: `Weights must add up to at most ${WEIGHT_SCALE}`,
  });
export type Rollout = z.infer<typeof rollout>;

export const serve = z.union([
  z.object({ variation: z.number().int().min(0) }),
  z.object({ rollout }),
]);
export type Serve = z.infer<typeof serve>;

export const rule = z.object({
  id: z.string().min(1),
  name: z.string().optional(),
  clauses: z.array(clause),
  serve,
});
export type Rule = z.infer<typeof rule>;

export const target = z.object({
  variation: z.number().int().min(0),
  values: z.array(z.string()),
});
export type Target = z.infer<typeof target>;

export const prerequisite = z.object({ key: flagKey, variation: z.number().int().min(0) });
export type Prerequisite = z.infer<typeof prerequisite>;

/** Targeting for one flag in one environment: what an editor changes. */
export const targeting = z.object({
  enabled: z.boolean(),
  offVariation: z.number().int().min(0),
  fallthrough: serve,
  targets: z.array(target).default([]),
  rules: z.array(rule).default([]),
  prerequisites: z.array(prerequisite).default([]),
});
export type Targeting = z.infer<typeof targeting>;
export type TargetingInput = z.input<typeof targeting>;

export const variation = z.object({
  value: z.unknown(),
  name: z.string().optional(),
  description: z.string().optional(),
});
export type Variation = z.infer<typeof variation>;

export const segmentRules = z.object({
  included: z.array(z.string()).default([]),
  excluded: z.array(z.string()).default([]),
  rules: z.array(z.object({ clauses: z.array(clause) })).default([]),
});
export type SegmentRules = z.infer<typeof segmentRules>;

export const snapshotFlag = targeting.extend({
  key: flagKey,
  type: flagType,
  variations: z.array(z.unknown()).min(1),
  salt: z.string(),
  version: z.number().int(),
});
export type SnapshotFlag = z.infer<typeof snapshotFlag>;

/** A track() event name: what an experiment metric counts. */
export const eventName = z
  .string()
  .min(1)
  .max(128)
  .regex(/^[A-Za-z0-9][A-Za-z0-9._:-]*$/, 'Use letters, digits, dot, dash, colon or underscore, starting with a letter or digit');

/**
 * Running experiments in an environment. Present in the snapshot (and in the browser evaluate response) only
 * while at least one experiment runs, so SDKs send user keys only for flags in an experiment and only the
 * events its metrics count.
 */
export const snapshotExperiments = z.object({
  flags: z.array(flagKey),
  events: z.array(eventName),
});
export type SnapshotExperiments = z.infer<typeof snapshotExperiments>;

export const snapshot = z.object({
  env: z.string(),
  version: z.number().int(),
  generatedAt: z.string(),
  flags: z.record(z.string(), snapshotFlag),
  segments: z.record(z.string(), segmentRules),
  experiments: snapshotExperiments.optional(),
});
export type Snapshot = z.infer<typeof snapshot>;

/** Context passed to evaluation. `key` (or OpenFeature `targetingKey`) identifies the user. */
export type EvalContext = Record<string, unknown> & { key?: string; targetingKey?: string };

export const evalReason = z.enum(['STATIC', 'DEFAULT', 'TARGETING_MATCH', 'SPLIT', 'DISABLED', 'ERROR']);
export type EvalReason = z.infer<typeof evalReason>;

export type EvalErrorCode = 'FLAG_NOT_FOUND' | 'TYPE_MISMATCH' | 'GENERAL' | 'PARSE_ERROR';

export interface EvalResult {
  value: unknown;
  variationIndex: number | null;
  reason: EvalReason;
  ruleId?: string;
  errorCode?: EvalErrorCode;
  errorMessage?: string;
  flagVersion?: number;
}

/** Valid variations for a flag type. */
export function validateVariations(type: FlagType, variations: Variation[]): string | null {
  if (variations.length < 1) return 'A flag needs at least one variation';
  if (type === 'boolean') {
    const vals = variations.map((v) => v.value);
    if (vals.length !== 2 || vals[0] !== false || vals[1] !== true)
      return 'Boolean flags have exactly two variations: false then true';
    return null;
  }
  for (const v of variations) {
    if (type === 'string' && typeof v.value !== 'string') return 'String flag variations must be strings';
    if (type === 'number' && (typeof v.value !== 'number' || !Number.isFinite(v.value)))
      return 'Number flag variations must be numbers';
    if (type === 'json' && (v.value === undefined || typeof v.value === 'function'))
      return 'JSON flag variations must be JSON values';
  }
  return null;
}

/** Checks variation indexes in targeting are in range. Returns the first problem or null. */
export function validateTargetingIndexes(t: Targeting, variationCount: number): string | null {
  const ok = (i: number) => i >= 0 && i < variationCount;
  const serveOk = (s: Serve) =>
    'variation' in s ? ok(s.variation) : s.rollout.weights.length === variationCount;
  if (!ok(t.offVariation)) return 'Off variation is out of range';
  if (!serveOk(t.fallthrough)) return 'Default rule points at a missing variation or has the wrong number of weights';
  for (const tg of t.targets) if (!ok(tg.variation)) return 'Individual target points at a missing variation';
  for (const r of t.rules) if (!serveOk(r.serve)) return `Rule ${r.id} points at a missing variation or has the wrong number of weights`;
  return null;
}

/** One thing to monitor during a Release Watch. Drafted by @railflag/watch, edited by people before approval. */
export const watchSignal = z.object({
  id: z.string().min(1).max(64),
  /** metric: a number from a monitoring tool. issues: new error issues (Sentry). exposure: the rollout split from SDK counts. */
  kind: z.enum(['metric', 'issues', 'exposure']),
  name: z.string().trim().min(1).max(120),
  /** A monitoring connection id; null for exposure signals, which Railflag measures itself. */
  connectionId: z.string().uuid().nullable(),
  /** The vendor's query: PromQL, a Datadog metric query, NRQL or a Sentry issue search. For exposure: the variation index. */
  query: z.string().max(2000),
  /** baseline: since the change vs the same length of time before it. variation: `{variation}` in the query, new vs old. */
  compare: z.enum(['baseline', 'variation']),
  /** increase: higher is worse (errors, latency). decrease: lower is worse (conversion). */
  direction: z.enum(['increase', 'decrease']),
  /** How much worse than the comparison breaks the limit: in the signal's unit, or in percent when relative. */
  threshold: z.object({ amount: z.number().nonnegative().finite(), relative: z.boolean() }),
  /** The limit must stay broken this many minutes before the signal counts as breached. */
  forMinutes: z.number().int().min(1).max(1440),
  /** No verdict until the new variation has been evaluated this many times. */
  minUsers: z.number().int().min(0).max(10_000_000),
  unit: z.string().max(16),
  reason: z.string().max(400),
});
export type WatchSignal = z.infer<typeof watchSignal>;

/**
 * SDK exposure counts: evaluations per flag and variation per minute, no user ids. The relay writes one hash
 * per environment and minute; the worker copies them to flag_exposures.
 */
export const exposureKey = (envId: string, minute: Date) => `ex:${envId}:${minute.toISOString().slice(0, 16)}`;
export const exposureBody = z.object({
  counts: z.record(flagKey, z.record(z.string().regex(/^\d{1,3}$/), z.number().int().min(1).max(10_000_000))),
});

/**
 * Experiment events from the SDKs, POST /sdk/v1/events. Unlike exposure counts these carry user keys, and
 * only for flags with a running experiment (see snapshotExperiments). The relay hashes every key before
 * storing it. Items are checked one by one: a bad item is dropped, the rest are kept.
 *
 * - An exposure is sent once per (flag, variation, user key) per SDK process, when an evaluation of a flag in
 *   `experiments.flags` returns reason SPLIT and the context has a key. A user whose variation changes is sent again.
 * - An event is sent for every track() call whose name is in `experiments.events` and whose context has a key.
 *   `value` is optional; a missing value counts as 0.
 * - `ts` is the client's clock in ms; `sentAt` is the client's clock when the batch was sent, which lets the
 *   relay correct clock skew.
 */
export const MAX_EVENT_ITEMS = 1000;
export const experimentExposureItem = z.object({
  flag: flagKey,
  variation: z.number().int().min(0).max(999),
  key: z.string().min(1).max(256),
  ts: z.number().int().positive(),
});
export const trackEventItem = z.object({
  event: eventName,
  key: z.string().min(1).max(256),
  value: z.number().finite().min(-1e12).max(1e12).optional(),
  ts: z.number().int().positive(),
});
export type ExperimentExposureItem = z.infer<typeof experimentExposureItem>;
export type TrackEventItem = z.infer<typeof trackEventItem>;
export const eventsBody = z.object({
  sentAt: z.number().int().positive().optional(),
  sdk: z.string().max(64).optional(),
  exposures: z.array(z.unknown()).max(MAX_EVENT_ITEMS).default([]),
  events: z.array(z.unknown()).max(MAX_EVENT_ITEMS).default([]),
});
/** The relay queues accepted batches here (user keys already hashed); the worker stores them. */
export const EVENTS_QUEUE_KEY = 'xq';

/**
 * Browser-user metering keys, shared by the relay (writes), the worker (copies to Postgres) and the api
 * (reads the current month live). A month is a UTC calendar month, YYYY-MM.
 */
export const usageMonth = (now = new Date()) => now.toISOString().slice(0, 7);
export const previousUsageMonth = (now = new Date()) => usageMonth(new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1)));
export const orgUsageKey = (orgId: string, month: string) => `bu:org:${orgId}:${month}`;
export const envUsageKey = (envId: string, month: string) => `bu:env:${envId}:${month}`;

const sourceField = z.string().trim().max(100).optional();
/**
 * Where a new organization came from, as railflag.com's attribution.js passes it to the signup page: the site that
 * linked to us (ref), utm tags, the first page of the visit (land) and the page whose signup link was clicked (from).
 */
export const signupSource = z.object({
  ref: sourceField, land: sourceField, from: sourceField, utm_source: sourceField, utm_medium: sourceField, utm_campaign: sourceField,
});
export type SignupSource = z.infer<typeof signupSource>;
export const SIGNUP_SOURCE_KEYS = Object.keys(signupSource.shape) as Array<keyof SignupSource>;

/**
 * SDK sightings, for activation: the relay notes the first and the latest time each kind of SDK used an
 * environment's key (hash field `<envId>|<sdk>`, value epoch ms); the worker copies them to sdk_connections.
 */
export const SDK_FIRST_SEEN_KEY = 'sdk:first';
export const SDK_LAST_SEEN_KEY = 'sdk:last';
export const SDK_KINDS = ['node', 'browser', 'python', 'go', 'java', 'other'] as const;
export type SdkKind = (typeof SDK_KINDS)[number];

/**
 * Which SDK sent a request. The Python, Go and Java SDKs name themselves in User-Agent; the Node SDK uses Node's
 * fetch, which sends "node"; the browser SDK uses a client key. Anything else (curl, a hand-written client) is "other".
 */
export function sdkKind(keyKind: 'server' | 'client', userAgent: string | undefined): SdkKind {
  const ua = userAgent ?? '';
  const named = /^railflag-(node|js|python|go|java)\b/.exec(ua);
  if (named) return named[1] === 'js' ? 'browser' : (named[1] as SdkKind);
  if (keyKind === 'client') return 'browser';
  if (/^(node|undici)\b/.test(ua)) return 'node';
  return 'other';
}
