package com.railflag.openfeature;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * What the SDK sends for experiments, ported from packages/eval/src/experiments.ts and pinned for every SDK
 * by packages/eval/vectors/events-vectors.json. Pure, like the evaluator.
 *
 * <p>The snapshot's optional {@code experiments} field, {@code {"flags": [...], "events": [...]}}, is there
 * only while the environment runs an experiment.
 */
final class Experiments {
    private Experiments() {}

    /** One user's variation of a flag in a running experiment. */
    static final class Exposure {
        final String flag;
        final int variation;
        final String key;

        Exposure(String flag, int variation, String key) {
            this.flag = flag;
            this.variation = variation;
            this.key = key;
        }

        /** The JSON the relay takes, with {@code ts} in epoch milliseconds. */
        Map<String, Object> toJson(long ts) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("flag", flag);
            out.put("variation", (double) variation);
            out.put("key", key);
            out.put("ts", (double) ts);
            return out;
        }
    }

    /** One track() call an experiment counts. */
    static final class Event {
        final String event;
        final String key;
        final double value;

        Event(String event, String key, double value) {
            this.event = event;
            this.key = key;
            this.value = value;
        }

        Map<String, Object> toJson(long ts) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("event", event);
            out.put("key", key);
            out.put("value", value);
            out.put("ts", (double) ts);
            return out;
        }
    }

    /** The snapshot's running experiments, or null when there are none. */
    static Map<?, ?> of(Map<String, Object> snap) {
        Object x = snap == null ? null : snap.get("experiments");
        return x instanceof Map ? (Map<?, ?>) x : null;
    }

    private static boolean includes(Object items, String s) {
        return items instanceof List && ((List<?>) items).contains(s);
    }

    /** The exposure an evaluation produces, or null. Only randomized users count: reason SPLIT, with a user key. */
    static Exposure exposureFor(Map<?, ?> experiments, String flagKey, Map<?, ?> ctx, EvalResult r) {
        if (experiments == null || !includes(experiments.get("flags"), flagKey)) return null;
        if (!"SPLIT".equals(r.reason) || r.variationIndex == null) return null;
        String key = Evaluator.contextKeyOf(ctx);
        if (key == null || key.isEmpty()) return null;
        return new Exposure(flagKey, r.variationIndex, key);
    }

    /** The event a track() call produces, or null. A missing or non-finite value counts as 0. */
    static Event trackFor(Map<?, ?> experiments, String event, Map<?, ?> ctx, Number value) {
        if (experiments == null || !includes(experiments.get("events"), event)) return null;
        String key = Evaluator.contextKeyOf(ctx);
        if (key == null || key.isEmpty()) return null;
        double v = value == null ? 0 : value.doubleValue();
        return new Event(event, key, Double.isFinite(v) ? v : 0);
    }

    /**
     * Remembers which (flag, variation, key) were already sent, so each is sent once. Oldest entries go
     * first when full. Safe to use from many threads; a repeat is a single lock-free lookup.
     */
    static final class Dedupe {
        private final int max;
        private final Map<String, Boolean> seen = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();

        Dedupe(int max) {
            this.max = max;
        }

        /** True the first time a record is seen. */
        boolean first(Exposure x) {
            String id = x.flag + '\u0000' + x.variation + '\u0000' + x.key;
            if (seen.putIfAbsent(id, Boolean.TRUE) != null) return false;
            order.add(id);
            while (seen.size() > max) {
                String oldest = order.poll();
                if (oldest == null) break;
                seen.remove(oldest);
            }
            return true;
        }
    }
}
