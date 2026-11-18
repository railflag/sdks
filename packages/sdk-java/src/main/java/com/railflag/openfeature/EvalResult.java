package com.railflag.openfeature;

/** The outcome of one flag evaluation. */
public final class EvalResult {
    /** The variation's value: Boolean, String, Double, a {@code Map<String, Object>} or a {@code List<Object>}. Null on error. */
    public final Object value;
    /** Index of the served variation; null when {@link #reason} is ERROR. */
    public final Integer variationIndex;
    /** STATIC, DEFAULT, TARGETING_MATCH, SPLIT, DISABLED or ERROR. */
    public final String reason;
    /** The rule that matched ("target", "prerequisite:&lt;key&gt;" or a rule id), or null. */
    public final String ruleId;
    public final Integer flagVersion;
    /** FLAG_NOT_FOUND, TYPE_MISMATCH or GENERAL when {@link #reason} is ERROR, else null. */
    public final String errorCode;
    public final String errorMessage;

    EvalResult(Object value, Integer variationIndex, String reason, String ruleId, Integer flagVersion,
            String errorCode, String errorMessage) {
        this.value = value;
        this.variationIndex = variationIndex;
        this.reason = reason;
        this.ruleId = ruleId;
        this.flagVersion = flagVersion;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    static EvalResult error(String code, String message, Integer flagVersion) {
        return new EvalResult(null, null, "ERROR", null, flagVersion, code, message);
    }

    @Override
    public String toString() {
        return "EvalResult{value=" + value + ", variationIndex=" + variationIndex + ", reason=" + reason
                + ", ruleId=" + ruleId + ", flagVersion=" + flagVersion + ", errorCode=" + errorCode
                + ", errorMessage=" + errorMessage + "}";
    }
}
