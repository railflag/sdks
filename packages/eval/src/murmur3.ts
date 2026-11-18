const C1 = 0xcc9e2d51;
const C2 = 0x1b873593;

function hashBytes(bytes: ArrayLike<number>, seed: number): number {
  let h = seed >>> 0;
  const len = bytes.length;
  const n = len & ~3;
  let k: number;
  for (let i = 0; i < n; i += 4) {
    k = bytes[i]! | (bytes[i + 1]! << 8) | (bytes[i + 2]! << 16) | (bytes[i + 3]! << 24);
    k = Math.imul(k, C1);
    k = (k << 15) | (k >>> 17);
    k = Math.imul(k, C2);
    h ^= k;
    h = (h << 13) | (h >>> 19);
    h = (Math.imul(h, 5) + 0xe6546b64) | 0;
  }
  k = 0;
  const rem = len & 3;
  if (rem === 3) k ^= bytes[n + 2]! << 16;
  if (rem >= 2) k ^= bytes[n + 1]! << 8;
  if (rem >= 1) {
    k ^= bytes[n]!;
    k = Math.imul(k, C1);
    k = (k << 15) | (k >>> 17);
    k = Math.imul(k, C2);
    h ^= k;
  }
  h ^= len;
  h ^= h >>> 16;
  h = Math.imul(h, 0x85ebca6b);
  h ^= h >>> 13;
  h = Math.imul(h, 0xc2b2ae35);
  h ^= h >>> 16;
  return h >>> 0;
}

const encoder = new TextEncoder();

/** MurmurHash3 x86 32-bit over the UTF-8 bytes of `input`. */
export function murmur3(input: string, seed = 0): number {
  return hashBytes(encoder.encode(input), seed);
}

/**
 * MurmurHash3 x86 32-bit over the low byte of each UTF-16 code unit, which is how
 * AB Tasty's SDKs hash (they read `charCodeAt(i) & 0xff`). Differs from `murmur3` for non-ASCII input.
 */
export function murmur3Latin1(input: string, seed = 0): number {
  const bytes = new Uint8Array(input.length);
  for (let i = 0; i < input.length; i++) bytes[i] = input.charCodeAt(i) & 0xff;
  return hashBytes(bytes, seed);
}
