package com.railflag.openfeature;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flag evaluation, ported from packages/eval (TypeScript). Both must give identical results for the
 * vectors in packages/eval/vectors/eval-vectors.json; change them together or not at all.
 *
 * <p>Snapshots and contexts hold JSON-like values: {@code Map<String, Object>}, {@code List<Object>},
 * {@code Double}, {@code String}, {@code Boolean} and {@code null} (see {@link Json}).
 */
public final class Evaluator {
    private Evaluator() {}

    /** The number of buckets a rollout is spread over. */
    public static final int WEIGHT_SCALE = 100_000;

    private static final long UNLEASH_VARIANT_SEED = 86028157L;

    // ---- bucketing ------------------------------------------------------------------------

    /** A bucket in [0, WEIGHT_SCALE). The same inputs always give the same bucket. */
    static double bucketFor(String flagKey, String salt, String value, Map<?, ?> bucketing) {
        Object algorithm = bucketing == null ? null : bucketing.get("algorithm");
        Object seedValue = bucketing == null ? null : bucketing.get("seed");
        boolean hasSeed = seedValue instanceof String;
        String seed = hasSeed ? (String) seedValue : "";
        if ("abtasty".equals(algorithm)) {
            return (double) (Murmur3.hashLatin1(seed + value, 0) % 100) * (WEIGHT_SCALE / 100);
        }
        if ("unleash".equals(algorithm)) {
            if (!hasSeed) seed = flagKey;
            return (double) (Murmur3.hashLatin1(seed + ":" + value, 0) % 100) * (WEIGHT_SCALE / 100);
        }
        if ("unleash-variant".equals(algorithm)) {
            if (!hasSeed) seed = flagKey;
            long m = 1000;
            Object mod = bucketing.get("modulus");
            if (mod instanceof Double) m = ((Double) mod).longValue() & 0xffffffffL;
            long h = Murmur3.hashLatin1(seed + ":" + value, UNLEASH_VARIANT_SEED) % m;
            return Math.floor((double) h * WEIGHT_SCALE / (double) m);
        }
        long h = Murmur3.hash(flagKey + "." + salt + "." + value, 0);
        return Math.floor((double) h / 0x1_0000_0000L * WEIGHT_SCALE);
    }

    /**
     * Lays out cumulative ranges in variation-index order, or in {@code order} when given, and returns
     * the variation whose range holds the bucket (null when unallocated).
     */
    private static Integer pickWeighted(List<?> weights, double bucket, List<?> order) {
        double total = 0;
        if (order != null) {
            for (Object o : order) {
                int i = toInt(o);
                total += weightAt(weights, i);
                if (bucket < total) return i;
            }
            return null;
        }
        for (int i = 0; i < weights.size(); i++) {
            total += weightAt(weights, i);
            if (bucket < total) return i;
        }
        return null;
    }

    private static double weightAt(List<?> weights, int i) {
        if (i < 0 || i >= weights.size()) return 0;
        Object w = weights.get(i);
        return w instanceof Double ? (Double) w : 0;
    }

    private static int toInt(Object v) {
        if (!(v instanceof Double)) {
            throw new IllegalStateException("expected a number, got " + (v == null ? "null" : v.getClass().getSimpleName()));
        }
        return (int) (double) (Double) v;
    }

    // ---- clauses --------------------------------------------------------------------------

    private static final Pattern SEMVER = Pattern.compile("^v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");

