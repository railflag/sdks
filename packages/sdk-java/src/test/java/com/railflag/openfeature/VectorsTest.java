package com.railflag.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The Java evaluator must reproduce the TypeScript evaluator exactly. The vectors are generated from
 * packages/eval by packages/eval/vectors/build.ts.
 */
class VectorsTest {
    private static final Path VECTORS = Path.of("../eval/vectors/eval-vectors.json");

    @SuppressWarnings("unchecked")
    static Map<String, Object> vectors() throws IOException {
        assumeTrue(Files.exists(VECTORS), "eval vectors not found (they live in the Railflag monorepo)");
        return (Map<String, Object>) Json.parse(new String(Files.readAllBytes(VECTORS), StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> v, String name) {
        return (List<Map<String, Object>>) v.get(name);
    }

    /** A result in the same JSON shape the TypeScript evaluator produces. */
    static Map<String, Object> asJson(EvalResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("variationIndex", r.variationIndex == null ? null : (double) r.variationIndex);
        out.put("reason", r.reason);
        if (r.variationIndex != null) out.put("value", r.value);
        if (r.ruleId != null) out.put("ruleId", r.ruleId);
        if (r.flagVersion != null) out.put("flagVersion", (double) r.flagVersion);
        if (r.errorCode != null) out.put("errorCode", r.errorCode);
        if (r.errorMessage != null) out.put("errorMessage", r.errorMessage);
        return out;
    }

    @Test
    void hashes() throws IOException {
        List<Map<String, Object>> hashes = items(vectors(), "hashes");
        assertTrue(hashes.size() >= 400, "only " + hashes.size() + " hash vectors");
        for (Map<String, Object> h : hashes) {
            String in = (String) h.get("input");
            long seed = ((Double) h.get("seed")).longValue();
            assertEquals(((Double) h.get("murmur3")).longValue(), Murmur3.hash(in, seed), "murmur3 of " + Json.stringify(in));
            assertEquals(((Double) h.get("latin1")).longValue(), Murmur3.hashLatin1(in, seed), "latin1 of " + Json.stringify(in));
        }
    }

    @Test
    void buckets() throws IOException {
        for (Map<String, Object> b : items(vectors(), "buckets")) {
            double got = Evaluator.bucketFor((String) b.get("flagKey"), (String) b.get("salt"), (String) b.get("value"),
                    (Map<?, ?>) b.get("bucketing"));
            assertEquals((Double) b.get("bucket"), got, () -> "bucket for " + Json.stringify(b));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyCaseMatches() throws IOException {
        Map<String, Object> v = vectors();
        Map<String, Object> snap = (Map<String, Object>) v.get("snapshot");
        int n = 0;
        for (Map<String, Object> c : items(v, "cases")) {
            Map<String, Object> ctx = (Map<String, Object>) c.get("context");
            Map<String, EvalResult> got = Evaluator.evaluateAll(snap, ctx);
            for (Map.Entry<String, Object> want : ((Map<String, Object>) c.get("results")).entrySet()) {
                Map<String, Object> g = asJson(got.get(want.getKey()));
                if (!g.equals(want.getValue())) {
                    fail("flag " + want.getKey() + " with context " + Json.stringify(ctx) + ":\n got  " + Json.stringify(g)
                            + "\n want " + Json.stringify(want.getValue()));
                }
                n++;
            }
        }
        assertTrue(n >= 5000, "only " + n + " evaluations");
    }

    @Test
    @SuppressWarnings("unchecked")
    void typed() throws IOException {
        Map<String, Object> v = vectors();
        Map<String, Object> snap = (Map<String, Object>) v.get("snapshot");
        for (Map<String, Object> c : items(v, "typed")) {
            EvalResult r = Evaluator.evaluate(snap, (String) c.get("flagKey"), (Map<String, Object>) c.get("context"),
                    (String) c.get("expectedType"));
            Map<String, Object> got = asJson(r);
            if (!got.equals(c.get("result"))) {
                fail(c.get("flagKey") + " as " + c.get("expectedType") + ":\n got  " + Json.stringify(got) + "\n want " + Json.stringify(c.get("result")));
            }
        }
    }

    @Test
    void numbersPrintAsInJavaScript() {
        double tenth = 0.1;
        Object[][] cases = {
            {0.0, "0"}, {-0.0, "0"}, {1.0, "1"}, {1.5, "1.5"}, {100.0, "100"}, {1e21, "1e+21"}, {1e-7, "1e-7"},
            {123456789012345680000.0, "123456789012345680000"}, {0.000001, "0.000001"}, {1.5e-10, "1.5e-10"},
            {-2.5e30, "-2.5e+30"}, {1e22, "1e+22"}, {Double.POSITIVE_INFINITY, "Infinity"}, {Double.NaN, "NaN"},
            {tenth + 0.2, "0.30000000000000004"}, {5e-324, "5e-324"}, {Double.MAX_VALUE, "1.7976931348623157e+308"},
            // Double.toString gives "2.0E-3" and, before Java 19, "1.0E23" for this one: not shortest.
            {0.002, "0.002"}, {1e23, "1e+23"},
        };
        for (Object[] c : cases) assertEquals(c[1], Js.numberToString((Double) c[0]), "String(" + c[0] + ")");
    }

    @Test
    void jsonKeepsLoneSurrogatesAndReadsNumbersAsDoubles() {
        List<?> got = (List<?>) Json.parse("[\"\\ud800x\", \"\\ud83d\\ude00\", \"\\u00e9\", 1, -2.5e3, {\"a\": [true, null]}]");
        assertEquals("\ud800x", got.get(0));
        assertEquals("😀", got.get(1));
        assertEquals("é", got.get(2));
        assertEquals(Arrays.asList(1.0, -2500.0), got.subList(3, 5));
        assertEquals("{\"a\":[true,null]}", Json.stringify(got.get(5)));
    }

    @Test
    void versionMatchesTheBuild() throws IOException {
        Properties p = new Properties();
        try (var in = Files.newInputStream(Path.of("gradle.properties"))) {
            p.load(in);
        }
        assertEquals(p.getProperty("version"), RailflagProvider.VERSION);
    }
}
