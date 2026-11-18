package com.railflag.openfeature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader that gives the values the evaluator works on: {@code Map<String, Object>},
 * {@code List<Object>}, {@code Double}, {@code String}, {@code Boolean} and {@code null}. Numbers
 * are always doubles, as in JavaScript, and strings keep lone UTF-16 surrogates, as JavaScript
 * strings do. Built in so the SDK doesn't depend on (or clash with) the application's JSON library.
 */
final class Json {
    private final String s;
    private int pos;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        Json p = new Json(text);
        p.skipSpace();
        Object v = p.value();
        p.skipSpace();
        if (p.pos != p.s.length()) throw p.error("unexpected data after the JSON value");
        return v;
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException("Invalid JSON at offset " + pos + ": " + msg);
    }

    private void skipSpace() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
            pos++;
        }
    }

    private Object value() {
        if (pos >= s.length()) throw error("unexpected end of input");
        char c = s.charAt(pos);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                literal("true");
                return Boolean.TRUE;
            case 'f':
                literal("false");
                return Boolean.FALSE;
            case 'n':
                literal("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw error("unexpected character '" + c + "'");
        }
    }

    private void literal(String word) {
        if (!s.startsWith(word, pos)) throw error("expected " + word);
        pos += word.length();
    }

    private Map<String, Object> object() {
        pos++;
        Map<String, Object> out = new LinkedHashMap<>();
        skipSpace();
        if (pos < s.length() && s.charAt(pos) == '}') {
            pos++;
            return out;
        }
        while (true) {
            skipSpace();
            if (pos >= s.length() || s.charAt(pos) != '"') throw error("expected a string key");
            String k = string();
            skipSpace();
            if (pos >= s.length() || s.charAt(pos) != ':') throw error("expected ':'");
            pos++;
            skipSpace();
            out.put(k, value());
            skipSpace();
            if (pos >= s.length()) throw error("unexpected end of input");
            char c = s.charAt(pos++);
            if (c == '}') return out;
            if (c != ',') throw error("expected ',' or '}'");
        }
    }

    private List<Object> array() {
        pos++;
        List<Object> out = new ArrayList<>();
        skipSpace();
        if (pos < s.length() && s.charAt(pos) == ']') {
            pos++;
            return out;
        }
        while (true) {
            skipSpace();
            out.add(value());
            skipSpace();
            if (pos >= s.length()) throw error("unexpected end of input");
            char c = s.charAt(pos++);
            if (c == ']') return out;
            if (c != ',') throw error("expected ',' or ']'");
        }
    }

    private Double number() {
        int start = pos;
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') pos++;
            else break;
        }
        try {
            return Double.valueOf(s.substring(start, pos));
        } catch (NumberFormatException e) {
            throw error("bad number");
        }
    }

    private String string() {
        pos++;
        StringBuilder b = null;
        int start = pos;
        while (true) {
            if (pos >= s.length()) throw error("unterminated string");
            char c = s.charAt(pos);
            if (c == '"') {
                String out = b == null ? s.substring(start, pos) : b.append(s, start, pos).toString();
                pos++;
                return out;
            }
            if (c < 0x20) throw error("control character in string");
            if (c != '\\') {
                pos++;
                continue;
            }
            if (b == null) b = new StringBuilder();
            b.append(s, start, pos);
            pos++;
            if (pos >= s.length()) throw error("unterminated escape");
            char e = s.charAt(pos++);
            switch (e) {
                case '"':
                case '\\':
                case '/':
                    b.append(e);
                    break;
                case 'b':
                    b.append('\b');
                    break;
                case 'f':
                    b.append('\f');
                    break;
                case 'n':
                    b.append('\n');
                    break;
                case 'r':
                    b.append('\r');
                    break;
                case 't':
                    b.append('\t');
                    break;
                case 'u':
                    if (pos + 4 > s.length()) throw error("short \\u escape");
                    try {
                        b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("bad \\u escape");
                    }
                    pos += 4;
                    break;
                default:
                    throw error("bad escape");
            }
            start = pos;
        }
    }

    /** Writes a value as JSON (used to compare snapshots and in tests). */
    static String stringify(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v);
        return b.toString();
    }

    private static void write(StringBuilder b, Object v) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof Boolean) {
            b.append(v);
        } else if (v instanceof Double) {
            b.append(Js.numberToString((Double) v));
        } else if (v instanceof String) {
            String str = (String) v;
            b.append('"');
            for (int i = 0; i < str.length(); i++) {
                char c = str.charAt(i);
                if (c == '"' || c == '\\') b.append('\\').append(c);
                else if (c < 0x20 || Character.isSurrogate(c)) b.append(String.format("\\u%04x", (int) c));
                else b.append(c);
            }
            b.append('"');
        } else if (v instanceof List) {
            b.append('[');
            boolean first = true;
            for (Object x : (List<?>) v) {
                if (!first) b.append(',');
                first = false;
                write(b, x);
            }
            b.append(']');
        } else if (v instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> en : ((Map<?, ?>) v).entrySet()) {
                if (!first) b.append(',');
                first = false;
                write(b, String.valueOf(en.getKey()));
                b.append(':');
                write(b, en.getValue());
            }
            b.append('}');
        } else {
            throw new IllegalArgumentException("not a JSON value: " + v.getClass());
        }
    }
}