    private static double[] parseSemver(Object v) {
        if (!(v instanceof String)) return null;
        Matcher m = SEMVER.matcher(trimJs((String) v));
        if (!m.lookingAt()) return null;
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            if (m.group(i + 1) != null) out[i] = Double.parseDouble(m.group(i + 1));
        }
        return out;
    }

    /** {@code String.prototype.trim}: JavaScript whitespace and line terminators. */
    static String trimJs(String s) {
        int start = 0, end = s.length();
        while (start < end && isJsSpace(s.charAt(start))) start++;
        while (end > start && isJsSpace(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    private static boolean isJsSpace(char c) {
        switch (c) {
            case '\t': case '\n': case 0x0B: case '\f': case '\r': case ' ': case 0xA0: case 0x1680:
            case 0x2028: case 0x2029: case 0x202F: case 0x205F: case 0x3000: case 0xFEFF:
                return true;
            default:
                return c >= 0x2000 && c <= 0x200A;
        }
    }

    private static int cmpSemver(double[] a, double[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return a[i] < b[i] ? -1 : 1;
        }
        return 0;
    }

    private static boolean matchOne(String op, Object actual, Object expected) {
        switch (op) {
            case "in":
                return Js.strictEquals(actual, expected);
            case "startsWith":
                return actual instanceof String && ((String) actual).startsWith(Js.string(expected));
            case "endsWith":
                return actual instanceof String && ((String) actual).endsWith(Js.string(expected));
            case "contains":
                return actual instanceof String && ((String) actual).contains(Js.string(expected));
            case "matches": {
                if (!(actual instanceof String)) return false;
                Pattern re = Js.regex(Js.string(expected));
                return re != null && re.matcher((String) actual).find();
            }
            case "lt":
            case "lte":
            case "gt":
            case "gte": {
                Double c = Js.compare(actual, expected);
                if (c == null || c.isNaN()) return false;
                switch (op) {
                    case "lt":
                        return c < 0;
                    case "lte":
                        return c <= 0;
                    case "gt":
                        return c > 0;
                    default:
                        return c >= 0;
                }
            }
            case "semverEq":
            case "semverLt":
            case "semverGt": {
                double[] a = parseSemver(actual), b = parseSemver(expected);
                if (a == null || b == null) return false;
                int c = cmpSemver(a, b);
                return op.equals("semverEq") ? c == 0 : op.equals("semverLt") ? c < 0 : c > 0;
            }
            case "before":
            case "after": {
                Double ta = Js.toTime(actual), tb = Js.toTime(expected);
                if (ta == null || tb == null) return false;
                return op.equals("before") ? ta < tb : ta > tb;
            }
            default:
                return false;
        }
    }

    private static final class ClauseEnv {
        final Map<?, ?> segments;
        final String contextKey;
        final int depth;

        ClauseEnv(Map<?, ?> segments, String contextKey, int depth) {
            this.segments = segments == null ? Collections.emptyMap() : segments;
            this.contextKey = contextKey;
            this.depth = depth;
        }
    }

    /**
     * A clause matches when any value matches (OR). {@code negate} inverts the result, except that a
     * missing attribute never matches, negated or not. {@code exists} is about presence.
     */
    private static boolean clauseMatches(Map<?, ?> c, Map<?, ?> ctx, ClauseEnv env) {
        Object opValue = c.get("op");
        String op = opValue instanceof String ? (String) opValue : "";
        boolean negate = truthy(c.get("negate"));
        List<?> values = list(c.get("values"));
        if (op.equals("segmentMatch")) {
            boolean hit = false;
            for (Object s : values) {
                if (segmentMatches(Js.string(s), ctx, env)) {
                    hit = true;
                    break;
                }
            }
            return hit != negate;
        }
        Object attr = c.get("attr");
        Object actual = "key".equals(attr) ? env.contextKey : ctx.get(attr instanceof String ? attr : "");
        if (op.equals("exists")) return (actual != null) != negate;
        if (actual == null) return false;
        List<?> actuals = actual instanceof List ? (List<?>) actual : Collections.singletonList(actual);
        boolean hit = false;
        outer:
        for (Object a : actuals) {
            for (Object e : values) {
                if (matchOne(op, a, e)) {
                    hit = true;
                    break outer;
                }
            }
        }
        return hit != negate;
    }

    private static boolean segmentMatches(String key, Map<?, ?> ctx, ClauseEnv env) {
        Object segValue = env.segments.get(key);
        if (!(segValue instanceof Map) || env.depth > 5) return false;
        Map<?, ?> seg = (Map<?, ?>) segValue;
        if (env.contextKey != null) {
            if (includesString(seg.get("excluded"), env.contextKey)) return false;
            if (includesString(seg.get("included"), env.contextKey)) return true;
        }
        ClauseEnv inner = new ClauseEnv(env.segments, env.contextKey, env.depth + 1);
        for (Object r : list(seg.get("rules"))) {
            if (allClauses(((Map<?, ?>) r).get("clauses"), ctx, inner)) return true;
        }
        return false;
    }

    private static boolean allClauses(Object clauses, Map<?, ?> ctx, ClauseEnv env) {
        for (Object c : list(clauses)) {
            if (!clauseMatches((Map<?, ?>) c, ctx, env)) return false;
        }
        return true;
    }

    private static boolean includesString(Object items, String s) {
        for (Object x : list(items)) {
            if (x instanceof String && x.equals(s)) return true;
        }
        return false;
    }

    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Double) {
            double d = (Double) v;
            return d != 0 && !Double.isNaN(d);
        }
        if (v instanceof String) return !((String) v).isEmpty();
        return true;
    }

    private static List<?> list(Object v) {
        return v instanceof List ? (List<?>) v : Collections.emptyList();
    }

    private static Map<?, ?> map(Object v) {
        return v instanceof Map ? (Map<?, ?>) v : null;
    }

    private static String str(Object v) {
        return v instanceof String ? (String) v : "";
    }

    // ---- evaluation -----------------------------------------------------------------------

    /** The key a context is bucketed and targeted by: {@code String(targetingKey ?? key)}, or null. */
    static String contextKeyOf(Map<?, ?> ctx) {
        Object k = ctx.get("targetingKey");
        if (k == null) k = ctx.get("key");
        return k == null ? null : Js.string(k);
    }

    private static String typeOf(Object v) {
        if (v instanceof Boolean) return "boolean";
        if (v instanceof String) return "string";
        if (v instanceof Double) return "number";
        return "json";
    }

    private static final class Resolved {
        final int index;
        final String reason;
        final String ruleId;

        Resolved(int index, String reason, String ruleId) {
            this.index = index;
            this.reason = reason;
            this.ruleId = ruleId;
        }
    }

    private static Object attrValue(Map<?, ?> ctx, String attr) {
        return attr.equals("key") ? contextKeyOf(ctx) : ctx.get(attr);
    }

    private static Resolved resolveServe(Map<?, ?> flag, Map<?, ?> serve, Map<?, ?> ctx, String ruleId) {
        if (serve.containsKey("variation")) {
            return new Resolved(toInt(serve.get("variation")), ruleId.isEmpty() ? "DEFAULT" : "TARGETING_MATCH", ruleId);
        }
        Map<?, ?> rollout = (Map<?, ?>) serve.get("rollout");
        String attr = rollout.get("bucketBy") instanceof String ? (String) rollout.get("bucketBy") : "key";
        String flagKey = str(flag.get("key")), salt = str(flag.get("salt"));
        Map<?, ?> gate = map(rollout.get("gate"));
        if (gate != null) {
            String gateAttr = gate.get("bucketBy") instanceof String ? (String) gate.get("bucketBy") : attr;
            double gateBucket = 0;
            Object g = attrValue(ctx, gateAttr);
            if (g != null) gateBucket = bucketFor(flagKey, salt, Js.string(g), map(gate.get("bucketing")));
            Object w = gate.get("weight");
            if (w instanceof Double && gateBucket >= (Double) w) return null; // outside the allocation: next rule
        }
        // A missing bucketing value goes to bucket 0, so it is still deterministic.
        double bucket = 0;
        Object raw = attrValue(ctx, attr);
        if (raw != null) bucket = bucketFor(flagKey, salt, Js.string(raw), map(rollout.get("bucketing")));
        Object order = rollout.get("order");
        Integer index = pickWeighted(list(rollout.get("weights")), bucket, order instanceof List ? (List<?>) order : null);
        return index == null ? null : new Resolved(index, "SPLIT", ruleId);
    }

    private static Resolved evaluateFlag(Map<?, ?> snap, Map<?, ?> flag, Map<?, ?> ctx, Set<String> seen) {
        int off = toInt(flag.get("offVariation"));
        if (!Boolean.TRUE.equals(flag.get("enabled"))) return new Resolved(off, "DISABLED", "");

        Map<?, ?> flags = map(snap.get("flags"));
        for (Object x : list(flag.get("prerequisites"))) {
            Map<?, ?> p = (Map<?, ?>) x;
            String pk = str(p.get("key"));
            Map<?, ?> pre = flags == null ? null : map(flags.get(pk));
            if (pre == null || seen.contains(pk)) return new Resolved(off, "DEFAULT", "prerequisite:" + pk);
            seen.add(pk);
            Resolved r = evaluateFlag(snap, pre, ctx, seen);
            seen.remove(pk);
            Object want = p.get("variation");
            boolean sameVariation = want instanceof Double && (double) r.index == (Double) want;
            if (!Boolean.TRUE.equals(pre.get("enabled")) || !sameVariation) {
                return new Resolved(off, "DEFAULT", "prerequisite:" + pk);
            }
        }

        String key = contextKeyOf(ctx);
        if (key != null) {
            for (Object x : list(flag.get("targets"))) {
                Map<?, ?> t = (Map<?, ?>) x;
                if (includesString(t.get("values"), key)) return new Resolved(toInt(t.get("variation")), "TARGETING_MATCH", "target");
            }
        }

        ClauseEnv env = new ClauseEnv(map(snap.get("segments")), key, 0);
        for (Object x : list(flag.get("rules"))) {
            Map<?, ?> rule = (Map<?, ?>) x;
            if (!allClauses(rule.get("clauses"), ctx, env)) continue;
            Resolved r = resolveServe(flag, (Map<?, ?>) rule.get("serve"), ctx, str(rule.get("id")));
            if (r != null) return r; // null = matched but not allocated: continue to the next rule
        }

        Resolved r = resolveServe(flag, (Map<?, ?>) flag.get("fallthrough"), ctx, "");
        return r != null ? r : new Resolved(off, "DEFAULT", "");
    }

    /**
     * Evaluates one flag. It never throws: problems come back as reason "ERROR" with an error code.
     * {@code expectedType} is null, "boolean", "string", "number" or "json"; a variation of another type
     * gives TYPE_MISMATCH. {@code ctx} holds JSON-like values (numbers as Double, times as epoch ms).
     */
    public static EvalResult evaluate(Map<String, Object> snap, String flagKey, Map<String, Object> ctx, String expectedType) {
        Map<?, ?> context = ctx == null ? Collections.emptyMap() : ctx;
        Integer version = null;
        try {
            Map<?, ?> flags = map(snap.get("flags"));
            Map<?, ?> flag = flags == null ? null : map(flags.get(flagKey));
            if (flag == null) return EvalResult.error("FLAG_NOT_FOUND", "Flag " + flagKey + " not found", null);
            if (flag.get("version") instanceof Double) version = toInt(flag.get("version"));
            Set<String> seen = new HashSet<>();
            seen.add(flagKey);
            Resolved r = evaluateFlag(snap, flag, context, seen);
            List<?> variations = list(flag.get("variations"));
            if (r.index < 0 || r.index >= variations.size()) {
                return EvalResult.error("GENERAL", "Variation index out of range", version);
            }
            Object value = variations.get(r.index);
            if (expectedType != null && !expectedType.isEmpty() && !expectedType.equals("json") && !typeOf(value).equals(expectedType)) {
                return EvalResult.error("TYPE_MISMATCH",
                        "Flag " + flagKey + " is " + Js.string(flag.get("type")) + ", not " + expectedType, version);
            }
            return new EvalResult(value, r.index, r.reason, r.ruleId.isEmpty() ? null : r.ruleId, version, null, null);
        } catch (RuntimeException | StackOverflowError e) {
            return EvalResult.error("GENERAL", String.valueOf(e.getMessage() != null ? e.getMessage() : e), version);
        }
    }

    /** Evaluates every flag in the snapshot. */
    public static Map<String, EvalResult> evaluateAll(Map<String, Object> snap, Map<String, Object> ctx) {
        Map<String, EvalResult> out = new LinkedHashMap<>();
        Map<?, ?> flags = map(snap.get("flags"));
        if (flags == null) return out;
        for (Object k : flags.keySet()) out.put((String) k, evaluate(snap, (String) k, ctx, null));
        return out;
    }
}
