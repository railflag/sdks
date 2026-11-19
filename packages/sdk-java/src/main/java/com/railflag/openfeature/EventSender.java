package com.railflag.openfeature;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends experiment data to the relay, like packages/sdk-node/src/events.ts: one exposure per user and
 * variation of a flag in a running experiment, and the track() events an experiment counts. User keys leave
 * the process here, only for those flags and events; Railflag hashes them before storing them. Sending never
 * delays a flag check, and a failed send is dropped rather than retried.
 */
final class EventSender {
    /** Items per request, per kind: the relay accepts up to 1,000 of each. */
    static final int BATCH = 1000;
    /** Send early once this many items wait, so a busy process doesn't hold big queues for the whole interval. */
    static final int SEND_AT = 500;
    /** Items kept per kind while the relay can't be reached; newer ones are dropped. */
    static final int MAX_QUEUE = 10_000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    /** How long shutdown waits for the last items to go out. */
    private static final long STOP_WAIT_MS = 2 * REQUEST_TIMEOUT.toMillis();

    private static final Logger log = LoggerFactory.getLogger("railflag");

    private final RailflagOptions options;
    private final HttpClient client;
    private final Object lock = new Object();
    private final List<Map<String, Object>> exposures = new ArrayList<>(); // guarded by lock
    private final List<Map<String, Object>> events = new ArrayList<>(); // guarded by lock
    private boolean warnedFull; // guarded by lock
    /** Held while sending, so batches go out one at a time and in order. */
    private final Object sendLock = new Object();
    private long lastWarn; // guarded by sendLock
    private final AtomicBoolean flushQueued = new AtomicBoolean();
    private volatile ScheduledExecutorService scheduler;

    EventSender(RailflagOptions options, HttpClient client) {
        this.options = options;
        this.client = client;
    }

    void exposure(Experiments.Exposure x) {
        push(exposures, x.toJson(System.currentTimeMillis()));
    }

    void event(Experiments.Event e) {
        push(events, e.toJson(System.currentTimeMillis()));
    }

    private void push(List<Map<String, Object>> q, Map<String, Object> item) {
        boolean warn = false, sendNow = false;
        synchronized (lock) {
            if (q.size() >= MAX_QUEUE) {
                warn = !warnedFull;
                warnedFull = true;
            } else {
                q.add(item);
                sendNow = exposures.size() + events.size() >= SEND_AT;
            }
        }
        if (warn) log.warn("Railflag experiment events: queue full, dropping new events until the relay can be reached");
        if (sendNow) sendSoon();
    }

    /** Flushes on the background thread, so the flag check or track() call that filled the queue doesn't wait. */
    private void sendSoon() {
        ScheduledExecutorService s = scheduler;
        if (s == null || !flushQueued.compareAndSet(false, true)) return;
        try {
            s.execute(() -> {
                flushQueued.set(false);
                flush();
            });
        } catch (RejectedExecutionException e) {
            flushQueued.set(false); // stopping
        }
    }

    /** Items waiting to be sent. */
    int queued() {
        synchronized (lock) {
            return exposures.size() + events.size();
        }
    }

    /** Sends every {@code eventsInterval} until {@link #stop}. */
    synchronized void start() {
        if (scheduler != null) return;
        flushQueued.set(false); // a stop that timed out may have dropped a queued flush
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(ConfigSource.daemon("railflag-events"));
        long ms = options.eventsInterval.toMillis();
        s.scheduleWithFixedDelay(this::flush, ms, ms, TimeUnit.MILLISECONDS);
        scheduler = s;
    }

    /** Stops the timer and sends what is queued, waiting a few seconds at most. */
    synchronized void stop() {
        ScheduledExecutorService s = scheduler;
        scheduler = null;
        if (s == null) {
            flush();
            return;
        }
        // The background thread sends the rest, after any send in progress.
        s.execute(this::flush);
        s.shutdown();
        try {
            if (!s.awaitTermination(STOP_WAIT_MS, TimeUnit.MILLISECONDS)) s.shutdownNow();
        } catch (InterruptedException e) {
            s.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Sends everything queued, in requests of at most {@link #BATCH} exposures and {@link #BATCH} events. Never throws. */
    void flush() {
        synchronized (sendLock) {
            while (true) {
                List<Map<String, Object>> xs, es;
                synchronized (lock) {
                    if (exposures.isEmpty() && events.isEmpty()) return;
                    xs = take(exposures);
                    es = take(events);
                    warnedFull = false;
                }
                if (!send(xs, es)) return;
            }
        }
    }

    private static List<Map<String, Object>> take(List<Map<String, Object>> q) {
        List<Map<String, Object>> head = q.subList(0, Math.min(BATCH, q.size()));
        List<Map<String, Object>> out = new ArrayList<>(head);
        head.clear();
        return out;
    }

    /** POSTs one batch; a failed batch is dropped. Returns false when the thread was interrupted. */
    private boolean send(List<Map<String, Object>> xs, List<Map<String, Object>> es) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sentAt", (double) System.currentTimeMillis());
            body.put("sdk", "java/" + RailflagProvider.VERSION);
            body.put("exposures", xs);
            body.put("events", es);
            HttpRequest req = HttpRequest.newBuilder(URI.create(options.baseUrl + "/sdk/v1/events"))
                    .header("Authorization", options.sdkKey)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "railflag-java/" + RailflagProvider.VERSION)
                    .timeout(REQUEST_TIMEOUT)
                    .POST(BodyPublishers.ofString(Json.stringify(body)))
                    .build();
            int status = client.send(req, BodyHandlers.discarding()).statusCode();
            // 404: a relay from before experiments, which has nowhere to put them.
            if ((status < 200 || status > 299) && status != 404) warn("Railflag experiment events not accepted: " + status);
        } catch (IOException | RuntimeException e) {
            warn("Railflag experiment events not sent: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }

    /** At most one warning a minute. */
    private void warn(String message) {
        long now = System.currentTimeMillis();
        if (now - lastWarn < 60_000) return;
        lastWarn = now;
        log.warn(message);
    }
}
