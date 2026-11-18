import { WEIGHT_SCALE, type Bucketing } from '@railflag/schema';
import { murmur3, murmur3Latin1 } from './murmur3.ts';

const UNLEASH_VARIANT_SEED = 86028157;

/**
 * Returns a bucket in [0, WEIGHT_SCALE). Same inputs always give the same bucket, and raising
 * a rollout percentage only adds users, because ranges are cumulative over a fixed bucket.
 */
export function bucketFor(flagKey: string, salt: string, value: string, bucketing?: Bucketing): number {
  if (bucketing?.algorithm === 'abtasty') {
    // AB Tasty: murmur3(variationGroupId + visitorId) % 100, compared against percent allocations.
    const seed = bucketing.seed ?? '';
    return (murmur3Latin1(seed + value) % 100) * (WEIGHT_SCALE / 100);
  }
  if (bucketing?.algorithm === 'unleash') {
    // Unleash strategies: murmur3("groupId:stickiness", seed 0) % 100 + 1, included when <= rollout.
    // Bucket b = hash % 100 is in when b < rollout, so b maps to [b * 1000, (b + 1) * 1000).
    // Latin-1 like the Node client and server (murmurhash3js reads charCodeAt & 0xff).
    return (murmur3Latin1(`${bucketing.seed ?? flagKey}:${value}`, 0) % 100) * (WEIGHT_SCALE / 100);
  }
  if (bucketing?.algorithm === 'unleash-variant') {
    // Unleash variants: murmur3("groupId:stickiness", seed 86028157) % totalWeight + 1, walked over cumulative weights.
    const m = bucketing.modulus ?? 1000;
    return Math.floor(((murmur3Latin1(`${bucketing.seed ?? flagKey}:${value}`, UNLEASH_VARIANT_SEED) % m) * WEIGHT_SCALE) / m);
  }
  const h = murmur3(`${flagKey}.${salt}.${value}`);
  return Math.floor((h / 0x1_0000_0000) * WEIGHT_SCALE);
}

/**
 * Picks the variation whose cumulative weight range holds the bucket, or null if the bucket is unallocated.
 * Ranges are laid out in variation-index order, or in `order` (variation indexes) when a vendor lays them out differently.
 */
export function pickWeighted(weights: number[], bucket: number, order?: number[]): number | null {
  let total = 0;
  for (const i of order ?? weights.keys()) {
    total += weights[i] ?? 0;
    if (bucket < total) return i;
  }
  return null;
}
