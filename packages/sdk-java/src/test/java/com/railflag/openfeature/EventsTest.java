package com.railflag.openfeature;

import static com.railflag.openfeature.ProviderTest.KEY;
import static com.railflag.openfeature.ProviderTest.waitFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.railflag.openfeature.ProviderTest.FakeRelay;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.MutableTrackingEventDetails;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Value;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Experiment exposures and track() events. The vectors are generated from packages/eval by
 * packages/eval/vectors/build.ts, and every SDK must send exactly what they expect.
 */
class EventsTest {
    private static final Path VECTORS = Path.of("../eval/vectors/events-vectors.json");

    @SuppressWarnings("unchecked")
    static Map<String, Object> vectors() throws IOException {
        assumeTrue(Files.exists(VECTORS), "events vectors not found (they live in the Railflag monorepo)");
        return (Map<String, Object>) Json.parse(new String(Files.readAllBytes(VECTORS), StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object v) {
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Object v) {
        return (List<Map<String, Object>>) v;
    }

    /** A vector context as an OpenFeature context: {@code targetingKey} is the targeting key, the rest are attributes. */
    static EvaluationContext context(Map<String, Object> json) {
        Map<String, Value> attrs = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : json.entrySet()) {
            if (!e.getKey().equals("targetingKey")) attrs.put(e.getKey(), Value.objectToValue(e.getValue()));
        }
        return new ImmutableContext((String) json.get("targetingKey"), attrs);
    }

    /** A record as the vectors write it: without {@code ts}. */
    private static Map<String, Object> withoutTs(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>(item);
        out.remove("ts");
        return out;
    }

    /** Every exposure or event the relay received, in order, without timestamps. */
    private static List<Object> sent(FakeRelay relay, String kind) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> post : relay.eventPosts) {
            for (Map<String, Object> item : items(post.get(kind))) {
                assertTrue(item.get("ts") instanceof Double && (Double) item.get("ts") > 1.7e12, "ts in " + Json.stringify(item));
                out.add(withoutTs(item));
            }
        }
        return out;
    }

    private static int count(FakeRelay relay, String kind) {
        int n = 0;
        for (Map<String, Object> post : relay.eventPosts) n += items(post.get(kind)).size();
        return n;
    }

    // ---- providers against the fake relay ----------------------------------------------------

    private final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    private final List<RailflagProvider> providers = new ArrayList<>();
    private FakeRelay relay;
    private String domain;

    @BeforeEach
    void start(TestInfo info) throws IOException {
        relay = new FakeRelay();
        domain = "events " + info.getDisplayName();
    }

    @AfterEach
    void stop() {
        for (RailflagProvider p : providers) p.shutdown();
        relay.close();
    }

    /** The tests flush themselves, so the interval is long. */
    private RailflagOptions.Builder options() {
        return RailflagOptions.builder().sdkKey(KEY).baseUrl(relay.url).stream(false).eventsInterval(Duration.ofHours(1));
    }

    /** A provider on its own OpenFeature domain, serving the relay's snapshot. */
    private Client client(String name, RailflagOptions.Builder o) {
        RailflagProvider p = new RailflagProvider(o.build());
        providers.add(p);
        api.setProviderAndWait(name, p);
        return api.getClient(name);
    }

    private RailflagProvider provider() {
        return providers.get(providers.size() - 1);
    }

    private static void evaluate(Client c, Map<String, Object> snap, String flag, EvaluationContext ctx) {
        Map<?, ?> f = (Map<?, ?>) ((Map<?, ?>) snap.get("flags")).get(flag);
        if (f != null && "string".equals(f.get("type"))) c.getStringValue(flag, "", ctx);
        else c.getBooleanValue(flag, false, ctx);
    }

    private static void track(Client c, String event, EvaluationContext ctx, Map<String, Object> details) {
        if (details == null) c.track(event, ctx);
        else if (details.get("value") == null) c.track(event, ctx, new MutableTrackingEventDetails());
        else c.track(event, ctx, new MutableTrackingEventDetails((Double) details.get("value")));
    }

    // ---- the shared vectors --------------------------------------------------------------------

    @Test
    void exposureVectors() throws IOException {
        Map<String, Object> v = vectors();
        Map<String, Object> snap = obj(v.get("snapshot"));
        int n = 0, exposed = 0;
        for (Map<String, Object> c : items(v.get("exposures"))) {
            String flag = (String) c.get("flag");
            Map<String, Object> ctx = obj(c.get("context"));
            EvalResult r = Evaluator.evaluate(snap, flag, ctx, null);
            String what = flag + " with " + Json.stringify(ctx);
            Map<String, Object> want = obj(c.get("result"));
            assertEquals(want.get("reason"), r.reason, what);
            Double index = r.variationIndex == null ? null : (double) r.variationIndex;
            assertEquals(want.get("variationIndex"), index, what);
            Experiments.Exposure x = Experiments.exposureFor(Experiments.of(snap), flag, ctx, r);
            assertEquals(c.get("expect"), x == null ? null : withoutTs(x.toJson(0)), what);
            n++;
            if (x != null) exposed++;
        }
        assertTrue(n >= 300 && exposed >= 100, n + " vectors, " + exposed + " exposures");
    }

    @Test
    void trackVectors() throws IOException {
        Map<String, Object> v = vectors();
        Map<?, ?> experiments = Experiments.of(obj(v.get("snapshot")));
        int n = 0;
        for (Map<String, Object> c : items(v.get("tracks"))) {
            Map<String, Object> details = obj(c.get("details"));
            Number value = details == null ? null : (Number) details.get("value");
            Experiments.Event e = Experiments.trackFor(experiments, (String) c.get("event"), obj(c.get("context")), value);
            assertEquals(c.get("expect"), e == null ? null : withoutTs(e.toJson(0)), () -> Json.stringify(c));
            n++;
        }
        assertTrue(n >= 30, "only " + n + " track vectors");
    }

    @Test
    void sequencesThroughOpenFeature() throws IOException {
        Map<String, Object> v = vectors();
        Map<String, Object> snap = obj(v.get("snapshot"));
        relay.snap = snap;
        int i = 0;
        for (Map<String, Object> seq : items(v.get("sequences"))) {
            relay.eventPosts.clear();
            Client c = client(domain + " #" + i++, options()); // a new provider: nothing sent yet
            for (Map<String, Object> step : items(seq.get("steps"))) {
                EvaluationContext ctx = context(obj(step.get("context")));
                if (step.containsKey("evaluate")) evaluate(c, snap, (String) step.get("evaluate"), ctx);
                else track(c, (String) step.get("track"), ctx, obj(step.get("details")));
            }
            provider().tracking().flush();
            Map<String, Object> expect = obj(seq.get("expect"));
            assertEquals(expect.get("exposures"), sent(relay, "exposures"), (String) seq.get("name"));
            assertEquals(expect.get("events"), sent(relay, "events"), (String) seq.get("name"));
        }
        assertTrue(i >= 4, "only " + i + " sequences");
    }

    /** Every exposure and track vector through one provider: the same records, each exposure once. */
    @Test
    void allVectorsThroughOpenFeature() throws IOException {
        Map<String, Object> v = vectors();
        Map<String, Object> snap = obj(v.get("snapshot"));
        relay.snap = snap;
        Client c = client(domain, options());
        List<Object> exposures = new ArrayList<>(), events = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        for (Map<String, Object> x : items(v.get("exposures"))) {
            evaluate(c, snap, (String) x.get("flag"), context(obj(x.get("context"))));
            if (x.get("expect") != null && seen.add(x.get("expect"))) exposures.add(x.get("expect"));
        }
        for (Map<String, Object> t : items(v.get("tracks"))) {
            track(c, (String) t.get("event"), context(obj(t.get("context"))), obj(t.get("details")));
            if (t.get("expect") != null) events.add(t.get("expect"));
        }
        provider().tracking().flush();
        assertEquals(exposures, sent(relay, "exposures"));
        assertEquals(events, sent(relay, "events"));
    }

    // ---- the provider ---------------------------------------------------------------------------

    @Test
    void nothingIsSentWithoutARunningExperiment() throws Exception {
        Map<String, Object> withExperiments = obj(vectors().get("snapshot"));
        Map<String, Object> without = new LinkedHashMap<>(withExperiments);
        without.remove("experiments");
        relay.snap = without;
        Client c = client(domain, options());
        ImmutableContext user = new ImmutableContext("user-1");
        assertEquals("SPLIT", c.getBooleanDetails("exp-split", false, user).getReason());
        c.track("purchase", user, new MutableTrackingEventDetails(5));
        provider().tracking().flush();
        assertEquals(0, relay.eventPosts.size());

        // An experiment starts: the next config has it, and the next checks are sent.
        Map<String, Object> started = new LinkedHashMap<>(withExperiments);
        started.put("version", 13.0);
        relay.publish(started, false);
        assertTrue(provider().source().fetch());
        c.getBooleanValue("exp-split", false, user);
        c.track("purchase", user, new MutableTrackingEventDetails(5));
        provider().tracking().flush();
        assertEquals(List.of(Map.of("flag", "exp-split", "variation", 0.0, "key", "user-1")), sent(relay, "exposures"));
        assertEquals(List.of(Map.of("event", "purchase", "key", "user-1", "value", 5.0)), sent(relay, "events"));
    }

    @Test
    void shutdownSendsWhatIsQueued() throws IOException {
        relay.snap = obj(vectors().get("snapshot"));
        Client c = client(domain, options());
        ImmutableContext user = new ImmutableContext("user-2");
        c.getBooleanValue("exp-split", false, user);
        c.track("signup", user, new MutableTrackingEventDetails(Double.NaN));
        assertEquals(0, relay.eventPosts.size());
        provider().shutdown();
        assertEquals(List.of(Map.of("flag", "exp-split", "variation", 1.0, "key", "user-2")), sent(relay, "exposures"));
        assertEquals(List.of(Map.of("event", "signup", "key", "user-2", "value", 0.0)), sent(relay, "events"));
    }

    @Test
    void sendEventsFalseSendsNothing() throws IOException {
        relay.snap = obj(vectors().get("snapshot"));
        Client c = client(domain, options().sendEvents(false));
        assertNull(provider().tracking());
        ImmutableContext user = new ImmutableContext("user-2");
        c.getBooleanValue("exp-split", false, user);
        c.track("purchase", user, new MutableTrackingEventDetails(1));
        provider().shutdown();
        assertEquals(0, relay.eventPosts.size());
    }

    @Test
    void sendsEarlyOnceFiveHundredWait() throws IOException {
        relay.snap = obj(vectors().get("snapshot"));
        Client c = client(domain, options());
        for (int i = 0; i < EventSender.SEND_AT; i++) c.getBooleanValue("exp-split", false, new ImmutableContext("u-" + i));
        waitFor("an early send", () -> count(relay, "exposures") == EventSender.SEND_AT);
    }

    // ---- the sender -----------------------------------------------------------------------------

    private EventSender sender(RailflagOptions.Builder o) {
        return new EventSender(o.build(), HttpClient.newHttpClient());
    }

    @Test
    void sendsOnTheInterval() {
        EventSender s = sender(options().eventsInterval(Duration.ofMillis(50)));
        s.start();
        try {
            s.exposure(new Experiments.Exposure("f", 1, "u"));
            waitFor("a send", () -> relay.eventPosts.size() == 1);
        } finally {
            s.stop();
        }
    }

    @Test
    void sendsBatchesOfAThousandWithTheKeyAndSdk() {
        EventSender s = sender(options());
        for (int i = 0; i < 2500; i++) s.exposure(new Experiments.Exposure("f", i % 2, "u-" + i));
        for (int i = 0; i < 1200; i++) s.event(new Experiments.Event("purchase", "u-" + i, i));
        long before = System.currentTimeMillis();
        s.flush();
        long after = System.currentTimeMillis();
        assertEquals(0, s.queued());
        // The relay answers 401 without recording when the Authorization header isn't the SDK key.
        assertEquals(3, relay.eventPosts.size());
        int[][] sizes = {{1000, 1000}, {1000, 200}, {500, 0}};
        for (int i = 0; i < 3; i++) {
            Map<String, Object> post = relay.eventPosts.get(i);
            assertEquals(sizes[i][0], items(post.get("exposures")).size());
            assertEquals(sizes[i][1], items(post.get("events")).size());
            assertEquals("java/" + RailflagProvider.VERSION, post.get("sdk"));
            double sentAt = (Double) post.get("sentAt");
            assertTrue(sentAt >= before && sentAt <= after, "sentAt " + sentAt);
            assertEquals("application/json", relay.eventContentTypes.get(i));
        }
        assertEquals(Map.of("flag", "f", "variation", 1.0, "key", "u-2499"), withoutTs(items(relay.eventPosts.get(2).get("exposures")).get(499)));
        assertEquals(Map.of("event", "purchase", "key", "u-1199", "value", 1199.0), withoutTs(items(relay.eventPosts.get(1).get("events")).get(199)));
    }

    @Test
    void queuesAtMostTenThousandOfEachKind() {
        EventSender s = sender(options());
        for (int i = 0; i < EventSender.MAX_QUEUE + 50; i++) s.exposure(new Experiments.Exposure("f", 0, "u-" + i));
        s.event(new Experiments.Event("purchase", "u", 1));
        assertEquals(EventSender.MAX_QUEUE + 1, s.queued());
        s.flush();
        assertEquals(EventSender.MAX_QUEUE, count(relay, "exposures"));
        assertEquals(1, count(relay, "events"));
        assertEquals(10, relay.eventPosts.size());
    }

    @Test
    void aFailedSendIsDroppedWithoutThrowing() throws IOException {
        EventSender s = sender(options());
        for (int status : new int[] {429, 500, 404}) {
            relay.eventsStatus = status;
            s.exposure(new Experiments.Exposure("f", 0, "u-" + status));
            s.flush();
            assertEquals(0, s.queued(), "after " + status);
        }
        relay.eventsStatus = 202;
        s.exposure(new Experiments.Exposure("f", 0, "u-ok"));
        s.flush();
        // The relay saw every batch, and the failed ones weren't sent again.
        assertEquals(4, relay.eventPosts.size());
        assertEquals(List.of(Map.of("flag", "f", "variation", 0.0, "key", "u-ok")), List.of(withoutTs(items(relay.eventPosts.get(3).get("exposures")).get(0))));
        assertEquals(1, items(relay.eventPosts.get(3).get("exposures")).size());

        // Nothing listening.
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        EventSender offline = sender(RailflagOptions.builder().sdkKey(KEY).baseUrl("http://127.0.0.1:" + port));
        offline.event(new Experiments.Event("purchase", "u", 1));
        offline.flush();
        assertEquals(0, offline.queued());
        offline.stop();
    }

    // ---- the rules --------------------------------------------------------------------------------

    @Test
    void trackValues() {
        Map<?, ?> experiments = Map.of("flags", List.of(), "events", List.of("purchase"));
        Map<String, Object> user = Map.of("targetingKey", "u");
        Object[][] cases = {{3, 3.0}, {7L, 7.0}, {2.5f, 2.5}, {Double.NaN, 0.0}, {Double.POSITIVE_INFINITY, 0.0}, {null, 0.0}};
        for (Object[] c : cases) {
            assertEquals(c[1], Experiments.trackFor(experiments, "purchase", user, (Number) c[0]).value, "value " + c[0]);
        }
        assertNull(Experiments.trackFor(experiments, "pageview", user, 1));
        assertNull(Experiments.trackFor(null, "purchase", user, 1));
        assertNull(Experiments.trackFor(Map.of("flags", "oops"), "purchase", user, 1));
    }

    @Test
    void dedupeSendsEachVariationOnceAndForgetsTheOldest() {
        Experiments.Dedupe d = new Experiments.Dedupe(3);
        Experiments.Exposure a = new Experiments.Exposure("f", 0, "a");
        Experiments.Exposure b = new Experiments.Exposure("f", 0, "b");
        Experiments.Exposure c = new Experiments.Exposure("f", 0, "c");
        assertTrue(d.first(a));
        assertTrue(d.first(b));
        assertTrue(d.first(c));
        assertFalse(d.first(new Experiments.Exposure("f", 0, "a")));
        assertTrue(d.first(new Experiments.Exposure("f", 1, "a")), "a new variation is sent"); // evicts a/0
        assertTrue(d.first(a), "the oldest was forgotten"); // evicts b
        assertFalse(d.first(c));
        assertTrue(d.first(b));
        assertTrue(d.first(new Experiments.Exposure("g", 0, "a")), "another flag");
    }

    @Test
    void dedupeIsThreadSafe() throws Exception {
        Experiments.Dedupe d = new Experiments.Dedupe(50_000);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                results.add(pool.submit(() -> {
                    int firsts = 0;
                    for (int i = 0; i < 5000; i++) if (d.first(new Experiments.Exposure("f", i % 3, "u-" + i))) firsts++;
                    return firsts;
                }));
            }
            int total = 0;
            for (Future<Integer> f : results) total += f.get(10, TimeUnit.SECONDS);
            assertEquals(5000, total);
        } finally {
            pool.shutdownNow();
        }
    }
}
