package com.railflag.openfeature;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.BodySubscribers;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the latest environment snapshot in memory, like packages/sdk-node/src/source.ts.
 *
 * <p>Flag checks never wait on the network. Updates arrive over Server-Sent Events; polling is the
 * safety net, and if Railflag can't be reached the last config keeps serving.
 */
final class ConfigSource {
    interface ChangeListener {
        /** Runs on a background thread after each update; {@code prev} is null on the first load. */
        void changed(Map<String, Object> snap, Map<String, Object> prev);
    }

    private static final Logger log = LoggerFactory.getLogger("railflag");

    private final RailflagOptions options;
    private final HttpClient client;
    private final List<ChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final Object fetchLock = new Object();

    private volatile Map<String, Object> snapshot;
    private volatile boolean streaming;
    private volatile boolean running;
    private volatile Stream current;
    private String etag; // guarded by fetchLock
    private ScheduledExecutorService scheduler;
    private Thread streamThread;

    ConfigSource(RailflagOptions options) {
        this.options = options;
        this.client = options.httpClient != null
                ? options.httpClient
                : HttpClient.newBuilder().connectTimeout(options.timeout).build();
    }

    /** The current config, or null before the first load. */
    Map<String, Object> snapshot() {
        return snapshot;
    }

    /** Whether the Server-Sent Events stream is connected. */
    boolean streaming() {
        return streaming;
    }

    void onChange(ChangeListener l) {
        listeners.add(l);
    }

    /** Loads the config once (throwing if that fails), then keeps it fresh in the background. */
    synchronized void start() throws IOException, InterruptedException {
        fetch();
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(daemon("railflag-poll"));
        long ms = options.pollInterval.toMillis();
        scheduler.scheduleWithFixedDelay(() -> fetchQuietly("poll"), ms, ms, TimeUnit.MILLISECONDS);
        if (options.stream) {
            streamThread = daemon("railflag-stream").newThread(this::streamLoop);
            streamThread.start();
        }
    }

