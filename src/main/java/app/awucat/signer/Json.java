package app.awucat.signer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal JSON encoder and tolerant parser. Values map to {@code Map<String,Object>} (insertion
 * ordered), {@code List<Object>}, {@code String}, {@code Long}/{@code Double}, {@code Boolean} and
 * {@code null}. The parser accepts trailing commas and any whitespace; duplicate keys keep the
 * last value. No third-party dependencies.
 */
final class Json {
    private Json() {}

    /** Serialises a value compactly ({@code indent < 0}) or pretty-printed with the given indent. */
    static String write(Object value, int indent) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, indent, 0);
        return sb.toString();
    }

    static String write(Object value) {
        return write(value, -1);
    }

    private static void write(Object v, StringBuilder sb, int indent, int depth) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            quote((String) v, sb);
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v).booleanValue());
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            sb.append(Double.isNaN(d) || Double.isInfinite(d) ? "null" : v.toString());
        } else if (v instanceof Number) {
            sb.append(v.toString());
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                newline(sb, indent, depth + 1);
                quote(String.valueOf(e.getKey()), sb);
                sb.append(indent >= 0 ? ": " : ":");
                write(e.getValue(), sb, indent, depth + 1);
            }
            if (!first) newline(sb, indent, depth);
            sb.append('}');
        } else if (v instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object o : (Iterable<?>) v) {
                if (!first) sb.append(',');
                first = false;
                newline(sb, indent, depth + 1);
                write(o, sb, indent, depth + 1);
            }
            if (!first) newline(sb, indent, depth);
            sb.append(']');
        } else if (v instanceof Object[]) {
            write(Arrays.asList((Object[]) v), sb, indent, depth);
        } else {
            quote(v.toString(), sb);
        }
    }

    private static void newline(StringBuilder sb, int indent, int depth) {
        if (indent < 0) return;
        sb.append('\n');
        for (int i = 0; i < indent * depth; i++) sb.append(' ');
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    /** Parses a JSON document. Throws {@link IllegalArgumentException} on malformed input. */
    static Object parse(String text) {
        Parser p = new Parser(text);
        Object v = p.value();
        p.ws();
        if (p.pos != text.length()) throw p.error("belge sonundan sonra fazladan karakter var");
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    /** Returns the string field {@code key}, or null when missing or not a string. */
    static String string(Map<String, Object> o, String key) {
        Object v = o == null ? null : o.get(key);
        return v instanceof String ? (String) v : null;
    }

    /** Recursive-descent parser over a string. */
    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) { this.s = s; }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException("JSON: " + msg + " (konum " + pos + ")");
        }

        void ws() {
            while (pos < s.length() && " \t\r\n".indexOf(s.charAt(pos)) >= 0) pos++;
        }

        char peek() {
            if (pos >= s.length()) throw error("beklenmedik son");
            return s.charAt(pos);
        }

        Object value() {
            ws();
            char c = peek();
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': literal("true"); return Boolean.TRUE;
                case 'f': literal("false"); return Boolean.FALSE;
                case 'n': literal("null"); return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw error("geçersiz değer");
            }
        }

        void literal(String lit) {
            if (!s.startsWith(lit, pos)) throw error("geçersiz sabit");
            pos += lit.length();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // '{'
            while (true) {
                ws();
                if (peek() == '}') { pos++; return m; }
                if (peek() != '"') throw error("nesne anahtarı bekleniyor");
                String key = string();
                ws();
                if (peek() != ':') throw error("':' bekleniyor");
                pos++;
                m.put(key, value());
                ws();
                if (peek() == ',') { pos++; continue; }
                if (peek() == '}') { pos++; return m; }
                throw error("',' veya '}' bekleniyor");
            }
        }

        List<Object> array() {
            List<Object> list = new ArrayList<>();
            pos++; // '['
            while (true) {
                ws();
                if (peek() == ']') { pos++; return list; }
                list.add(value());
                ws();
                if (peek() == ',') { pos++; continue; }
                if (peek() == ']') { pos++; return list; }
                throw error("',' veya ']' bekleniyor");
            }
        }

        String string() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = peek();
                pos++;
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = peek();
                pos++;
                switch (e) {
                    case '"': case '\\': case '/': sb.append(e); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > s.length()) throw error("eksik \\u kaçışı");
                        try {
                            // Surrogate pairs arrive as two consecutive escapes and combine naturally.
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error("geçersiz \\u kaçışı");
                        }
                        pos += 4;
                        break;
                    default: throw error("geçersiz kaçış dizisi");
                }
            }
        }

        Number number() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < s.length() && "0123456789+-.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            String t = s.substring(start, pos);
            try {
                if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) return Long.valueOf(t);
                return Double.valueOf(t);
            } catch (NumberFormatException ex) {
                throw error("geçersiz sayı");
            }
        }
    }
}
