package com.railflag.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EventDetails;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.ProviderState;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/** The provider against a fake relay that speaks the same protocol as apps/relay. */
class ProviderTest {
    static final String KEY = "srv_test_123";

    // ---- the fake relay -------------------------------------------------------------------

    static Map<String, Object> flag(String key, List<Object> variations, Object... over) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("key", key);
        f.put("type", "boolean");
        f.put("variations", variations);
        f.put("salt", "s");
        f.put("version", 1.0);
        f.put("enabled", true);
        f.put("offVariation", 0.0);
        f.put("fallthrough", Map.of("variation", 1.0));
        f.put("targets", List.of());
        f.put("rules", List.of());
        f.put("prerequisites", List.of());
        for (int i = 0; i < over.length; i += 2) f.put((String) over[i], over[i + 1]);
        return f;
    }

    static List<Object> rule(String id, String attr, String op, Object value) {
        return List.of(Map.of("id", id, "clauses", List.of(Map.of("attr", attr, "op", op, "values", List.of(value))),
                "serve", Map.of("variation", 1.0)));
    }

    static Map<String, Object> snapshot(int version, boolean checkoutOn) {
        Map<String, Object> flags = new LinkedHashMap<>();
        for (Map<String, Object> f : Arrays.asList(
                flag("new-checkout", List.of(false, true), "enabled", checkoutOn, "rules", rule("pro", "plan", "in", "pro"),
                        "fallthrough", Map.of("variation", 0.0)),
                flag("limit", List.of(10.0, 25.0, 2.5), "type", "number"),
                flag("ratio", List.of(0.5), "type", "number", "fallthrough", Map.of("variation", 0.0)),
                flag("theme", List.of(Map.of("color", "teal")), "type", "json", "fallthrough", Map.of("variation", 0.0)),
                flag("banner", List.of("off", "summer"), "type", "string", "rules", rule("late", "signup", "after", "2026-01-01T00:00:00Z"),
                        "fallthrough", Map.of("variation", 0.0)))) {
            flags.put((String) f.get("key"), f);
        }
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("env", "test");
        snap.put("version", (double) version);
        snap.put("generatedAt", "2026-11-07T00:00:00Z");
        snap.put("flags", flags);
        snap.put("segments", Map.of());
        return snap;
    }

    static final class FakeRelay implements AutoCloseable {
        private static final String CLOSE = "\u0000close";
        final HttpServer server;
        final String url;
        final ExecutorService executor = Executors.newCachedThreadPool();
        volatile Map<String, Object> snap = snapshot(1, false);
        volatile boolean streamOk = true;
        volatile boolean down;
        final List<Integer> configCalls = new CopyOnWriteArrayList<>();
        final List<String> userAgents = new CopyOnWriteArrayList<>();
        final AtomicInteger streamConnections = new AtomicInteger();
        final Set<BlockingQueue<String>> streams = ConcurrentHashMap.newKeySet();

        FakeRelay() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
            url = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void handle(HttpExchange ex) throws IOException {
            try {
                if (down) {
                    send(ex, 503, "down");
                    return;
                }
                if (!KEY.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                    send(ex, 401, "{\"error\":\"unauthorized\"}");
                    return;
                }
                userAgents.add(ex.getRequestHeaders().getFirst("User-Agent"));
                Map<String, Object> current = snap;
                String version = Js.string(current.get("version"));
                switch (ex.getRequestURI().getPath()) {
                    case "/sdk/v1/config": {
                        String etag = "\"v" + version + "\"";
                        if (etag.equals(ex.getRequestHeaders().getFirst("If-None-Match"))) {
                            configCalls.add(304);
                            ex.sendResponseHeaders(304, -1);
                            return;
                        }
                        configCalls.add(200);
                        ex.getResponseHeaders().set("ETag", etag);
                        ex.getResponseHeaders().set("Content-Type", "application/json");
                        send(ex, 200, Json.stringify(current));
                        return;
                    }
                    case "/sdk/v1/stream":
                        if (!streamOk) {
                            send(ex, 500, "{}");
                            return;
                        }
                        stream(ex, version);
                        return;
                    default:
                        send(ex, 404, "not found");
                }
            } finally {
                ex.close();
            }
        }

        private void stream(HttpExchange ex, String version) throws IOException {
            BlockingQueue<String> q = new LinkedBlockingQueue<>();
            streams.add(q);
            streamConnections.incrementAndGet();
            try {
                ex.getResponseHeaders().set("Content-Type", "text/event-stream");
                ex.sendResponseHeaders(200, 0);
                OutputStream out = ex.getResponseBody();
                out.write(configEvent(version).getBytes(StandardCharsets.UTF_8));
                out.flush();
                while (true) {
                    String msg = q.poll(50, TimeUnit.MILLISECONDS);
                    if (msg == null) continue;
                    if (msg.equals(CLOSE)) return;
                    out.write(msg.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (IOException | InterruptedException e) {
                // The client went away.
            } finally {
                streams.remove(q);
            }
        }

        private static String configEvent(String version) {
            return "id: " + version + "\nevent: config\ndata: {\"version\":" + version + "}\n\n";
        }

        private static void send(HttpExchange ex, int status, String body) throws IOException {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
        }

        void publish(Map<String, Object> s, boolean notify) {
            snap = s;
            if (notify) sendToStreams(configEvent(Js.string(s.get("version"))));
        }

        void sendToStreams(String raw) {
            for (BlockingQueue<String> q : streams) q.add(raw);
        }

        void closeStreams() {
            sendToStreams(CLOSE);
        }

        int count(int status) {
            return Collections.frequency(configCalls, status);
        }

        @Override
        public void close() {
            closeStreams();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private FakeRelay relay;
    private String domain;
    private final OpenFeatureAPI api = OpenFeatureAPI.getInstance();

    @BeforeEach
    void start(TestInfo info) throws IOException {
        relay = new FakeRelay();
        domain = info.getDisplayName(); // a client per test, so tests don't share a provider
    }

    @AfterEach
    void stop() {
        api.getProvider(domain).shutdown();
        relay.close();
    }

    private RailflagOptions.Builder options() {
        return RailflagOptions.builder().sdkKey(KEY).baseUrl(relay.url);
    }

    private RailflagProvider provider(RailflagOptions.Builder o) {
        RailflagProvider p = new RailflagProvider(o.build());
        api.setProviderAndWait(domain, p);
        return p;
    }

    private Client client() {
        return api.getClient(domain);
    }

    static void waitFor(String what, BooleanSupplier test) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            if (test.getAsBoolean()) return;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
        fail("timed out waiting for " + what);
    }

    private static ImmutableContext ctx(String key, String attr, Value value) {
        return new ImmutableContext(key, Map.of(attr, value));
    }

    // ---- tests ------------------------------------------------------------------------------

    @Test
    void evaluatesLocallyAndStreamsUpdates() {
        List<EventDetails> changes = new CopyOnWriteArrayList<>();
        client().onProviderConfigurationChanged(changes::add);
        RailflagProvider p = provider(options());
        Client c = client();
        assertEquals(ProviderState.READY, c.getProviderState());
        waitFor("the stream", p.source()::streaming);
        assertEquals("railflag-java/" + RailflagProvider.VERSION, relay.userAgents.get(0));

        ImmutableContext pro = ctx("user-1", "plan", new Value("pro"));
        FlagEvaluationDetails<Boolean> d = c.getBooleanDetails("new-checkout", true, pro);
        assertFalse(d.getValue());
        assertEquals(Reason.DISABLED.toString(), d.getReason());

        relay.publish(snapshot(2, true), true);
        waitFor("the update", () -> c.getBooleanValue("new-checkout", false, pro));
        d = c.getBooleanDetails("new-checkout", false, pro);
        assertEquals(Reason.TARGETING_MATCH.toString(), d.getReason());
        assertEquals("1", d.getVariant());
        assertEquals("pro", d.getFlagMetadata().getString("ruleId"));
        assertEquals(2, d.getFlagMetadata().getInteger("configVersion"));
        assertEquals(1, d.getFlagMetadata().getInteger("flagVersion"));
        assertFalse(c.getBooleanValue("new-checkout", true, ctx("user-1", "plan", new Value("free"))));

        waitFor("the change event", () -> !changes.isEmpty());
        assertEquals(List.of("new-checkout"), changes.get(0).getFlagsChanged());
        assertEquals(1, relay.streamConnections.get());
    }

    @Test
    void types() {
        provider(options().stream(false));
        Client c = client();
        ImmutableContext none = new ImmutableContext();
        assertEquals(25, c.getIntegerValue("limit", 0, none));
        assertEquals(25L, c.getLongValue("limit", 0L, none));
        assertEquals(0.5, c.getDoubleValue("ratio", 0.0, none));

        FlagEvaluationDetails<Integer> ratioAsInt = c.getIntegerDetails("ratio", 7, none);
        assertEquals(7, ratioAsInt.getValue());
        assertEquals(ErrorCode.TYPE_MISMATCH, ratioAsInt.getErrorCode());

        Value theme = c.getObjectValue("theme", new Value(), none);
        assertEquals("teal", theme.asStructure().getValue("color").asString());

        FlagEvaluationDetails<Boolean> limitAsBool = c.getBooleanDetails("limit", true, none);
        assertTrue(limitAsBool.getValue());
        assertEquals(ErrorCode.TYPE_MISMATCH, limitAsBool.getErrorCode());

        FlagEvaluationDetails<String> missing = c.getStringDetails("nope", "dflt", none);
        assertEquals("dflt", missing.getValue());
        assertEquals(ErrorCode.FLAG_NOT_FOUND, missing.getErrorCode());

        FlagEvaluationDetails<Value> boolAsObject = c.getObjectDetails("new-checkout", new Value("dflt"), none);
        assertEquals(ErrorCode.TYPE_MISMATCH, boolAsObject.getErrorCode());

        Instant late = Instant.parse("2026-03-01T00:00:00Z");
        assertEquals("summer", c.getStringValue("banner", "x", ctx("u", "signup", new Value(late))));
        assertEquals("off", c.getStringValue("banner", "x", ctx("u", "signup", new Value("2025-12-31T00:00:00Z"))));
    }

    @Test
    void usesTheETagAndFallsBackToPolling() {
        relay.streamOk = false;
        RailflagProvider p = provider(options().pollInterval(Duration.ofMillis(100)));
        Client c = client();
        waitFor("a 304", () -> relay.count(304) > 0);
        relay.publish(snapshot(5, true), false);
        ImmutableContext pro = ctx("u", "plan", new Value("pro"));
        waitFor("the polled update", () -> c.getBooleanValue("new-checkout", false, pro));
        assertEquals(5.0, ConfigSource.versionOf(p.source().snapshot()));
        assertFalse(p.source().streaming());
    }

    @Test
    void reconnectsWhenTheRelayAsks() {
        RailflagProvider p = provider(options());
        waitFor("the stream", p.source()::streaming);
        relay.sendToStreams("event: reconnect\ndata: {}\n\n");
        waitFor("a second stream", () -> relay.streamConnections.get() == 2);
        // An update after reconnecting still arrives over the new stream.
        relay.publish(snapshot(3, true), true);
        waitFor("the update", () -> client().getBooleanValue("new-checkout", false, ctx("u", "plan", new Value("pro"))));
    }

    @Test
    void reconnectsAfterASilentStream() {
        RailflagProvider p = provider(options().streamReadTimeout(Duration.ofMillis(300)).pollInterval(Duration.ofHours(1)));
        waitFor("the stream", p.source()::streaming);
        // The fake relay sends no heartbeats, so the watchdog drops the stream and it reconnects.
        waitFor("a reconnect", () -> relay.streamConnections.get() >= 2);
    }

    @Test
    void keepsServingWhenTheRelayGoesAway() throws InterruptedException {
        provider(options().pollInterval(Duration.ofMillis(100)));
        relay.down = true;
        relay.closeStreams();
        Thread.sleep(400);
        assertEquals(25, client().getIntegerValue("limit", 0, new ImmutableContext()));
    }

    @Test
    void aBadKeyPutsTheProviderInErrorAndReturnsDefaults() {
        Exception e = assertThrows(Exception.class,
                () -> api.setProviderAndWait(domain, new RailflagProvider(options().sdkKey("srv_wrong").build())));
        StringWriter trace = new StringWriter();
        e.printStackTrace(new PrintWriter(trace));
        assertTrue(trace.toString().contains("401"), trace.toString());
        Client c = client();
        assertEquals(ProviderState.ERROR, c.getProviderState());
        FlagEvaluationDetails<Boolean> d = c.getBooleanDetails("new-checkout", true, new ImmutableContext());
        assertTrue(d.getValue());
        assertNotNull(d.getErrorCode());
    }

    @Test
    void optionsAreChecked() {
        for (String bad : new String[] {null, "", "relay.example.com"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> RailflagOptions.builder().sdkKey(KEY).baseUrl(bad).build());
            assertTrue(e.getMessage().contains("baseUrl must be your Railflag relay URL"), e.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> RailflagOptions.builder().baseUrl("https://relay.example.com").build());
        assertEquals("https://relay.example.com", RailflagOptions.builder().sdkKey(KEY).baseUrl("https://relay.example.com//").build().baseUrl);
    }

    @Test
    void contextValuesBecomeJavaScriptValues() {
        MutableContext ctx = new MutableContext("user-1");
        ctx.add("i", 3);
        ctx.add("d", 1.5);
        ctx.add("b", true);
        ctx.add("when", Instant.ofEpochMilli(1_700_000_000_123L));
        ctx.add("tags", List.of(new Value("a"), new Value(2)));
        ctx.add("org", new ImmutableStructure(Map.of("tier", new Value("gold"))));
        ctx.add("longs", List.of(new Value(4L)));
        Map<String, Object> got = RailflagProvider.toContext(ctx);
        assertEquals("user-1", got.get("targetingKey"));
        assertEquals(3.0, got.get("i"));
        assertEquals(List.of(4.0), got.get("longs"));
        assertEquals(1.5, got.get("d"));
        assertEquals(true, got.get("b"));
        assertEquals(1_700_000_000_123.0, got.get("when"));
        assertEquals(new ArrayList<>(List.of("a", 2.0)), got.get("tags"));
        assertEquals(Map.of("tier", "gold"), got.get("org"));
    }
}
