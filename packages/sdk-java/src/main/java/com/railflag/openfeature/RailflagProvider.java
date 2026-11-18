package com.railflag.openfeature;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventProvider;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderEventDetails;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Railflag's OpenFeature provider. It downloads your environment's flag config once, evaluates every
 * flag check in process, and picks up changes within a second over Server-Sent Events.
 *
 * <pre>{@code
 * OpenFeatureAPI api = OpenFeatureAPI.getInstance();
 * api.setProviderAndWait(new RailflagProvider(RailflagOptions.builder()
 *     .sdkKey(System.getenv("RAILFLAG_SERVER_KEY"))
 *     .baseUrl("https://relay.railflag.com")
 *     .build()));
 * Client client = api.getClient();
 * boolean on = client.getBooleanValue("new-checkout", false,
 *     new ImmutableContext("user-42", Map.of("plan", new Value("pro"))));
 * }</pre>
 */
public final class RailflagProvider extends EventProvider {
    /** This SDK's version, sent in the User-Agent header. */
    public static final String VERSION = "0.1.0";

    private final ConfigSource source;

    /** The config is loaded when OpenFeature initializes the provider (setProviderAndWait). */
    public RailflagProvider(RailflagOptions options) {
        this.source = new ConfigSource(Objects.requireNonNull(options, "options"));
        source.onChange(this::changed);
    }

    ConfigSource source() {
        return source;
    }

    @Override
    public Metadata getMetadata() {
        return () -> "railflag";
    }

    /** Loads the config; OpenFeature reports the provider ready when this returns, or in error if it throws. */
    @Override
    public void initialize(EvaluationContext evaluationContext) throws Exception {
        source.start();
    }

    /** Closes the stream and stops polling. */
    @Override
    public void shutdown() {
        source.stop();
        super.shutdown();
    }

    private void changed(Map<String, Object> snap, Map<String, Object> prev) {
        if (prev == null) return; // the first load is the READY event, which OpenFeature sends itself
        Map<?, ?> before = flags(prev), after = flags(snap);
        List<String> changed = new ArrayList<>();
        for (Map.Entry<?, ?> e : after.entrySet()) {
            if (!Objects.equals(before.get(e.getKey()), e.getValue())) changed.add((String) e.getKey());
        }
        for (Object k : before.keySet()) {
            if (!after.containsKey(k)) changed.add((String) k);
        }
        Collections.sort(changed);
        emitProviderConfigurationChanged(ProviderEventDetails.builder()
                .flagsChanged(changed)
                .message("config version " + (long) ConfigSource.versionOf(snap))
                .build());
    }

    private static Map<?, ?> flags(Map<String, Object> snap) {
        Object f = snap.get("flags");
        return f instanceof Map ? (Map<?, ?>) f : Collections.emptyMap();
    }

    /** Turns a flag value into the requested Java type; throws IllegalArgumentException (a type mismatch) if it can't. */
    private interface Converter<T> {
        T convert(String flag, Object value);
    }

    private <T> ProviderEvaluation<T> resolve(String flag, T defaultValue, EvaluationContext ctx, String expected, Converter<T> converter) {
        Map<String, Object> snap = source.snapshot();
        if (snap == null) return error(defaultValue, ErrorCode.PROVIDER_NOT_READY, "Railflag config not loaded yet");
        EvalResult r = Evaluator.evaluate(snap, flag, toContext(ctx), expected);
        if (r.errorCode != null) return error(defaultValue, errorCode(r.errorCode), r.errorMessage);
        T value;
        try {
            value = converter.convert(flag, r.value);
        } catch (IllegalArgumentException e) {
            return error(defaultValue, ErrorCode.TYPE_MISMATCH, e.getMessage());
        }
        ImmutableMetadata.ImmutableMetadataBuilder meta = ImmutableMetadata.builder()
                .addInteger("configVersion", (int) ConfigSource.versionOf(snap))
                .addInteger("flagVersion", r.flagVersion == null ? 0 : r.flagVersion);
        if (r.ruleId != null) meta.addString("ruleId", r.ruleId);
        return ProviderEvaluation.<T>builder()
                .value(value)
                .variant(String.valueOf(r.variationIndex))
                .reason(r.reason)
                .flagMetadata(meta.build())
                .build();
    }

    private static ErrorCode errorCode(String code) {
        switch (code) {
            case "FLAG_NOT_FOUND":
                return ErrorCode.FLAG_NOT_FOUND;
            case "TYPE_MISMATCH":
                return ErrorCode.TYPE_MISMATCH;
            case "PARSE_ERROR":
                return ErrorCode.PARSE_ERROR;
            default:
                return ErrorCode.GENERAL;
        }
    }

    private static <T> ProviderEvaluation<T> error(T defaultValue, ErrorCode code, String message) {
        return ProviderEvaluation.<T>builder()
                .value(defaultValue)
                .reason(Reason.ERROR.toString())
                .errorCode(code)
                .errorMessage(message)
                .build();
    }

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue, EvaluationContext ctx) {
        return resolve(key, defaultValue, ctx, "boolean", (f, v) -> (Boolean) v);
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(String key, String defaultValue, EvaluationContext ctx) {
        return resolve(key, defaultValue, ctx, "string", (f, v) -> (String) v);
    }

    /** A number flag whose value isn't a whole number in the int range is a type mismatch. */
    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer defaultValue, EvaluationContext ctx) {
        return resolve(key, defaultValue, ctx, "number", (f, v) -> {
            double d = (Double) v;
            if (d != Math.rint(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Flag " + f + " is " + Js.numberToString(d) + ", not an integer");
            }
            return (int) d;
        });
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double defaultValue, EvaluationContext ctx) {
        return resolve(key, defaultValue, ctx, "number", (f, v) -> (Double) v);
    }

    /** JSON flags come back as a structure or a list, with numbers as doubles. */
    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(String key, Value defaultValue, EvaluationContext ctx) {
        return resolve(key, defaultValue, ctx, "json", (f, v) -> {
            if (!(v instanceof Map) && !(v instanceof List)) {
                throw new IllegalArgumentException("Flag " + f + " value is not an object or list");
            }
            return Value.objectToValue(v);
        });
    }

    /**
     * Turns an OpenFeature context into the values the evaluator compares: numbers become doubles and
     * instants become epoch milliseconds, as in JavaScript.
     */
    static Map<String, Object> toContext(EvaluationContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (ctx == null) return out;
        for (Map.Entry<String, Value> e : ctx.asMap().entrySet()) out.put(e.getKey(), normalize(e.getValue()));
        return out;
    }

    private static Object normalize(Value v) {
        if (v == null || v.isNull()) return null;
        if (v.isBoolean()) return v.asBoolean();
        if (v.isString()) return v.asString();
        if (v.isNumber()) return v.asDouble();
        if (v.isInstant()) return (double) v.asInstant().toEpochMilli();
        if (v.isList()) {
            List<Object> out = new ArrayList<>();
            for (Value x : v.asList()) out.add(normalize(x));
            return out;
        }
        if (v.isStructure()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Value> e : v.asStructure().asMap().entrySet()) out.put(e.getKey(), normalize(e.getValue()));
            return out;
        }
        return null;
    }
}
