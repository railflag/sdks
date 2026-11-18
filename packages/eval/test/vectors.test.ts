import { readFileSync } from 'node:fs';
import { expect, it } from 'vitest';
import { buildVectors } from '../vectors/build.ts';

/**
 * The committed vectors are what other SDKs (Python) are tested against. If this fails, evaluation
 * changed: that is only OK on purpose, and then the vectors and every SDK must be updated together.
 */
it('evaluator still matches the cross-language vectors', () => {
  const committed = JSON.parse(readFileSync(new URL('../vectors/eval-vectors.json', import.meta.url), 'utf8'));
  expect(JSON.parse(JSON.stringify(buildVectors()))).toEqual(committed);
});
