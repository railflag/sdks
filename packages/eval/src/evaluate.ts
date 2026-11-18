import type { EvalContext, EvalResult, FlagType, Serve, Snapshot, SnapshotFlag } from '@railflag/schema';
import { bucketFor, pickWeighted } from './bucket.ts';
import { clauseMatches, type ClauseEnv } from './clauses.ts';

export interface EvaluateOptions {
  /** When set, a variation of another type returns an error result instead of the value. */
  expectedType?: FlagType;
}

export function contextKeyOf(ctx: EvalContext): string | undefined {
  const k = ctx.targetingKey ?? ctx.key;
  return k === undefined || k === null ? undefined : String(k);
}

function typeOf(v: unknown): FlagType {
  if (typeof v === 'boolean') return 'boolean';
  if (typeof v === 'string') return 'string';
  if (typeof v === 'number') return 'number';
  return 'json';
}

type Resolved = { index: number; reason: EvalResult['reason']; ruleId?: string } | null;

function resolveServe(flag: SnapshotFlag, s: Serve, ctx: EvalContext, ruleId: string | undefined): Resolved {
  if ('variation' in s) return { index: s.variation, reason: ruleId ? 'TARGETING_MATCH' : 'DEFAULT', ruleId };
  const attr = s.rollout.bucketBy ?? 'key';
  const gate = s.rollout.gate;
  if (gate) {
    const gateAttr = gate.bucketBy ?? attr;
    const g = gateAttr === 'key' ? contextKeyOf(ctx) : ctx[gateAttr];
    const gateBucket = g === undefined || g === null ? 0 : bucketFor(flag.key, flag.salt, String(g), gate.bucketing);
    if (gateBucket >= gate.weight) return null; // outside the allocation: continue to the next rule
  }
  const raw = attr === 'key' ? contextKeyOf(ctx) : ctx[attr];
  // A missing bucketing value goes to bucket 0, so it is still deterministic.
  const bucket = raw === undefined || raw === null ? 0 : bucketFor(flag.key, flag.salt, String(raw), s.rollout.bucketing);
  const index = pickWeighted(s.rollout.weights, bucket, s.rollout.order);
  if (index === null) return null;
  return { index, reason: 'SPLIT', ruleId };
}

function evaluateFlag(snapshot: Snapshot, flag: SnapshotFlag, ctx: EvalContext, seen: Set<string>): Resolved {
  if (!flag.enabled) return { index: flag.offVariation, reason: 'DISABLED' };

  for (const p of flag.prerequisites) {
    const pre = snapshot.flags[p.key];
    if (!pre || seen.has(p.key)) return { index: flag.offVariation, reason: 'DEFAULT', ruleId: `prerequisite:${p.key}` };
    seen.add(p.key);
    const r = evaluateFlag(snapshot, pre, ctx, seen);
    seen.delete(p.key);
    if (!r || !pre.enabled || r.index !== p.variation)
      return { index: flag.offVariation, reason: 'DEFAULT', ruleId: `prerequisite:${p.key}` };
  }

  const key = contextKeyOf(ctx);
  if (key !== undefined) {
    for (const t of flag.targets) {
      if (t.values.includes(key)) return { index: t.variation, reason: 'TARGETING_MATCH', ruleId: 'target' };
    }
  }

  const env: ClauseEnv = { segments: snapshot.segments, contextKey: key, depth: 0 };
  for (const rule of flag.rules) {
    if (!rule.clauses.every((c) => clauseMatches(c, ctx, env))) continue;
    const r = resolveServe(flag, rule.serve, ctx, rule.id);
    if (r) return r; // null = matched but not allocated: continue to the next rule
  }

  return resolveServe(flag, flag.fallthrough, ctx, undefined) ?? { index: flag.offVariation, reason: 'DEFAULT' };
}

/**
 * Evaluates one flag. Never throws: problems come back as reason ERROR with an error code, and the
 * caller (an SDK) then returns the code default it was given.
 */
export function evaluate(snapshot: Snapshot, flagKey: string, ctx: EvalContext = {}, opts: EvaluateOptions = {}): EvalResult {
  try {
    const flag = snapshot.flags[flagKey];
    if (!flag) return { value: undefined, variationIndex: null, reason: 'ERROR', errorCode: 'FLAG_NOT_FOUND', errorMessage: `Flag ${flagKey} not found` };
    const r = evaluateFlag(snapshot, flag, ctx, new Set([flagKey]));
    const index = r?.index ?? flag.offVariation;
    if (index < 0 || index >= flag.variations.length)
      return { value: undefined, variationIndex: null, reason: 'ERROR', errorCode: 'GENERAL', errorMessage: 'Variation index out of range', flagVersion: flag.version };
    const value = flag.variations[index];
    if (opts.expectedType && typeOf(value) !== opts.expectedType && !(opts.expectedType === 'json'))
      return { value: undefined, variationIndex: null, reason: 'ERROR', errorCode: 'TYPE_MISMATCH', errorMessage: `Flag ${flagKey} is ${flag.type}, not ${opts.expectedType}`, flagVersion: flag.version };
    return { value, variationIndex: index, reason: r?.reason ?? 'DEFAULT', ruleId: r?.ruleId, flagVersion: flag.version };
  } catch (e) {
    return { value: undefined, variationIndex: null, reason: 'ERROR', errorCode: 'GENERAL', errorMessage: e instanceof Error ? e.message : String(e) };
  }
}

/** Evaluates every flag in a snapshot (used by remote evaluation for browsers). */
export function evaluateAll(snapshot: Snapshot, ctx: EvalContext): Record<string, EvalResult> {
  const out: Record<string, EvalResult> = {};
  for (const key of Object.keys(snapshot.flags)) out[key] = evaluate(snapshot, key, ctx);
  return out;
}
