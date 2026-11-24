/*
 * File Name:       Json.java
 * Relative Path:   src/main/java/com/boehnenelton/bejson/core/Json.java
 * Description:     Python-compatible JSON layer for the Java Core port. Mirrors CPython's json module semantics
 *                  (loads, dumps with indent / ensure_ascii, float repr, NaN/Infinity, big ints) and Python's
 *                  value equality / str() / repr() so ported Core logic behaves identically.
 * Value model:     null | Boolean | Long | BigInteger | Double | String | List&lt;Object&gt; (ArrayList) | Map&lt;String,Object&gt; (LinkedHashMap)
 * VERSION:         1.0.0
 * Release_Version: 300 (port of libraries_104 Core, Python reference)
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   d66b7b48-31bc-41ae-a76c-fcbccc5b379a
 */
package com.boehnenelton.bejson.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {

    private Json() { }

    /** Mirrors json.JSONDecodeError. */
    public static final class JsonParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final String msg;
        public final int pos;
        public final int lineno;
        public final int colno;

        JsonParseException(String msg, String doc, int pos) {
            super(format(msg, doc, pos));
            this.msg = msg;
            this.pos = pos;
            int line = 1;
            int lastNl = -1;
            int lim = Math.min(pos, doc.length());
            for (int i = 0; i < lim; i++) {
                if (doc.charAt(i) == '\n') { line++; lastNl = i; }
            }
            this.lineno = line;
            this.colno = lastNl < 0 ? pos + 1 : pos - lastNl;
        }

        private static String format(String msg, String doc, int pos) {
            int line = 1;
            int lastNl = -1;
            int lim = Math.min(pos, doc.length());
            for (int i = 0; i < lim; i++) {
                if (doc.charAt(i) == '\n') { line++; lastNl = i; }
            }
            int col = lastNl < 0 ? pos + 1 : pos - lastNl;
            return msg + ": line " + line + " column " + col + " (char " + pos + ")";
        }
    }

    // ------------------------------------------------------------------ parsing

    /** json.loads(text). */
    public static Object loads(String text) {
        if (text == null) {
            throw new IllegalArgumentException("the JSON object must be str, bytes or bytearray, not NoneType");
        }
        Parser p = new Parser(text);
        int idx = p.skipWs(0);
        Object[] holder = new Object[1];
        int end;
        try {
            end = p.scanOnce(idx, holder);
        } catch (Stop s) {
            throw new JsonParseException("Expecting value", text, s.pos);
        }
        end = p.skipWs(end);
        if (end != text.length()) {
            throw new JsonParseException("Extra data", text, end);
        }
        return holder[0];
    }

    /** json.JSONDecoder().raw_decode(text, start): returns {value, endIndex(Integer)}. */
    public static Object[] rawDecode(String text, int start) {
        Parser p = new Parser(text);
        Object[] holder = new Object[1];
        int end;
        try {
            end = p.scanOnce(start, holder);
        } catch (Stop s) {
            throw new JsonParseException("Expecting value", text, s.pos);
        }
        return new Object[] {holder[0], Integer.valueOf(end)};
    }

    /** Maximum container nesting accepted by the parser/serializer/deep copy (Python raises RecursionError; Java would overflow the stack). */
    public static final int MAX_DEPTH = 256;

    /** Analogue of Python's RecursionError for over-deep JSON values. */
    public static final class JsonRecursionError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public JsonRecursionError(String message) { super(message); }
    }

    private static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int pos;
        Stop(int pos) { super(null, null, false, false); this.pos = pos; }
    }

    private static final class Parser {
        final String s;
        final int n;

        Parser(String s) { this.s = s; this.n = s.length(); }

        int skipWs(int i) {
            while (i < n) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break;
            }
            return i;
        }

        /** Returns end index; stores value in out[0]. Throws Stop when no value at idx. */
        int scanOnce(int idx, Object[] out) {
            if (idx >= n || idx < 0) throw new Stop(idx);
            char c = s.charAt(idx);
            switch (c) {
                case '"':
                    return scanString(idx + 1, out);
                case '{':
                    return scanObject(idx + 1, out);
                case '[':
                    return scanArray(idx + 1, out);
                case 'n':
                    if (s.startsWith("null", idx)) { out[0] = null; return idx + 4; }
                    break;
                case 't':
                    if (s.startsWith("true", idx)) { out[0] = Boolean.TRUE; return idx + 4; }
                    break;
                case 'f':
                    if (s.startsWith("false", idx)) { out[0] = Boolean.FALSE; return idx + 5; }
                    break;
                case 'N':
                    if (s.startsWith("NaN", idx)) { out[0] = Double.valueOf(Double.NaN); return idx + 3; }
                    break;
                case 'I':
                    if (s.startsWith("Infinity", idx)) { out[0] = Double.valueOf(Double.POSITIVE_INFINITY); return idx + 8; }
                    break;
                case '-':
                    if (s.startsWith("-Infinity", idx)) { out[0] = Double.valueOf(Double.NEGATIVE_INFINITY); return idx + 9; }
                    break;
                default:
                    break;
            }
            int e = matchNumber(idx, out);
            if (e < 0) throw new Stop(idx);
            return e;
        }

        /** Matches -?(0|[1-9]\d*)(\.\d+)?([eE][-+]?\d+)? ; returns end or -1. */
        int matchNumber(int idx, Object[] out) {
            int i = idx;
            if (i < n && s.charAt(i) == '-') i++;
            if (i >= n) return -1;
            char c = s.charAt(i);
            if (c == '0') {
                i++;
            } else if (c >= '1' && c <= '9') {
                i++;
                while (i < n && isDigit(s.charAt(i))) i++;
            } else {
                return -1;
            }
            int intEnd = i;
            boolean isFloat = false;
            if (i + 1 < n && s.charAt(i) == '.' && isDigit(s.charAt(i + 1))) {
                i += 2;
                while (i < n && isDigit(s.charAt(i))) i++;
                isFloat = true;
            }
            if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                int j = i + 1;
                if (j < n && (s.charAt(j) == '+' || s.charAt(j) == '-')) j++;
                if (j < n && isDigit(s.charAt(j))) {
                    while (j < n && isDigit(s.charAt(j))) j++;
                    i = j;
                    isFloat = true;
                }
            }
            String tok = s.substring(idx, i);
            if (isFloat) {
                out[0] = Double.valueOf(Double.parseDouble(tok));
            } else {
                String digits = s.substring(idx, intEnd);
                out[0] = parseInteger(digits);
            }
            return i;
        }

        static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

        int scanString(int end, Object[] out) {
            int begin = end - 1;
            StringBuilder sb = null;
            int chunkStart = end;
            while (true) {
                if (end >= n) throw new JsonParseException("Unterminated string starting at", s, begin);
                char c = s.charAt(end);
                if (c == '"') {
                    String chunk = s.substring(chunkStart, end);
                    out[0] = sb == null ? chunk : sb.append(chunk).toString();
                    return end + 1;
                }
                if (c == '\\') {
                    if (sb == null) sb = new StringBuilder();
                    sb.append(s, chunkStart, end);
                    end++;
                    if (end >= n) throw new JsonParseException("Unterminated string starting at", s, begin);
                    char esc = s.charAt(end);
                    if (esc != 'u') {
                        char r;
                        switch (esc) {
                            case '"': r = '"'; break;
                            case '\\': r = '\\'; break;
                            case '/': r = '/'; break;
                            case 'b': r = '\b'; break;
                            case 'f': r = '\f'; break;
                            case 'n': r = '\n'; break;
                            case 'r': r = '\r'; break;
                            case 't': r = '\t'; break;
                            default:
                                throw new JsonParseException("Invalid \\escape", s, end - 1);
                        }
                        sb.append(r);
                        end++;
                    } else {
                        int uni = hex4(end + 1);
                        end += 5;
                        if (uni >= 0xD800 && uni <= 0xDBFF && end + 1 < n
                                && s.charAt(end) == '\\' && s.charAt(end + 1) == 'u') {
                            int lo = hex4(end + 2);
                            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                                sb.append((char) uni).append((char) lo);
                                end += 6;
                                chunkStart = end;
                                continue;
                            }
                        }
                        sb.append((char) uni);
                    }
                    chunkStart = end;
                    continue;
                }
                if (c < 0x20) {
                    throw new JsonParseException("Invalid control character at", s, end);
                }
                end++;
            }
        }

        int hex4(int pos) {
            if (pos + 4 > n) throw new JsonParseException("Invalid \\uXXXX escape", s, pos - 1);
            int v = 0;
            for (int i = 0; i < 4; i++) {
                int d = Character.digit(s.charAt(pos + i), 16);
                char ch = s.charAt(pos + i);
                boolean ok = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
                if (!ok || d < 0) throw new JsonParseException("Invalid \\uXXXX escape", s, pos - 1);
                v = (v << 4) | d;
            }
            return v;
        }

        int depth;

        private void enter(String what) {
            if (++depth > MAX_DEPTH) {
                depth--;
                throw new JsonRecursionError("maximum recursion depth exceeded while decoding a JSON " + what + " from a unicode string");
            }
        }

        int scanObject(int end, Object[] out) {
            enter("object");
            try { return scanObjectImpl(end, out); } finally { depth--; }
        }

        int scanArray(int end, Object[] out) {
            enter("array");
            try { return scanArrayImpl(end, out); } finally { depth--; }
        }

        int scanObjectImpl(int end, Object[] out) {
            Map<String, Object> pairs = new LinkedHashMap<String, Object>();
            end = skipWs(end);
            char next = end < n ? s.charAt(end) : '\0';
            boolean atEnd = end >= n;
            if (atEnd || next != '"') {
                if (!atEnd && next == '}') { out[0] = pairs; return end + 1; }
                if (atEnd || next != '"') {
                    throw new JsonParseException("Expecting property name enclosed in double quotes", s, end);
                }
            }
            end++;
            while (true) {
                Object[] k = new Object[1];
                end = scanString(end, k);
                String key = (String) k[0];
                if (end >= n || s.charAt(end) != ':') {
                    end = skipWs(end);
                    if (end >= n || s.charAt(end) != ':') {
                        throw new JsonParseException("Expecting ':' delimiter", s, end);
                    }
                }
                end++;
                end = skipWs(end);
                Object[] v = new Object[1];
                try {
                    end = scanOnce(end, v);
                } catch (Stop st) {
                    throw new JsonParseException("Expecting value", s, st.pos);
                }
                pairs.put(key, v[0]);
                end = skipWs(end);
                char nc = end < n ? s.charAt(end) : '\0';
                boolean eof = end >= n;
                end++;
                if (!eof && nc == '}') break;
                if (eof || nc != ',') {
                    throw new JsonParseException("Expecting ',' delimiter", s, end - 1);
                }
                int commaIdx = end - 1;
                end = skipWs(end);
                nc = end < n ? s.charAt(end) : '\0';
                eof = end >= n;
                end++;
                if (eof || nc != '"') {
                    if (!eof && nc == '}') {
                        throw new JsonParseException("Illegal trailing comma before end of object", s, commaIdx);
                    }
                    throw new JsonParseException("Expecting property name enclosed in double quotes", s, end - 1);
                }
            }
            out[0] = pairs;
            return end;
        }

        int scanArrayImpl(int end, Object[] out) {
            List<Object> vals = new ArrayList<Object>();
            end = skipWs(end);
            if (end < n && s.charAt(end) == ']') { out[0] = vals; return end + 1; }
            while (true) {
                Object[] v = new Object[1];
                try {
                    end = scanOnce(end, v);
                } catch (Stop st) {
                    throw new JsonParseException("Expecting value", s, st.pos);
                }
                vals.add(v[0]);
                end = skipWs(end);
                char nc = end < n ? s.charAt(end) : '\0';
                boolean eof = end >= n;
                end++;
                if (!eof && nc == ']') break;
                if (eof || nc != ',') {
                    throw new JsonParseException("Expecting ',' delimiter", s, end - 1);
                }
                int commaIdx = end - 1;
                end = skipWs(end);
                if (end < n && s.charAt(end) == ']') {
                    throw new JsonParseException("Illegal trailing comma before end of array", s, commaIdx);
                }
            }
            out[0] = vals;
            return end;
        }
    }

    private static Object parseInteger(String digits) {
        if (digits.length() <= 18) {
            return Long.valueOf(Long.parseLong(digits));
        }
        BigInteger b = new BigInteger(digits);
        if (b.bitLength() <= 63) return Long.valueOf(b.longValue());
        return b;
    }

    // ------------------------------------------------------------------ serializing

    /** json.dumps(obj) with Python defaults (separators ", " and ": ", ensure_ascii=True). */
    public static String dumps(Object obj) {
        StringBuilder sb = new StringBuilder();
        write(sb, obj, -1, 0, true, false);
        return sb.toString();
    }

    /** json.dumps(obj, indent=indent, ensure_ascii=ensureAscii). indent &lt; 0 means no indent. */
    public static String dumps(Object obj, int indent, boolean ensureAscii) {
        StringBuilder sb = new StringBuilder();
        write(sb, obj, indent, 0, ensureAscii, false);
        return sb.toString();
    }

    /** json.dumps(obj, indent=indent, ensure_ascii=ensureAscii, sort_keys=sortKeys). */
    public static String dumps(Object obj, int indent, boolean ensureAscii, boolean sortKeys) {
        StringBuilder sb = new StringBuilder();
        write(sb, obj, indent, 0, ensureAscii, sortKeys);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object o, int indent, int level, boolean ascii, boolean sortKeys) {
        if (level > MAX_DEPTH) throw new JsonRecursionError("maximum recursion depth exceeded while encoding a JSON object");
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String) { writeString(sb, (String) o, ascii); return; }
        if (o instanceof Boolean) { sb.append(((Boolean) o).booleanValue() ? "true" : "false"); return; }
        if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            if (Double.isNaN(d)) sb.append("NaN");
            else if (Double.isInfinite(d)) sb.append(d > 0 ? "Infinity" : "-Infinity");
            else sb.append(floatRepr(d));
            return;
        }
        if (o instanceof Number) { sb.append(o.toString()); return; }
        if (o instanceof List) {
            List<Object> l = (List<Object>) o;
            if (l.isEmpty()) { sb.append("[]"); return; }
            sb.append('[');
            boolean first = true;
            for (Object e : l) {
                if (!first) sb.append(indent >= 0 ? "," : ", ");
                first = false;
                if (indent >= 0) newline(sb, indent, level + 1);
                write(sb, e, indent, level + 1, ascii, sortKeys);
            }
            if (indent >= 0) newline(sb, indent, level);
            sb.append(']');
            return;
        }
        if (o instanceof Map) {
            Map<Object, Object> m = (Map<Object, Object>) o;
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append('{');
            boolean first = true;
            List<Object> keys = new ArrayList<Object>(m.keySet());
            if (sortKeys) {
                java.util.Collections.sort((List<String>) (List<?>) keys);
            }
            for (Object k : keys) {
                if (!first) sb.append(indent >= 0 ? "," : ", ");
                first = false;
                if (indent >= 0) newline(sb, indent, level + 1);
                String ks;
                if (k instanceof String) ks = (String) k;
                else if (k instanceof Boolean) ks = ((Boolean) k).booleanValue() ? "true" : "false";
                else if (k == null) ks = "null";
                else if (k instanceof Double) ks = floatRepr(((Double) k).doubleValue());
                else ks = k.toString();
                writeString(sb, ks, ascii);
                sb.append(": ");
                write(sb, m.get(k), indent, level + 1, ascii, sortKeys);
            }
            if (indent >= 0) newline(sb, indent, level);
            sb.append('}');
            return;
        }
        throw new IllegalArgumentException("Object of type " + o.getClass().getSimpleName() + " is not JSON serializable");
    }

    private static void newline(StringBuilder sb, int indent, int level) {
        sb.append('\n');
        for (int i = 0; i < indent * level; i++) sb.append(' ');
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static void writeString(StringBuilder sb, String s, boolean ascii) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20 || (ascii && c > 0x7e)) {
                        // CPython ESCAPE_ASCII escapes everything outside ' '..'~', so 0x7f becomes \u007f too.
                        sb.append("\\u").append(HEX[(c >> 12) & 0xf]).append(HEX[(c >> 8) & 0xf])
                          .append(HEX[(c >> 4) & 0xf]).append(HEX[c & 0xf]);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ Python float repr

    /** repr(float) for finite doubles, exactly as CPython prints it. */
    public static String floatRepr(double d) {
        if (Double.isNaN(d)) return "nan";
        if (Double.isInfinite(d)) return d > 0 ? "inf" : "-inf";
        if (d == 0.0) return (1.0 / d < 0) ? "-0.0" : "0.0";
        BigDecimal exact = new BigDecimal(Math.abs(d));
        BigDecimal best = null;
        for (int p = 1; p <= 17; p++) {
            BigDecimal r = exact.round(new MathContext(p, RoundingMode.HALF_EVEN));
            if (r.doubleValue() == Math.abs(d)) { best = r; break; }
        }
        if (best == null) best = exact.round(new MathContext(17, RoundingMode.HALF_EVEN));
        String digits = best.unscaledValue().toString();
        // strip trailing zeros of the digit string
        int end = digits.length();
        while (end > 1 && digits.charAt(end - 1) == '0') end--;
        // decpt: value = 0.DIGITS * 10^decpt
        int decpt = best.precision() - best.scale();
        digits = digits.substring(0, end);
        StringBuilder sb = new StringBuilder();
        if (d < 0) sb.append('-');
        if (decpt > 16 || decpt <= -4) {
            sb.append(digits.charAt(0));
            if (digits.length() > 1) sb.append('.').append(digits, 1, digits.length());
            int exp = decpt - 1;
            sb.append('e').append(exp < 0 ? '-' : '+');
            int ae = Math.abs(exp);
            if (ae < 10) sb.append('0');
            sb.append(ae);
        } else if (decpt <= 0) {
            sb.append("0.");
            for (int i = 0; i < -decpt; i++) sb.append('0');
            sb.append(digits);
        } else if (decpt >= digits.length()) {
            sb.append(digits);
            for (int i = digits.length(); i < decpt; i++) sb.append('0');
            sb.append(".0");
        } else {
            sb.append(digits, 0, decpt).append('.').append(digits, decpt, digits.length());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ Python semantics helpers

    public static boolean isInt(Object o) {
        return o instanceof Long || o instanceof Integer || o instanceof Short || o instanceof Byte || o instanceof BigInteger;
    }

    /** isinstance(x, int) in Python (True for bool as well). */
    public static boolean pyIsInt(Object o) {
        return isInt(o) || o instanceof Boolean;
    }

    public static boolean isFloat(Object o) {
        return o instanceof Double || o instanceof Float;
    }

    /** isinstance(x, (int, float)) and not bool. */
    public static boolean isNumber(Object o) {
        return isInt(o) || isFloat(o);
    }

    public static boolean isMap(Object o) { return o instanceof Map; }

    public static boolean isList(Object o) { return o instanceof List; }

    private static BigDecimal toBigDecimal(Object o) {
        if (o instanceof Boolean) return ((Boolean) o).booleanValue() ? BigDecimal.ONE : BigDecimal.ZERO;
        if (o instanceof BigInteger) return new BigDecimal((BigInteger) o);
        if (o instanceof Double || o instanceof Float) return new BigDecimal(((Number) o).doubleValue());
        return new BigDecimal(((Number) o).longValue());
    }

    private static boolean numericLike(Object o) {
        return o instanceof Boolean || o instanceof Number;
    }

    /** Python == for JSON values (True == 1, 1 == 1.0, deep list/dict equality, NaN != NaN). */
    @SuppressWarnings("unchecked")
    public static boolean pyEquals(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (numericLike(a) && numericLike(b)) {
            if (a instanceof Double && Double.isNaN((Double) a)) return false;
            if (b instanceof Double && Double.isNaN((Double) b)) return false;
            if (a instanceof Double && Double.isInfinite((Double) a)) return b instanceof Double && a.equals(b);
            if (b instanceof Double && Double.isInfinite((Double) b)) return false;
            return toBigDecimal(a).compareTo(toBigDecimal(b)) == 0;
        }
        if (a instanceof String && b instanceof String) return a.equals(b);
        if (a instanceof List && b instanceof List) {
            List<Object> x = (List<Object>) a;
            List<Object> y = (List<Object>) b;
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!pyEquals(x.get(i), y.get(i))) return false;
            return true;
        }
        if (a instanceof Map && b instanceof Map) {
            Map<Object, Object> x = (Map<Object, Object>) a;
            Map<Object, Object> y = (Map<Object, Object>) b;
            if (x.size() != y.size()) return false;
            for (Map.Entry<Object, Object> e : x.entrySet()) {
                if (!y.containsKey(e.getKey())) return false;
                if (!pyEquals(e.getValue(), y.get(e.getKey()))) return false;
            }
            return true;
        }
        return false;
    }

    /** Python truthiness of a JSON value. */
    public static boolean truthy(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean) return ((Boolean) o).booleanValue();
        if (o instanceof String) return !((String) o).isEmpty();
        if (o instanceof Double) return ((Double) o).doubleValue() != 0.0;
        if (o instanceof BigInteger) return ((BigInteger) o).signum() != 0;
        if (o instanceof Number) return ((Number) o).longValue() != 0L;
        if (o instanceof List) return !((List<?>) o).isEmpty();
        if (o instanceof Map) return !((Map<?, ?>) o).isEmpty();
        return true;
    }

    /** str(x) for a JSON value. */
    public static String pyStr(Object o) {
        if (o instanceof String) return (String) o;
        return pyRepr(o);
    }

    /** repr(x) for a JSON value (None, True/False, numbers, 'strings', lists, dicts). */
    @SuppressWarnings("unchecked")
    public static String pyRepr(Object o) {
        if (o == null) return "None";
        if (o instanceof Boolean) return ((Boolean) o).booleanValue() ? "True" : "False";
        if (o instanceof Double || o instanceof Float) return floatRepr(((Number) o).doubleValue());
        if (o instanceof Number) return o.toString();
        if (o instanceof String) return pyStrRepr((String) o);
        if (o instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object e : (List<Object>) o) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(pyRepr(e));
            }
            return sb.append(']').toString();
        }
        if (o instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) o).entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(pyRepr(e.getKey())).append(": ").append(pyRepr(e.getValue()));
            }
            return sb.append('}').toString();
        }
        return String.valueOf(o);
    }

    private static String pyStrRepr(String s) {
        boolean hasSingle = s.indexOf('\'') >= 0;
        boolean hasDouble = s.indexOf('"') >= 0;
        char q = (hasSingle && !hasDouble) ? '"' : '\'';
        StringBuilder sb = new StringBuilder();
        sb.append(q);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == q || cp == '\\') { sb.append('\\').appendCodePoint(cp); }
            else if (cp == '\n') sb.append("\\n");
            else if (cp == '\r') sb.append("\\r");
            else if (cp == '\t') sb.append("\\t");
            else if (cp < 0x20 || cp == 0x7f) sb.append(String.format("\\x%02x", cp));
            else if (cp < 0x7f) sb.appendCodePoint(cp);
            else if (isPrintable(cp)) sb.appendCodePoint(cp);
            else if (cp <= 0xff) sb.append(String.format("\\x%02x", cp));
            else if (cp <= 0xffff) sb.append(String.format("\\u%04x", cp));
            else sb.append(String.format("\\U%08x", cp));
        }
        return sb.append(q).toString();
    }

    private static boolean isPrintable(int cp) {
        int t = Character.getType(cp);
        switch (t) {
            case Character.CONTROL: case Character.FORMAT: case Character.SURROGATE:
            case Character.PRIVATE_USE: case Character.UNASSIGNED:
            case Character.LINE_SEPARATOR: case Character.PARAGRAPH_SEPARATOR:
                return false;
            case Character.SPACE_SEPARATOR:
                return cp == ' ';
            default:
                return true;
        }
    }

    /** Deep copy of a JSON value tree (copy.deepcopy). */
    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object o) { return deepCopy(o, 0); }

    @SuppressWarnings("unchecked")
    private static Object deepCopy(Object o, int depth) {
        if (depth > MAX_DEPTH) throw new JsonRecursionError("maximum recursion depth exceeded in deepcopy");
        if (o instanceof List) {
            List<Object> src = (List<Object>) o;
            List<Object> out = new ArrayList<Object>(src.size());
            for (Object e : src) out.add(deepCopy(e, depth + 1));
            return out;
        }
        if (o instanceof Map) {
            Map<Object, Object> src = (Map<Object, Object>) o;
            Map<Object, Object> out = new LinkedHashMap<Object, Object>();
            for (Map.Entry<Object, Object> e : src.entrySet()) out.put(e.getKey(), deepCopy(e.getValue(), depth + 1));
            return out;
        }
        return o;
    }

    // ------------------------------------------------------------------ typed accessors

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    /** New empty JSON object (insertion ordered, like a Python dict). */
    public static Map<String, Object> obj() { return new LinkedHashMap<String, Object>(); }

    /** New JSON array from the given elements. */
    public static List<Object> arr(Object... items) {
        List<Object> l = new ArrayList<Object>(items.length);
        for (Object i : items) l.add(i);
        return l;
    }

    /** Normalizes any Java Number to the canonical JSON model (Long / BigInteger / Double). */
    public static Object norm(Object o) {
        if (o instanceof Integer || o instanceof Short || o instanceof Byte) return Long.valueOf(((Number) o).longValue());
        if (o instanceof Float) return Double.valueOf(((Number) o).doubleValue());
        return o;
    }
}
