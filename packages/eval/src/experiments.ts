import type { EvalContext, EvalResult, SnapshotExperiments } from '@railflag/schema';
import { contextKeyOf } from './evaluate.ts';

/**
 * What every SDK sends for experiments. Pure, like the evaluator, and pinned for the other languages by
 * vectors/events-vectors.json (written by `node packages/eval/vectors/build.ts --write`).
 */

export interface ExposureRecord { flag: string; variation: number; key: string }
export interface TrackRecord { event: string; key: string; value: number }

/** The exposure an evaluation produces, or null. Only randomized users count: reason SPLIT, with a user key. */
export function exposureFor(experiments: SnapshotExperiments | undefined, flagKey: string, ctx: EvalContext, result: Pick<EvalResult, 'reason' | 'variationIndex'>): ExposureRecord | null {
  if (!experiments?.flags.includes(flagKey)) return null;
  if (result.reason !== 'SPLIT' || result.variationIndex === null) return null;
  const key = contextKeyOf(ctx);
  if (key === undefined || key === '') return null;
  return { flag: flagKey, variation: result.variationIndex, key };
}

/** The event a track() call produces, or null. A missing or non-finite value counts as 0. */
export function trackFor(experiments: SnapshotExperiments | undefined, event: string, ctx: EvalContext, value?: unknown): TrackRecord | null {
  if (!experiments?.events.includes(event)) return null;
  const key = contextKeyOf(ctx);
  if (key === undefined || key === '') return null;
  return { event, key, value: typeof value === 'number' && Number.isFinite(value) ? value : 0 };
}

/** Remembers which (flag, variation, key) were already sent, so each is sent once. Oldest entries go first when full. */
export class ExposureDedupe {
  private seen = new Set<string>();
  private readonly max: number;
  constructor(max = 50_000) { this.max = max; }

  /** True the first time a record is seen. */
  first(r: ExposureRecord): boolean {
    const id = `${r.flag}\u0000${r.variation}\u0000${r.key}`;
    if (this.seen.has(id)) return false;
    if (this.seen.size >= this.max) this.seen.delete(this.seen.values().next().value!);
    this.seen.add(id);
    return true;
  }
}
