/*
 * File Name:       PyOps.java
 * Relative Path:   src/main/java/com/boehnenelton/bejson/core/PyOps.java
 * Description:     Small Python-semantics helpers for the Core port: str.strip(), Unicode isalnum, ordering of JSON
 *                  values (the comparison used by list.sort / sorted), and timestamp formatting.
 * VERSION:         1.0.0
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   30edd3ec-c460-4c23-9a2b-0fac4eeeb266
 */
package com.boehnenelton.bejson.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;

public final class PyOps {

    private PyOps() { }

    /** Python str.isspace() for one char. */
    public static boolean isSpace(char c) {
        return Character.isWhitespace(c) || c == 0xA0 || c == 0x85 || c == 0x2007 || c == 0x202F
                || Character.isSpaceChar(c);
    }

    /** str.strip() (no args). */
    public static String strip(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && isSpace(s.charAt(a))) a++;
        while (b > a && isSpace(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    /** str.strip(chars). */
    public static String strip(String s, String chars) {
        int a = 0;
        int b = s.length();
        while (a < b && chars.indexOf(s.charAt(a)) >= 0) a++;
        while (b > a && chars.indexOf(s.charAt(b - 1)) >= 0) b--;
        return s.substring(a, b);
    }

    /** str.isalnum(). */
    public static boolean isAlnum(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetterOrDigit(cp)) return false;
        }
        return true;
    }

    /** str.isalnum() for a single code point string char. */
    public static boolean isAlnumChar(int cp) {
        return Character.isLetterOrDigit(cp);
    }