    /** Stops the background threads and closes the stream. */
    synchronized void stop() {
        running = false;
        Stream s = current;
        if (s != null) s.cancel("stopped");
        if (streamThread != null) {
            streamThread.interrupt();
            try {
                streamThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (scheduler != null) scheduler.shutdownNow();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(options.baseUrl + path))
                .header("Authorization", options.sdkKey)
                .header("User-Agent", "railflag-java/" + RailflagProvider.VERSION)
                .timeout(options.timeout);
    }

    /** Downloads the snapshot if it changed. Returns whether a newer one was applied. */
    @SuppressWarnings("unchecked")
    boolean fetch() throws IOException, InterruptedException {
        Map<String, Object> snap;
        Map<String, Object> prev;
        synchronized (fetchLock) {
            HttpRequest.Builder req = request("/sdk/v1/config");
            if (etag != null) req.header("If-None-Match", etag);
            HttpResponse<String> resp;
            try {
                resp = client.send(req.build(), BodyHandlers.ofString());
            } catch (IOException e) {
                throw new IOException("Railflag config request failed: " + e, e);
            }
            if (resp.statusCode() == 304) return false;
            if (resp.statusCode() != 200) {
                String body = resp.body() == null ? "" : resp.body();
                throw new IOException("Railflag config request failed: " + resp.statusCode() + " "
                        + body.substring(0, Math.min(200, body.length())));
            }
            Object parsed;
            try {
                parsed = Json.parse(resp.body());
            } catch (IllegalArgumentException e) {
                throw new IOException("Railflag config is not valid JSON: " + e.getMessage(), e);
            }
            if (!(parsed instanceof Map)) throw new IOException("Railflag config is not a JSON object");
            snap = (Map<String, Object>) parsed;
            prev = snapshot;
            if (prev != null && versionOf(snap) < versionOf(prev)) return false;
            snapshot = snap;
            etag = resp.headers().firstValue("ETag").orElse(null);
        }
        for (ChangeListener l : listeners) {
            try {
                l.changed(snap, prev);
            } catch (RuntimeException e) {
                log.error("Railflag change listener failed", e);
            }
        }
        return true;
    }

    private void fetchQuietly(String what) {
        try {
            fetch();
        } catch (IOException | RuntimeException e) {
            if (running) log.warn("Railflag {} failed: {}", what, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static double versionOf(Map<String, Object> snap) {
        Object v = snap.get("version");
        return v instanceof Double ? (Double) v : 0;
    }

    private void streamLoop() {
        long backoff = 500;
        while (running) {
            Stream s = new Stream();
            current = s;
            String problem = null;
            try {
                problem = s.run();
            } catch (InterruptedException e) {
                s.cancel("stopped");
                return;
            } catch (IOException | RuntimeException e) {
                problem = e.toString();
            } finally {
                streaming = false;
                current = null;
            }
            if (!running) return;
            if (s.connected) backoff = 500;
            if (problem != null) log.warn("Railflag stream dropped, reconnecting: {}", problem);
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                return;
            }
            backoff = Math.min(backoff * 2, 30_000);
        }
    }

    private void onConfigEvent(String data) {
        Object parsed;
        try {
            parsed = Json.parse(data);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (!(parsed instanceof Map) || !(((Map<?, ?>) parsed).get("version") instanceof Double)) return;
        double v = (Double) ((Map<?, ?>) parsed).get("version");
        Map<String, Object> cur = snapshot;
        if (cur != null && v <= versionOf(cur)) return;
        try {
            scheduler.execute(() -> fetchQuietly("fetch after a stream event"));
        } catch (RejectedExecutionException e) {
            // Stopping.
        }
    }

    /** One Server-Sent Events connection to /sdk/v1/stream. */
    private final class Stream implements Flow.Subscriber<String> {
        /** Completes with null when the stream ends as it should (stop, "reconnect"), or with what went wrong. */
        private final CompletableFuture<String> done = new CompletableFuture<>();
        private volatile Flow.Subscription subscription;
        private volatile CompletableFuture<HttpResponse<Void>> response;
        private volatile int status;
        private volatile long lastLine = System.nanoTime();
        volatile boolean connected;
        private String event = "";
        private StringBuilder data;

        /** Reads until the connection ends. Returns null, or why it ended early. */
        String run() throws IOException, InterruptedException {
            HttpRequest req = request("/sdk/v1/stream").header("Accept", "text/event-stream").build();
            response = client.sendAsync(req, info -> {
                status = info.statusCode();
                if (status != 200) return BodySubscribers.<Void>replacing(null);
                connected = true;
                streaming = true;
                lastLine = System.nanoTime();
                return BodySubscribers.fromLineSubscriber(this);
            });
            long period = Math.max(10, options.streamReadTimeout.toMillis() / 4);
            long limit = options.streamReadTimeout.toNanos();
            ScheduledFuture<?> watchdog = scheduler.scheduleWithFixedDelay(() -> {
                if (connected && System.nanoTime() - lastLine > limit) cancel("no data for " + options.streamReadTimeout.toSeconds() + " s");
            }, period, period, TimeUnit.MILLISECONDS);
            try {
                CompletableFuture.anyOf(response, done).get();
            } catch (ExecutionException e) {
                if (!done.isDone()) throw new IOException("stream request failed: " + e.getCause(), e.getCause());
            } finally {
                watchdog.cancel(false);
            }
            if (status != 0 && status != 200) return "stream request failed: " + status;
            if (done.isDone()) {
                try {
                    return done.get();
                } catch (ExecutionException e) {
                    return String.valueOf(e.getCause());
                }
            }
            return "server closed the stream";
        }

        void cancel(String why) {
            done.complete("stopped".equals(why) ? null : why);
            Flow.Subscription s = subscription;
            if (s != null) s.cancel();
            CompletableFuture<HttpResponse<Void>> r = response;
            if (r != null) r.cancel(true);
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            if (done.isDone()) s.cancel();
            else s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(String line) {
            lastLine = System.nanoTime();
            if (line.isEmpty()) {
                String ev = event;
                String d = data == null ? "" : data.toString();
                event = "";
                data = null;
                if (ev.equals("reconnect")) {
                    done.complete(null);
                    subscription.cancel();
                } else if (ev.equals("config")) {
                    onConfigEvent(d);
                }
                return;
            }
            if (line.startsWith(":")) return;
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) value = value.substring(1);
            if (field.equals("event")) {
                event = value;
            } else if (field.equals("data")) {
                if (data == null) data = new StringBuilder(value);
                else data.append('\n').append(value);
            }
        }

        @Override
        public void onError(Throwable t) {
            done.complete(t.toString());
        }

        @Override
        public void onComplete() {
            done.complete("server closed the stream");
        }
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
