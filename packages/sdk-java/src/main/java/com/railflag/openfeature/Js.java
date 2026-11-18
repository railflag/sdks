package com.railflag.openfeature;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * JavaScript semantics the evaluator relies on, so Java gives the same answers as the TypeScript
 * evaluator (packages/eval): {@code String(x)}, {@code ===}, string ordering and {@code Date.parse}.
 * Java strings are UTF-16 like JavaScript's, so ordering and prefixes need no conversion.
 */
final class Js {
    private Js() {}

    /** {@code String(n)}: shortest round-trip digits, exponent notation outside 1e-7 ≤ |n| < 1e21. */
    static String numberToString(double x) {
        if (Double.isNaN(x)) return "NaN";
        if (Double.isInfinite(x)) return x > 0 ? "Infinity" : "-Infinity";
        if (x == 0) return "0";
        String sign = x < 0 ? "-" : "";
        double a = Math.abs(x);
        // Double.toString isn't always the shortest round-trip form before Java 19, so search for it.
        BigDecimal exact = new BigDecimal(a);
        BigDecimal shortest = exact;
        for (int p = 1; p <= 17; p++) {
            BigDecimal r = exact.round(new MathContext(p));
            if (r.doubleValue() == a) {
                shortest = r;
                break;
            }
        }
        shortest = shortest.stripTrailingZeros();
        String digits = shortest.unscaledValue().toString();
        int k = digits.length();
        int n = k - shortest.scale(); // a = 0.<digits> × 10^n
        String out;
        if (k <= n && n <= 21) {
            out = digits + "0".repeat(n - k);
        } else if (0 < n && n <= 21) {
            out = digits.substring(0, n) + "." + digits.substring(n);
        } else if (-6 < n && n <= 0) {
            out = "0." + "0".repeat(-n) + digits;
        } else {
            String m = k == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
            int e = n - 1;
            out = m + "e" + (e >= 0 ? "+" : "-") + Math.abs(e);
        }
        return sign + out;
    }

    /** {@code String(v)} for JSON-like values. */
    static String string(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean) return ((Boolean) v) ? "true" : "false";
        if (v instanceof Double) return numberToString((Double) v);
        if (v instanceof String) return (String) v;
        if (v instanceof List) {
            StringBuilder b = new StringBuilder();
            boolean first = true;
            for (Object x : (List<?>) v) {
                if (!first) b.append(',');
                first = false;
                if (x != null) b.append(string(x));
            }
            return b.toString();
        }
        return "[object Object]";
    }

    /** {@code a === b} for scalars. */
    static boolean strictEquals(Object a, Object b) {
        if (a instanceof Double && b instanceof Double) return ((Double) a).doubleValue() == ((Double) b).doubleValue();
        if (a instanceof Boolean && b instanceof Boolean) return a.equals(b);
        if (a instanceof String && b instanceof String) return a.equals(b);
        return false;
    }

    /** {@code a - b} or the UTF-16 order of two strings; null when the types differ. */
    static Double compare(Object a, Object b) {
        if (a instanceof Double && b instanceof Double) return (Double) a - (Double) b;
        if (a instanceof String && b instanceof String) return (double) Integer.signum(((String) a).compareTo((String) b));
        return null;
    }

    private static final Pattern ISO = Pattern.compile(
            "^([+-]\\d{6}|\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?"
                    + "(?:T(\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d+))?)?(Z|[+-]\\d{2}:\\d{2})?)?$");

    /**
     * {@code Date.parse} for ISO 8601 and RFC 1123 dates, in epoch milliseconds, or null. A
     * date-time without an offset is read as UTC (JavaScript would use the machine's time zone).
     */
    static Double parseTime(String s) {
        Matcher m = ISO.matcher(s);
        if (m.matches()) {
            int year = Integer.parseInt(m.group(1));
            int month = num(m.group(2), 1), day = num(m.group(3), 1);
            int hour = num(m.group(4), 0), minute = num(m.group(5), 0), second = num(m.group(6), 0);
            String frac = m.group(7);
            if (month < 1 || month > 12 || day < 1 || day > LocalDate.of(year, month, 1).lengthOfMonth()
                    || hour > 24 || minute >= 60 || second >= 60) return null;
            if (hour == 24 && (minute != 0 || second != 0 || (frac != null && !frac.matches("0*")))) return null;
            int ms = frac == null ? 0 : Integer.parseInt((frac + "00").substring(0, 3));
            long days = LocalDate.of(year, month, 1).toEpochDay() + day - 1;
            long t = ((days * 24 + hour) * 60 + minute) * 60 + second;
            String tz = m.group(8);
            if (tz != null && !tz.equals("Z")) {
                int off = (Integer.parseInt(tz.substring(1, 3)) * 60 + Integer.parseInt(tz.substring(4, 6))) * 60;
                t += tz.charAt(0) == '+' ? -off : off;
            }
            return (double) (t * 1000 + ms);
        }
        try {
            return (double) ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static int num(String s, int dflt) {
        return s == null ? dflt : Integer.parseInt(s);
    }

    static Double toTime(Object v) {
        if (v instanceof Double) {
            double d = (Double) v;
            return Double.isFinite(d) ? d : null;
        }
        if (v instanceof String) return parseTime((String) v);
        return null;
    }

    private static final Map<String, Object> REGEXES = new ConcurrentHashMap<>();
    private static final Object NO_REGEX = new Object();

    /** A JavaScript regex source compiled with java.util.regex, or null when it doesn't compile. */
    static Pattern regex(String src) {
        Object p = REGEXES.computeIfAbsent(src, s -> {
            try {
                return Pattern.compile(s);
            } catch (PatternSyntaxException e) {
                return NO_REGEX;
            }
        });
        return p == NO_REGEX ? null : (Pattern) p;
    }
}
