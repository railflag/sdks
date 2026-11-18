import { describe, expect, it } from 'vitest';
import { murmur3, murmur3Latin1, bucketFor } from '../src/index.ts';
import { abtastyMurmurHash3Int32 } from './abtasty-reference.ts';

describe('murmur3', () => {
  it('matches published MurmurHash3 x86_32 values', () => {
    expect(murmur3('', 0)).toBe(0);
    expect(murmur3('', 1)).toBe(0x514e28b7);
    expect(murmur3('hello', 0)).toBe(613153351);
    expect(murmur3('The quick brown fox jumps over the lazy dog', 0)).toBe(0x2e4ff723);
  });

  it('reproduces AB Tasty hashing for 100,000 ids, including non-ASCII', () => {
    for (let i = 0; i < 100_000; i++) {
      const id = `vg-${i % 37}user-${i}${i % 11 === 0 ? 'ΑΒΓ-é' : ''}`;
      expect(murmur3Latin1(id)).toBe(abtastyMurmurHash3Int32(id));
    }
  });

  it('AB Tasty buckets are (hash % 100) on the percent scale', () => {
    const vg = 'c0ffee12vg';
    for (const v of ['visitor-1', 'visitor-2', 'Ζωή']) {
      expect(bucketFor('ignored', 'ignored', v, { algorithm: 'abtasty', seed: vg })).toBe(
        (abtastyMurmurHash3Int32(vg + v) % 100) * 1000,
      );
    }
  });
});
