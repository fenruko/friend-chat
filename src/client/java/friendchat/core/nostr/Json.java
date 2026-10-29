package friendchat.core.nostr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny JSON reader/writer: Map (object), List, String, Long/Double, Boolean, null. No dependencies. */
public final class Json {
    private Json() { }

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder(128);
        write(sb, o);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object o) {
        if (o == null) sb.append("null");
        else if (o instanceof String s) str(sb, s);
        else if (o instanceof Boolean || o instanceof Integer || o instanceof Long) sb.append(o);
        else if (o instanceof Number n) sb.append(n.doubleValue() == Math.rint(n.doubleValue()) ? Long.toString(n.longValue()) : n.toString());
        else if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                str(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object x : it) {
                if (!first) sb.append(',');
                first = false;
                write(sb, x);
            }
            sb.append(']');
        } else str(sb, o.toString());
    }

    /** NIP-01 escaping: only the short escapes, so event ids hash identically on every relay. */
    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    /** @return the parsed value, or null when the text is not valid JSON (also for the JSON value null). */
    public static Object parse(String s) {
        try {
            P p = new P(s);
            p.ws();
            Object v = p.value(0);
            p.ws();
            return p.i == s.length() ? v : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    public static String str(Map<String, Object> m, String k, String def) {
        Object v = m == null ? null : m.get(k);
        return v instanceof String s ? s : def;
    }

    public static long num(Map<String, Object> m, String k, long def) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Number n ? n.longValue() : def;
    }

    private static final class P {
        final String s;
        int i;

        P(String s) { this.s = s; }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        Object value(int depth) {
            if (depth > 32) throw new IllegalStateException("too deep");
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws();
                    String k = string();
                    ws();
                    expect(':');
                    ws();
                    m.put(k, value(depth + 1));
                    ws();
                    char d = s.charAt(i++);
                    if (d == '}') return m;
                    if (d != ',') throw new IllegalStateException();
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    ws();
                    l.add(value(depth + 1));
                    ws();
                    char d = s.charAt(i++);
                    if (d == ']') return l;
                    if (d != ',') throw new IllegalStateException();
                }
            }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(st, i);
            if (n.isEmpty()) throw new IllegalStateException();
            if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0) return Long.parseLong(n);
            return Double.parseDouble(n);
        }

        void expect(char c) {
            if (s.charAt(i++) != c) throw new IllegalStateException();
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> sb.append(e);
                }
            }
        }
    }
}
