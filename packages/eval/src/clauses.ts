import type { Clause, SegmentRules } from '@railflag/schema';

type Scalar = string | number | boolean;

function parseSemver(v: unknown): [number, number, number] | null {
  if (typeof v !== 'string') return null;
  const m = /^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?/.exec(v.trim());
  if (!m) return null;
  return [Number(m[1]), Number(m[2] ?? 0), Number(m[3] ?? 0)];
}

function cmpSemver(a: [number, number, number], b: [number, number, number]): number {
  for (let i = 0; i < 3; i++) if (a[i] !== b[i]) return a[i]! < b[i]! ? -1 : 1;
  return 0;
}

function toTime(v: unknown): number | null {
  if (typeof v === 'number' && Number.isFinite(v)) return v;
  if (typeof v === 'string') {
    const t = Date.parse(v);
    return Number.isNaN(t) ? null : t;
  }
  return null;
}

function compare(a: unknown, b: Scalar): number | null {
  if (typeof a === 'number' && typeof b === 'number') return a - b;
  if (typeof a === 'string' && typeof b === 'string') return a < b ? -1 : a > b ? 1 : 0;
  return null;
}

const regexCache = new Map<string, RegExp | null>();
function regex(src: string): RegExp | null {
  if (!regexCache.has(src)) {
    let re: RegExp | null = null;
    try {
      re = new RegExp(src);
    } catch {
      re = null;
    }
    regexCache.set(src, re);
  }
  return regexCache.get(src)!;
}

function matchOne(op: Clause['op'], actual: unknown, expected: Scalar): boolean {
  switch (op) {
    case 'in':
      return actual === expected;
    case 'startsWith':
      return typeof actual === 'string' && actual.startsWith(String(expected));
    case 'endsWith':
      return typeof actual === 'string' && actual.endsWith(String(expected));
    case 'contains':
      return typeof actual === 'string' && actual.includes(String(expected));
    case 'matches': {
      const re = typeof actual === 'string' ? regex(String(expected)) : null;
      return re !== null && re.test(actual as string);
    }
    case 'lt': {
      const c = compare(actual, expected);
      return c !== null && c < 0;
    }
    case 'lte': {
      const c = compare(actual, expected);
      return c !== null && c <= 0;
    }
    case 'gt': {
      const c = compare(actual, expected);
      return c !== null && c > 0;
    }
    case 'gte': {
      const c = compare(actual, expected);
      return c !== null && c >= 0;
    }
    case 'semverEq':
    case 'semverLt':
    case 'semverGt': {
      const a = parseSemver(actual);
      const b = parseSemver(expected);
      if (!a || !b) return false;
      const c = cmpSemver(a, b);
      return op === 'semverEq' ? c === 0 : op === 'semverLt' ? c < 0 : c > 0;
    }
    case 'before':
    case 'after': {
      const a = toTime(actual);
      const b = toTime(expected);
      if (a === null || b === null) return false;
      return op === 'before' ? a < b : a > b;
    }
    default:
      return false;
  }
}

export interface ClauseEnv {
  segments: Record<string, SegmentRules>;
  contextKey: string | undefined;
  depth: number;
}

/**
 * A clause matches when any value matches (OR). `negate` inverts the result, except that a missing
 * attribute never matches, negated or not (so "country is not GR" doesn't match users with no country).
 * The `exists` operator is the one exception: it is about the attribute being present.
 */
export function clauseMatches(c: Clause, ctx: Record<string, unknown>, env: ClauseEnv): boolean {
  if (c.op === 'segmentMatch') {
    const hit = c.values.some((s) => segmentMatches(String(s), ctx, env));
    return c.negate ? !hit : hit;
  }
  const actual = c.attr === 'key' ? env.contextKey : ctx[c.attr];
  if (c.op === 'exists') {
    const present = actual !== undefined && actual !== null;
    return c.negate ? !present : present;
  }
  if (actual === undefined || actual === null) return false;
  const values = Array.isArray(actual) ? actual : [actual];
  const hit = values.some((a) => c.values.some((e) => matchOne(c.op, a, e)));
  return c.negate ? !hit : hit;
}

export function segmentMatches(key: string, ctx: Record<string, unknown>, env: ClauseEnv): boolean {
  const seg = env.segments[key];
  if (!seg || env.depth > 5) return false;
  const k = env.contextKey;
  if (k !== undefined) {
    if (seg.excluded.includes(k)) return false;
    if (seg.included.includes(k)) return true;
  }
  const inner = { ...env, depth: env.depth + 1 };
  return seg.rules.some((r) => r.clauses.every((c) => clauseMatches(c, ctx, inner)));
}
