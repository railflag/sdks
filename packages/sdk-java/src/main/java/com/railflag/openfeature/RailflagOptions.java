package com.railflag.openfeature;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Settings for {@link RailflagProvider}. The SDK key and relay URL are required.
 *
 * <pre>{@code
 * RailflagOptions.builder()
 *     .sdkKey(System.getenv("RAILFLAG_SERVER_KEY"))
 *     .baseUrl("https://relay.railflag.com")
 *     .build();
 * }</pre>
 */
public final class RailflagOptions {
    final String sdkKey;
    final String baseUrl;
    final boolean stream;
    final Duration pollInterval;
    final Duration timeout;
    final HttpClient httpClient;
    /** The relay sends a heartbeat every 25 s, so a stream silent for this long is dead. */
    final Duration streamReadTimeout;
    final boolean sendEvents;
    final Duration eventsInterval;

    private RailflagOptions(Builder b) {
        String url = b.baseUrl == null ? "" : b.baseUrl;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException(
                    "Railflag: baseUrl must be your Railflag relay URL, like https://relay.example.com (got \"" + url + "\")");
        }
        if (b.sdkKey == null || b.sdkKey.isEmpty()) {
            throw new IllegalArgumentException("Railflag: sdkKey is required (a server key from the Environments page)");
        }
        this.sdkKey = b.sdkKey;
        this.baseUrl = url.replaceAll("/+$", "");
        this.stream = b.stream;
        this.pollInterval = positive(b.pollInterval, Duration.ofSeconds(30));
        this.timeout = positive(b.timeout, Duration.ofSeconds(10));
        this.httpClient = b.httpClient;
        this.streamReadTimeout = positive(b.streamReadTimeout, Duration.ofSeconds(60));
        this.sendEvents = b.sendEvents;
        this.eventsInterval = positive(b.eventsInterval, Duration.ofSeconds(10));
    }

    private static Duration positive(Duration d, Duration fallback) {
        return d == null || d.isZero() || d.isNegative() ? fallback : d;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String sdkKey;
        private String baseUrl;
        private boolean stream = true;
        private Duration pollInterval;
        private Duration timeout;
        private HttpClient httpClient;
        private Duration streamReadTimeout;
        private boolean sendEvents = true;
        private Duration eventsInterval;

        private Builder() {}

        /** A server key: Environments → keys. Keep it on the server. */
        public Builder sdkKey(String sdkKey) {
            this.sdkKey = sdkKey;
            return this;
        }

        /** Your Railflag relay, like {@code https://relay.railflag.com}. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** False polls only, without the Server-Sent Events stream. Default true. */
        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        /** How often to check for changes; the safety net behind the stream. Default 30 s. */
        public Builder pollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
            return this;
        }

        /** Per config request. Default 10 s. */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /** The HTTP client to use (proxies, custom TLS). Default: a new {@code HttpClient}. */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /**
         * Send experiment data: which variation each user got, for flags in a running experiment, and the
         * {@code track()} events an experiment counts. This sends user keys (Railflag hashes them before
         * storing them), only for those flags and events, and only while an experiment runs. Default true.
         */
        public Builder sendEvents(boolean sendEvents) {
            this.sendEvents = sendEvents;
            return this;
        }

        /** How often experiment data is sent. Default 10 s. */
        public Builder eventsInterval(Duration eventsInterval) {
            this.eventsInterval = eventsInterval;
            return this;
        }

        Builder streamReadTimeout(Duration streamReadTimeout) {
            this.streamReadTimeout = streamReadTimeout;
            return this;
        }

        /** @throws IllegalArgumentException when the key is missing or the URL isn't http(s) */
        public RailflagOptions build() {
            return new RailflagOptions(this);
        }
    }
}
