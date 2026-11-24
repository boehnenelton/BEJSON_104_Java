/*
 * File Name:       BejsonCore.java
 * Relative Path:   src/main/java/com/boehnenelton/bejson/core/BejsonCore.java
 * Description:     Port of lib_bejson_Core_bejson_core.py 2.0.10: format-level primitives - atomic write, file/string
 *                  loading, freshness-guarded field-map cache, and document factories for 104, 104a and 104db.
 *                  Documents are Map&lt;String,Object&gt; (see Json); Values rows are List&lt;Object&gt;.
 * Format_Scope:    104 | 104a | 104db (generic)
 * VERSION:         1.0.0 (reference 2.0.10)
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   787a24ad-6d74-4797-aab8-8cf95bad96db
 */
package com.boehnenelton.bejson.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BejsonCore {
    private BejsonCore() { }

    public static final String VERSION = "2.0.10";
    private static final Logger LOG = Logger.getLogger("lib_bejson_Core_bejson_core");

    /** In-memory field-map cache key; never read back from disk or from a string. */
    public static final String FIELD_MAP_CACHE_KEY = "_bejson_field_map";

    /** Provenance marker: only instances built by getFieldMap pass the freshness guard. */
    public static final class FieldMap extends LinkedHashMap<String, Integer> {
        private static final long serialVersionUID = 1L;

        public FieldMap() { super(); }

        public FieldMap(Map<String, Integer> src) { super(src); }
    }

    /** Discards a persisted field-map cache from a freshly loaded document (untrusted input). */
    public static Object stripPersistedCache(Object doc) {
        if (doc instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) doc;
            if (m.get(FIELD_MAP_CACHE_KEY) != null) {
                m.remove(FIELD_MAP_CACHE_KEY);
                LOG.log(Level.WARNING, "[BEJSON_CORE] Discarded persisted {0} (untrusted cache)", FIELD_MAP_CACHE_KEY);
            } else if (m.containsKey(FIELD_MAP_CACHE_KEY)) {
                m.remove(FIELD_MAP_CACHE_KEY);
            }
        }
        return doc;
    }

    /** Loads a BEJSON file (resolves path placeholders). Returns null if missing, unparseable or not a JSON object. */
    public static Map<String, Object> loadFile(String path) {
        path = BejsonEnv.resolvePath(path);
        if (path == null || path.isEmpty()) return null;
        try {
            Object doc = stripPersistedCache(Json.loads(PyFs.readText(path)));
            return Json.asMap(doc);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "[BEJSON_CORE] Failed to load {0}: {1}", new Object[] {path, e.getMessage()});
            return null;
        }
    }

    /** Writes a document atomically (temp file, fsync, rename); keys starting with '_' are stripped. */
    public static boolean atomicWrite(String path, Map<String, Object> data) {
        String targetDir = PyFs.dirname(PyFs.abspath(path));
        PyFs.makedirs(targetDir);
        Map<String, Object> clean = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (!e.getKey().startsWith("_")) clean.put(e.getKey(), e.getValue());
        }
        try {
            byte[] bytes = Json.dumps(clean, 2, true).getBytes(StandardCharsets.UTF_8);
            PyFs.atomicWriteBytes(path, bytes, "tmp", ".tmp");
            return true;
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "[BEJSON_CORE] Atomic write failed for {0}: {1}", new Object[] {path, e.getMessage()});
            return false;
        }
    }

    /** Simple directory lock "<path>.lock"; true when acquired within timeout seconds. */
    public static boolean acquireLock(String filePath, int timeout) {
        File lock = new File(filePath + ".lock");
        long start = System.nanoTime();
        while ((System.nanoTime() - start) / 1e9 < timeout) {
            if (lock.mkdir()) return true;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public static boolean acquireLock(String filePath) { return acquireLock(filePath, 10); }

    /** Releases the simple directory lock. */
    public static void releaseLock(String filePath) {
        new File(filePath + ".lock").delete();
    }

    // ------------------------------------------------------------ field map

    private static final Map<List<Object>, Map<String, Integer>> FIELD_MAP_CACHE =
            Collections.synchronizedMap(new java.util.HashMap<List<Object>, Map<String, Integer>>());

    /** Name of a Fields entry: the map's "name", or str() of a non-map entry. */
    static String fieldName(Object fieldDef) {
        if (fieldDef instanceof Map) {
            Object n = ((Map<?, ?>) fieldDef).get("name");
            if (n == null) return null;
            return n instanceof String ? (String) n : Json.pyStr(n);
        }
        return Json.pyStr(fieldDef);
    }

    /** O(1) freshness guard for an in-document cache (size equals len(Fields); first/mid/last resolve correctly). */
    public static boolean fieldMapIsFresh(Object cachedMap, Object fields) {
        if (cachedMap == null || cachedMap.getClass() != FieldMap.class || !(fields instanceof List)) return false;
        List<?> f = (List<?>) fields;
        FieldMap cm = (FieldMap) cachedMap;
        int count = f.size();
        if (cm.size() != count) return false;
        if (count == 0) return true;
        int last = count - 1;
        int mid = count / 2;
        return Integer.valueOf(0).equals(cm.get(fieldName(f.get(0))))
                && Integer.valueOf(last).equals(cm.get(fieldName(f.get(last))))
                && Integer.valueOf(mid).equals(cm.get(fieldName(f.get(mid))));
    }

    /** Returns {field_name: column_index}, built once from Fields and cached on the document. */
    @SuppressWarnings("unchecked")
    public static Map<String, Integer> getFieldMap(Map<String, Object> doc) {
        Object fieldsObj = doc.containsKey("Fields") ? doc.get("Fields") : new ArrayList<Object>();
        Object cached = doc.get(FIELD_MAP_CACHE_KEY);
        if (cached != null) {
            if (fieldMapIsFresh(cached, fieldsObj)) return (Map<String, Integer>) cached;
            doc.remove(FIELD_MAP_CACHE_KEY);
        }
        if (!(fieldsObj instanceof List) || ((List<?>) fieldsObj).isEmpty()) return new LinkedHashMap<String, Integer>();
        List<Object> fields = (List<Object>) fieldsObj;
        List<Object> key = new ArrayList<Object>(fields.size() + 1);
        key.add(doc.get("Format_Version"));
        List<String> names = new ArrayList<String>(fields.size());
        for (Object f : fields) {
            String n = fieldName(f);
            names.add(n);
            key.add(n);
        }
        Map<String, Integer> shared = FIELD_MAP_CACHE.get(key);
        if (shared == null) {
            shared = new LinkedHashMap<String, Integer>();
            for (int i = 0; i < names.size(); i++) shared.put(names.get(i), Integer.valueOf(i));
            FIELD_MAP_CACHE.put(key, shared);
        }
        FieldMap fm = new FieldMap(shared);
        try {
            doc.put(FIELD_MAP_CACHE_KEY, fm);
        } catch (RuntimeException ignored) {
            // immutable document
        }
        return fm;
    }

    /** Column index of one field name via the field-map cache; -1 if absent. */
    public static int getFieldIndex(Map<String, Object> doc, String fieldName) {
        Integer i = getFieldMap(doc).get(fieldName);
        return i == null ? -1 : i.intValue();
    }

    // ------------------------------------------------------------ factories

    private static Map<String, Object> baseDoc(String version, List<Object> recordsType, List<Object> fields, List<Object> values) {
        Map<String, Object> doc = new LinkedHashMap<String, Object>();
        doc.put("Format", "BEJSON");
        doc.put("Format_Version", version);
        doc.put("Format_Creator", "Elton Boehnen");
        doc.put("Records_Type", recordsType);
        doc.put("Fields", fields);
        doc.put("Values", values);
        return doc;
    }

    /** New BEJSON 104 document: exactly one record type, no custom keys. */
    public static Map<String, Object> create104(String recordType, List<Object> fields, List<Object> values) {
        return baseDoc("104", Json.arr(recordType), fields, values);
    }

    /** New BEJSON 104a document with optional custom top-level header keys. */
    public static Map<String, Object> create104a(String recordType, List<Object> fields, List<Object> values,
                                                 Map<String, Object> custom) {
        Map<String, Object> doc = baseDoc("104a", Json.arr(recordType), fields, values);
        if (custom != null) doc.putAll(custom);
        return doc;
    }

    public static Map<String, Object> create104a(String recordType, List<Object> fields, List<Object> values) {
        return create104a(recordType, fields, values, null);
    }

    /** New BEJSON 104db document (one file, 2+ record types). */
    public static Map<String, Object> create104db(List<Object> recordTypes, List<Object> fields, List<Object> values) {
        return baseDoc("104db", recordTypes, fields, values);
    }

    // ------------------------------------------------------------ format-level helpers

    /** Parses a JSON string into a document; null on error (logged). */
    public static Map<String, Object> loadString(String content) {
        try {
            return Json.asMap(stripPersistedCache(Json.loads(content)));
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "[BEJSON_CORE] Failed to load JSON string: {0}", e.getMessage());
            return null;
        }
    }

    public static int getRecordCount(Map<String, Object> doc) {
        List<Object> v = Json.asList(doc.get("Values"));
        return v == null ? 0 : v.size();
    }

    /** Appends a row if its length equals the field count. */
    public static boolean addRecord(Map<String, Object> doc, List<Object> record) {
        List<Object> f = Json.asList(doc.get("Fields"));
        int fc = f == null ? 0 : f.size();
        if (record.size() != fc) return false;
        List<Object> values = Json.asList(doc.get("Values"));
        if (values == null) {
            values = new ArrayList<Object>();
            doc.put("Values", values);
        }
        values.add(record);
        return true;
    }

    /** Removes the row at index; false when out of range. */
    public static boolean removeRecord(Map<String, Object> doc, int index) {
        List<Object> values = Json.asList(doc.get("Values"));
        if (values != null && index >= 0 && index < values.size()) {
            values.remove(index);
            return true;
        }
        return false;
    }

    /** Sets one cell by (row_index, field_name). */
    public static boolean updateField(Map<String, Object> doc, int rowIndex, String fieldName, Object value) {
        int idx = getFieldIndex(doc, fieldName);
        if (idx == -1) return false;
        List<Object> values = Json.asList(doc.get("Values"));
        if (values != null && rowIndex >= 0 && rowIndex < values.size()) {
            Json.asList(values.get(rowIndex)).set(idx, value);
            return true;
        }
        return false;
    }

    /** Rows where field_name equals value (Python == semantics); empty list if the field is unknown. */
    public static List<Object> filterRows(Map<String, Object> doc, String fieldName, Object value) {
        List<Object> out = new ArrayList<Object>();
        int idx = getFieldIndex(doc, fieldName);
        if (idx == -1) return out;
        List<Object> values = Json.asList(doc.get("Values"));
        if (values == null) return out;
        for (Object row : values) {
            if (Json.pyEquals(Json.asList(row).get(idx), value)) out.add(row);
        }
        return out;
    }

    /** Sorts Values in place by one field (null sorts as empty string); stable. */
    public static void sortByField(Map<String, Object> doc, String fieldName, boolean reverse) {
        final int idx = getFieldIndex(doc, fieldName);
        if (idx == -1) return;
        List<Object> values = Json.asList(doc.get("Values"));
        final Comparator<Object> cmp = PyOps.comparator();
        Collections.sort(values, new Comparator<Object>() {
            @Override
            public int compare(Object a, Object b) {
                Object x = Json.asList(a).get(idx);
                Object y = Json.asList(b).get(idx);
                return cmp.compare(x == null ? "" : x, y == null ? "" : y);
            }
        });
        if (reverse) {
            // Python reverse=True keeps equal elements in original order: re-sort stably with inverted comparator.
            Collections.sort(values, new Comparator<Object>() {
                @Override
                public int compare(Object a, Object b) {
                    Object x = Json.asList(a).get(idx);
                    Object y = Json.asList(b).get(idx);
                    return -cmp.compare(x == null ? "" : x, y == null ? "" : y);
                }
            });
        }
    }

    public static void sortByField(Map<String, Object> doc, String fieldName) { sortByField(doc, fieldName, false); }

    /** Cheap presence check: all six mandatory top-level keys exist. */
    public static boolean isValid(Map<String, Object> doc) {
        String[] req = {"Format", "Format_Version", "Format_Creator", "Records_Type", "Fields", "Values"};
        for (String k : req) if (!doc.containsKey(k)) return false;
        return true;
    }

    /** Format_Version or "unknown". */
    public static String getVersion(Map<String, Object> doc) {
        Object v = doc.get("Format_Version");
        if (!doc.containsKey("Format_Version")) return "unknown";
        return v instanceof String ? (String) v : Json.pyStr(v);
    }

    /** {record_count, field_count, version}. */
    public static Map<String, Object> getStats(Map<String, Object> doc) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("record_count", Long.valueOf(getRecordCount(doc)));
        List<Object> f = Json.asList(doc.get("Fields"));
        m.put("field_count", Long.valueOf(f == null ? 0 : f.size()));
        m.put("version", getVersion(doc));
        return m;
    }
}
