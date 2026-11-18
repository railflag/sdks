package com.railflag.openfeature;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Value;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * End-to-end check of the Java SDK against a running Railflag (production by default), like
 * packages/sdk-python/examples/live_check.py.
 *
 * <pre>
 * cd packages/sdk-java
 * SY_EMAIL=you@example.com SY_PASSWORD=... ./gradlew liveCheck
 * </pre>
 *
 * Signs in to the api, rotates the Development server key (the raw key is only shown once), creates or
 * reuses the flag {@code sdk-live-check} in Development, checks that changes stream in and that rules
 * and rollouts evaluate right, then switches the flag off and archives it.
 */
public final class LiveCheck {
    private static final String API = env("SY_API", "https://api.railflag.com");
    private static final String RELAY = env("SY_RELAY", "https://relay.railflag.com");
    private static final String FLAG = "sdk-live-check";
    private static final String ENV = "development";
    private static final HttpClient http = HttpClient.newHttpClient();
    private static String cookie = "";
    private static int failures;

    private LiveCheck() {}

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? fallback : v;
    }

    private static Object call(String method, String path, Object body) throws IOException, InterruptedException {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(API + path)).header("cookie", cookie);
        if (body != null) req.header("content-type", "application/json");
        req.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(Json.stringify(body)));
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        res.headers().firstValue("set-cookie").ifPresent(c -> cookie = c.split(";")[0]);
        if (res.statusCode() >= 400) {
            throw new IOException(method + " " + path + " -> " + res.statusCode() + " " + res.body().substring(0, Math.min(300, res.body().length())));
        }
        return res.body().isEmpty() ? null : Json.parse(res.body());
    }

    private static String p(String rest) {
        return "/v1/projects/default" + rest;
    }

    private static Map<?, ?> env(Object flagOrEnvs, String key) {
        for (Object e : (List<?>) ((Map<?, ?>) flagOrEnvs).get("environments")) {
            if (key.equals(((Map<?, ?>) e).get("key"))) return (Map<?, ?>) e;
        }
        throw new IllegalStateException("no environment " + key);
    }

    private static void setTargeting(Map<String, Object> targeting) throws IOException, InterruptedException {
        Map<?, ?> e = env(call("GET", p("/flags/" + FLAG), null), ENV);
        call("PUT", p("/flags/" + FLAG + "/environments/" + ENV + "/targeting"), Map.of("targeting", targeting, "baseVersion", e.get("version")));
    }

    private static Map<String, Object> proRule(Map<String, Object> fallthrough, boolean enabled, boolean withRule) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("enabled", enabled);
        t.put("offVariation", 0.0);
        t.put("fallthrough", fallthrough);
        t.put("targets", List.of());
        t.put("prerequisites", List.of());
        t.put("rules", withRule
                ? List.of(Map.of("id", "pro", "name", "Pro plan",
                        "clauses", List.of(Map.of("attr", "plan", "op", "in", "values", List.of("pro"))),
                        "serve", Map.of("variation", 1.0)))
                : List.of());
        return t;
    }

    private static void check(String label, boolean ok, String detail) {
        if (!ok) failures++;
        System.out.println((ok ? "PASS  " : "FAIL  ") + label + (detail.isEmpty() ? "" : "  (" + detail + ")"));
    }

    private static long waitFor(BooleanSupplier test, long timeoutMs) throws InterruptedException {
        long t0 = System.nanoTime();
        while (System.nanoTime() - t0 < TimeUnit.MILLISECONDS.toNanos(timeoutMs)) {
            if (test.getAsBoolean()) return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            Thread.sleep(5);
        }
        return -1;
    }

    private static ImmutableContext user(String key, String plan) {
        return plan == null ? new ImmutableContext(key) : new ImmutableContext(key, Map.of("plan", new Value(plan)));
    }

    public static void main(String[] args) throws Exception {
        String email = System.getenv("SY_EMAIL"), password = System.getenv("SY_PASSWORD");
        if (email == null || password == null) {
            System.err.println("Set SY_EMAIL and SY_PASSWORD");
            System.exit(2);
        }
        call("POST", "/v1/auth/login", Map.of("email", email, "password", password));
        String serverKey = (String) ((Map<?, ?>) call("POST", p("/environments/" + ENV + "/keys/server/rotate"), null)).get("key");
        System.out.println("signed in; " + ENV + " server key rotated (" + serverKey.substring(0, 12) + "…)");
        try {
            call("POST", p("/flags"), Map.of("key", FLAG, "name", "SDK live check", "type", "boolean", "description", "Created by the SDK live checks"));
            System.out.println("created flag " + FLAG);
        } catch (IOException e) {
            if (!e.getMessage().contains("409")) throw e;
            call("PATCH", p("/flags/" + FLAG), Map.of("archived", false));
            System.out.println("reusing flag " + FLAG);
        }
        setTargeting(proRule(Map.of("variation", 1.0), false, false));

        System.out.println("\nJava SDK (com.railflag:railflag-openfeature, local evaluation)");
        RailflagProvider provider = new RailflagProvider(RailflagOptions.builder().sdkKey(serverKey).baseUrl(RELAY).build());
        OpenFeatureAPI api = OpenFeatureAPI.getInstance();
        CountDownLatch changed = new CountDownLatch(1);
        api.onProviderConfigurationChanged(d -> changed.countDown());
        long t0 = System.nanoTime();
        api.setProviderAndWait(provider);
        Client client = api.getClient();
        check("provider ready", true, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) + " ms, config v"
                + Js.string(provider.source().snapshot().get("version")));
        check("SSE stream connected", waitFor(provider.source()::streaming, 5000) >= 0, "");

        FlagEvaluationDetails<Boolean> d = client.getBooleanDetails(FLAG, true, user("user-1", null));
        check("flag off -> false", !d.getValue(), "reason " + d.getReason());

        call("POST", p("/flags/" + FLAG + "/environments/" + ENV + "/toggle"), Map.of("enabled", true));
        long ms = waitFor(() -> client.getBooleanValue(FLAG, false, user("user-1", null)), 10_000);
        check("toggle on reaches the SDK over SSE", ms >= 0, ms + " ms after the api answered");
        check("PROVIDER_CONFIGURATION_CHANGED fired", changed.await(2, TimeUnit.SECONDS), "");

        setTargeting(proRule(Map.of("variation", 0.0), true, true));
        ms = waitFor(() -> !client.getBooleanValue(FLAG, true, user("user-1", "free")), 10_000);
        check("rule change arrives", ms >= 0, ms + " ms");
        d = client.getBooleanDetails(FLAG, false, user("user-1", "pro"));
        check("plan=pro -> true via rule", d.getValue() && "TARGETING_MATCH".equals(d.getReason()),
                "reason " + d.getReason() + ", rule " + d.getFlagMetadata().getString("ruleId"));

        double version = ConfigSource.versionOf(provider.source().snapshot());
        setTargeting(proRule(Map.of("rollout", Map.of("weights", List.of(70_000.0, 30_000.0))), true, true));
        waitFor(() -> ConfigSource.versionOf(provider.source().snapshot()) > version, 10_000);
        int n = 10_000, on = 0;
        long t1 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            if (client.getBooleanValue(FLAG, false, user("user-" + i, "free"))) on++;
        }
        double perUs = (System.nanoTime() - t1) / 1000.0 / n;
        check("30% rollout over 10,000 users", Math.abs(on / (double) n - 0.3) < 0.02,
                String.format("%.1f%% true, %.1f µs per evaluation through OpenFeature", 100.0 * on / n, perUs));

        // The relay evaluates for browsers with the TypeScript evaluator; both must put every user in the same bucket.
        String clientKey = (String) env(call("GET", p("/environments"), null), ENV).get("clientKey");
        int mismatch = 0;
        for (int i = 0; i < 200; i++) {
            Map<String, Object> ctx = Map.of("targetingKey", "user-" + i, "plan", "free");
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(RELAY + "/sdk/v1/evaluate"))
                    .header("authorization", clientKey).header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(Map.of("context", ctx)))).build(), HttpResponse.BodyHandlers.ofString());
            Object remote = ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) Json.parse(res.body())).get("flags")).get(FLAG)).get("value");
            boolean local = client.getBooleanValue(FLAG, !Boolean.TRUE.equals(remote), user("user-" + i, "free"));
            if (!Boolean.valueOf(local).equals(remote)) mismatch++;
        }
        check("Java and the relay (TypeScript) agree for 200 users", mismatch == 0, mismatch + " differ");

        api.shutdown();
        call("POST", p("/flags/" + FLAG + "/environments/" + ENV + "/toggle"), Map.of("enabled", false));
        call("PATCH", p("/flags/" + FLAG), Map.of("archived", true));
        System.out.println("\n" + FLAG + " switched off and archived. " + (failures > 0 ? failures + " check(s) failed" : "All checks passed."));
        System.exit(failures > 0 ? 1 : 0);
    }
}