    /** Python str.lower() (locale independent). */
    public static String lower(String s) {
        return s.toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isNum(Object o) {
        return o instanceof Number || o instanceof Boolean;
    }

    private static BigDecimal dec(Object o) {
        if (o instanceof Boolean) return ((Boolean) o).booleanValue() ? BigDecimal.ONE : BigDecimal.ZERO;
        if (o instanceof BigInteger) return new BigDecimal((BigInteger) o);
        if (o instanceof Double || o instanceof Float) return new BigDecimal(((Number) o).doubleValue());
        return new BigDecimal(((Number) o).longValue());
    }

    /**
     * Python ordering (a &lt; b) for JSON values: numbers with numbers (bool counts as int), str with str (by code
     * point), list with list (lexicographic). Anything else raises like Python's TypeError.
     */
    @SuppressWarnings("unchecked")
    public static int compare(Object a, Object b) {
        if (isNum(a) && isNum(b)) {
            boolean an = a instanceof Double && ((Double) a).isNaN();
            boolean bn = b instanceof Double && ((Double) b).isNaN();
            if (an || bn) return 0;
            if (a instanceof Double && ((Double) a).isInfinite()) {
                if (b instanceof Double && ((Double) b).isInfinite()) return Double.compare((Double) a, (Double) b);
                return ((Double) a) > 0 ? 1 : -1;
            }
            if (b instanceof Double && ((Double) b).isInfinite()) return ((Double) b) > 0 ? -1 : 1;
            return dec(a).compareTo(dec(b));
        }
        if (a instanceof String && b instanceof String) {
            String x = (String) a;
            String y = (String) b;
            int i = 0;
            int j = 0;
            while (i < x.length() && j < y.length()) {
                int cx = x.codePointAt(i);
                int cy = y.codePointAt(j);
                if (cx != cy) return cx < cy ? -1 : 1;
                i += Character.charCount(cx);
                j += Character.charCount(cy);
            }
            if (i < x.length()) return 1;
            if (j < y.length()) return -1;
            return 0;
        }
        if (a instanceof List && b instanceof List) {
            List<Object> x = (List<Object>) a;
            List<Object> y = (List<Object>) b;
            int n = Math.min(x.size(), y.size());
            for (int i = 0; i < n; i++) {
                if (!Json.pyEquals(x.get(i), y.get(i))) return compare(x.get(i), y.get(i));
            }
            return Integer.compare(x.size(), y.size());
        }
        throw new IllegalArgumentException("'<' not supported between instances of '" + typeName(a) + "' and '" + typeName(b) + "'");
    }

    public static String typeName(Object o) {
        if (o == null) return "NoneType";
        if (o instanceof Boolean) return "bool";
        if (o instanceof Double || o instanceof Float) return "float";
        if (o instanceof Number) return "int";
        if (o instanceof String) return "str";
        if (o instanceof List) return "list";
        if (o instanceof java.util.Map) return "dict";
        return o.getClass().getSimpleName();
    }

    /** Comparator implementing Python's ordering; stable under Collections.sort / List.sort. */
    public static Comparator<Object> comparator() {
        return new Comparator<Object>() {
            @Override
            public int compare(Object a, Object b) {
                return PyOps.compare(a, b);
            }
        };
    }

    // ------------------------------------------------------------------ Python duck-typing emulation
    // The validators run on untrusted JSON where any value may have any type; Python reports type confusion as TypeError /
    // AttributeError / KeyError, which the libraries surface as "Unexpected validation error: <message>". These helpers
    // reproduce those operations and messages so malformed documents are judged identically.

    /** Python iteration: list items, dict keys, str characters; otherwise TypeError. */
    public static List<Object> iter(Object o) {
        if (o instanceof List) {
            @SuppressWarnings("unchecked") List<Object> l = (List<Object>) o;
            return l;
        }
        if (o instanceof java.util.Map) return new java.util.ArrayList<Object>(((java.util.Map<?, ?>) o).keySet());
        if (o instanceof String) {
            String str = (String) o;
            List<Object> out = new java.util.ArrayList<Object>();
            for (int i = 0; i < str.length(); ) { int cp = str.codePointAt(i); out.add(new String(Character.toChars(cp))); i += Character.charCount(cp); }
            return out;
        }
        throw new IllegalStateException("'" + typeName(o) + "' object is not iterable");
    }

    /** Python len(). */
    public static int len(Object o) {
        if (o instanceof List) return ((List<?>) o).size();
        if (o instanceof java.util.Map) return ((java.util.Map<?, ?>) o).size();
        if (o instanceof String) { String str = (String) o; return str.codePointCount(0, str.length()); }
        throw new IllegalStateException("object of type '" + typeName(o) + "' has no len()");
    }

    /** Python hash(): dict and list are unhashable. */
    public static void hashable(Object o) {
        if (o instanceof java.util.Map || o instanceof List) throw new IllegalStateException("unhashable type: '" + typeName(o) + "'");
    }

    /** obj.get(key) as used on dict-like values; other types raise AttributeError. */
    public static Object get(Object o, String key) {
        if (o instanceof java.util.Map) return ((java.util.Map<?, ?>) o).get(key);
        throw new IllegalStateException("'" + typeName(o) + "' object has no attribute 'get'");
    }

    /** obj["key"] with a string key: dict lookup (KeyError), otherwise Python's TypeError wording. */
    public static Object subKey(Object o, String key) {
        if (o instanceof java.util.Map) {
            java.util.Map<?, ?> m = (java.util.Map<?, ?>) o;
            if (!m.containsKey(key)) throw new IllegalStateException("'" + key + "'");
            return m.get(key);
        }
        if (o instanceof String) throw new IllegalStateException("string indices must be integers, not 'str'");
        if (o instanceof List) throw new IllegalStateException("list indices must be integers or slices, not str");
        throw new IllegalStateException("'" + typeName(o) + "' object is not subscriptable");
    }

    /** obj[i] for an int index. */
    public static Object index(Object o, int i) {
        if (o instanceof List) {
            List<?> l = (List<?>) o;
            int k = i < 0 ? l.size() + i : i;
            if (k < 0 || k >= l.size()) throw new IllegalStateException("list index out of range");
            return l.get(k);
        }
        if (o instanceof String) {
            List<Object> chars = iter(o);
            int k = i < 0 ? chars.size() + i : i;
            if (k < 0 || k >= chars.size()) throw new IllegalStateException("string index out of range");
            return chars.get(k);
        }
        if (o instanceof java.util.Map) throw new IllegalStateException(String.valueOf(i));
        throw new IllegalStateException("'" + typeName(o) + "' object is not subscriptable");
    }

    /** Membership in a Python set built from items: hashes the probe (TypeError for dict/list) then compares with ==. */
    public static boolean inSet(Object item, Iterable<Object> members) {
        hashable(item);
        for (Object m : members) if (Json.pyEquals(m, item)) return true;
        return false;
    }

    /** Canonical key reproducing Python hash-equality for scalars (1 == 1.0 == True). */
    public static String hashKey(Object o) {
        hashable(o);
        if (o == null) return "N";
        if (o instanceof Boolean) return "n:" + (((Boolean) o).booleanValue() ? "1" : "0");
        if (o instanceof Number) {
            if (o instanceof Double || o instanceof Float) {
                double d = ((Number) o).doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) return "f:" + d;
                return "n:" + new java.math.BigDecimal(d).stripTrailingZeros().toPlainString();
            }
            return "n:" + new java.math.BigDecimal(o.toString()).stripTrailingZeros().toPlainString();
        }
        return "s:" + o;
    }
}
