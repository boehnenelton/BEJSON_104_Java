/*
 * File Name:       BejsonCoreNesting.java
 * Relative Path:   src/main/java/com/boehnenelton/bejson/core/BejsonCoreNesting.java
 * Description:     Port of lib_bejson_Core_bejson_core_nesting.py 0.6.0: nested BEJSON 104 (104 embedded in a string
 *                  cell of another 104) - scan, mutate, flatten, summary, NestMap cache, and dotted-path queries.
 *                  104 ONLY. Error codes 130-139 come from BejsonErrors.
 * Format_Scope:    Nested 104 (NOT 104db, NOT MFDB)
 * Deviations:      * Python ValueError maps to IllegalArgumentException.
 *                  * The path-segment regex is hand-matched (no java.util.regex) with identical semantics, including
 *                    Unicode decimal digits for [n] and Python's "$ also matches before one trailing \n".
 *                  * A non-string Fields[i]["name"] is rendered with str() when used as a field name.
 *                  * Negative row/col in mutate() is reported as E130 (Python would index from the end).
 * VERSION:         1.0.0 (reference 0.6.0)
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   2218556e-b48b-495e-b42d-d3feb518c7c2
 */
package com.boehnenelton.bejson.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BejsonCoreNesting {
    private BejsonCoreNesting() { }

    public static final String VERSION = "0.6.0";
    public static final int NESTING_MAX_DEPTH = 16;
    private static final String VALID_VERSION = "104";
    private static final Logger LOG = Logger.getLogger("lib_bejson_Core_bejson_core_nesting");

    private static final String[] MANDATORY_KEYS =
            {"Format", "Format_Version", "Format_Creator", "Records_Type", "Fields", "Values"};

    // ------------------------------------------------------------------ data classes

    /** Canonical 4-tuple address (parent_fingerprint, row, col, depth); location is identity. */
    public static final class NestAddress {
        public final String parentFingerprint;
        public final int row;
        public final int col;
        public final int depth;

        public NestAddress(String parentFingerprint, int row, int col, int depth) {
            this.parentFingerprint = parentFingerprint;
            this.row = row;
            this.col = col;
            this.depth = depth;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof NestAddress)) return false;
            NestAddress a = (NestAddress) o;
            return row == a.row && col == a.col && depth == a.depth
                    && (parentFingerprint == null ? a.parentFingerprint == null : parentFingerprint.equals(a.parentFingerprint));
        }

        @Override
        public int hashCode() {
            int h = parentFingerprint == null ? 0 : parentFingerprint.hashCode();
            h = 31 * h + row;
            h = 31 * h + col;
            h = 31 * h + depth;
            return h;
        }

        @Override
        public String toString() {
            return "NestAddress(" + parentFingerprint + ", " + row + ", " + col + ", " + depth + ")";
        }
    }

    /** One cell holding a detected, parsed nested 104 document (doc is a live reference). */
    public static final class NestedCell {
        public final int row;
        public final int col;
        public final String fieldName;
        public final int depth;
        public final Map<String, Object> doc;
        public final boolean isValid;
        public final List<String> errors;
        public final List<String> warnings;
        public final List<NestedCell> children = new ArrayList<NestedCell>();

        public NestedCell(int row, int col, String fieldName, int depth, Map<String, Object> doc,
                          boolean isValid, List<String> errors, List<String> warnings) {
            this.row = row;
            this.col = col;
            this.fieldName = fieldName;
            this.depth = depth;
            this.doc = doc;
            this.isValid = isValid;
            this.errors = errors;
            this.warnings = warnings;
        }

        /** Partial address (parent fingerprint is unknown here, as in the Python property). */
        public NestAddress address() {
            return new NestAddress("", row, col, depth);
        }
    }

    /** Returned by scan(). */
    public static final class NestingResult {
        public int scannedCells;
        public int nestedFound;
        public int nestedValid;
        public int nestedInvalid;
        public int maxDepthSeen;
        public int schemaErrors;
        public final List<NestedCell> cells = new ArrayList<NestedCell>();
        public final List<String> errors = new ArrayList<String>();
        public final List<String> warnings = new ArrayList<String>();
    }

    /** One matched cell at the end of a resolved path. */
    public static final class QueryMatch {
        public final String path;
        public final Object value;
        public final List<String> fieldChain;
        public final List<Integer> rowChain;
        public final List<Integer> colChain;

        public QueryMatch(String path, Object value, List<String> fieldChain, List<Integer> rowChain, List<Integer> colChain) {
            this.path = path;
            this.value = value;
            this.fieldChain = fieldChain;
            this.rowChain = rowChain;
            this.colChain = colChain;
        }
    }

    /** Callback applied by mutate() to the nested document. */
    public interface Mutation {
        void apply(Map<String, Object> doc);
    }

    // ------------------------------------------------------------------ NestMap cache

    private static final Object CACHE_LOCK = new Object();
    private static Map<NestAddress, NestedCell> nestMap = new LinkedHashMap<NestAddress, NestedCell>();

    /** O(1) NestMap lookup by full 4-tuple address; null on miss. */
    public static synchronized NestedCell cacheGet(String parentFingerprint, int row, int col, int depth) {
        synchronized (CACHE_LOCK) {
            return nestMap.get(new NestAddress(parentFingerprint, row, col, depth));
        }
    }

    /** Insert or overwrite a NestMap entry. */
    public static synchronized void cachePut(String parentFingerprint, int row, int col, int depth, NestedCell cell) {
        synchronized (CACHE_LOCK) {
            nestMap.put(new NestAddress(parentFingerprint, row, col, depth), cell);
        }
    }

    /** Clears all entries (parentFingerprint == null) or only one parent's entries; returns the number removed. */
    public static synchronized int cacheClear(String parentFingerprint) {
        synchronized (CACHE_LOCK) {
            if (parentFingerprint == null) {
                int removed = nestMap.size();
                nestMap = new LinkedHashMap<NestAddress, NestedCell>();
                return removed;
            }
            List<NestAddress> matching = new ArrayList<NestAddress>();
            for (NestAddress k : nestMap.keySet()) {
                if (parentFingerprint.equals(k.parentFingerprint)) matching.add(k);
            }
            for (NestAddress k : matching) nestMap.remove(k);
            return matching.size();
        }
    }

    public static int cacheClear() { return cacheClear(null); }

    /** Diagnostic snapshot: total_entries, unique_parents, depth_spread. */
    public static synchronized Map<String, Object> cacheStats() {
        synchronized (CACHE_LOCK) {
            Set<String> parents = new HashSet<String>();
            TreeSet<Integer> depths = new TreeSet<Integer>();
            for (NestAddress k : nestMap.keySet()) {
                parents.add(k.parentFingerprint);
                depths.add(Integer.valueOf(k.depth));
            }
            List<Object> spread = new ArrayList<Object>();
            for (Integer d : depths) spread.add(Long.valueOf(d.longValue()));
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("total_entries", Long.valueOf(nestMap.size()));
            m.put("unique_parents", Long.valueOf(parents.size()));
            m.put("depth_spread", spread);
            return m;
        }
    }

    /** All cached cells for a parent document and column (all rows and depths). */
    public static synchronized List<NestedCell> cacheLookup(String parentFingerprint, int col) {
        synchronized (CACHE_LOCK) {
            List<NestedCell> out = new ArrayList<NestedCell>();
            for (Map.Entry<NestAddress, NestedCell> e : nestMap.entrySet()) {
                NestAddress k = e.getKey();
                if (parentFingerprint.equals(k.parentFingerprint) && k.col == col) out.add(e.getValue());
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ internal helpers

    /** RELATIONAL_ID if present, else a stable hash of Fields and Values (content, not just schema). */
    static String docFingerprint(Map<String, Object> doc) {
        Object rid = doc.get("RELATIONAL_ID");
        if (!Json.truthy(rid)) rid = doc.get("relational_id");
        if (Json.truthy(rid)) return Json.pyStr(rid);
        List<Object> pair = new ArrayList<Object>();
        pair.add(doc.containsKey("Fields") ? doc.get("Fields") : new ArrayList<Object>());
        pair.add(doc.containsKey("Values") ? doc.get("Values") : new ArrayList<Object>());
        String contentRepr = Json.dumps(pair, -1, false, true);
        String hex = PyFs.sha256Hex(contentRepr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "hash:" + hex.substring(0, 32);
    }

    /** Fast pre-filter: string that trims to {...}. */
    static boolean isCandidate(Object value) {
        if (!(value instanceof String)) return false;
        String t = PyOps.strip((String) value);
        return t.startsWith("{") && t.endsWith("}");
    }

    /** json.loads of a candidate cell; the map, or null when not valid JSON object text. */
    static Map<String, Object> parseCell(String rawCellText) {
        try {
            return Json.asMap(Json.loads(rawCellText));
        } catch (Json.JsonParseException e) {
            return null;
        }
    }

    /** Canonical string of a Fields array (sorted keys, default separators, ensure_ascii). */
    static String fieldsSignature(Map<String, Object> doc) {
        Object f = doc.containsKey("Fields") ? doc.get("Fields") : new ArrayList<Object>();
        return Json.dumps(f, -1, true, true);
    }

    private static final class QuickResult {
        final boolean ok;
        final List<String> errors;

        QuickResult(boolean ok, List<String> errors) {
            this.ok = ok;
            this.errors = errors;
        }
    }

    /** Strict BEJSON 104 validation; non-BEJSON / non-104 is (false, []) and skipped silently. */
    private static QuickResult quickValidate104(Object docObj) {
        if (!(docObj instanceof Map)) {
            List<String> e = new ArrayList<String>();
            e.add("E" + BejsonErrors.E_NESTING_NOT_BEJSON + ": not a dict");
            return new QuickResult(false, e);
        }
        Map<String, Object> doc = Json.asMap(docObj);
        for (String k : MANDATORY_KEYS) {
            if (!doc.containsKey(k)) return new QuickResult(false, new ArrayList<String>());
        }
        if (!"BEJSON".equals(doc.get("Format"))) return new QuickResult(false, new ArrayList<String>());
        if (!VALID_VERSION.equals(doc.get("Format_Version"))) return new QuickResult(false, new ArrayList<String>());
        BejsonValidator.ValidationResult core = BejsonValidator.validateBejson(doc, false);
        List<String> errs = new ArrayList<String>();
        for (String m : core.errors) errs.add("E" + BejsonErrors.E_NESTING_VALIDATION_FAILED + ": " + m);
        return new QuickResult(errs.isEmpty(), errs);
    }

    /** Warning if Records_Type does not match the PascalCase of the parent field name, else null. */
    private static String checkRecordsTypeConvention(Map<String, Object> nestedDoc, String parentFieldName) {
        Object rt = nestedDoc.containsKey("Records_Type") ? nestedDoc.get("Records_Type") : new ArrayList<Object>();
        if (!Json.truthy(rt)) return null;
        String expected = "";
        if (parentFieldName != null && !parentFieldName.isEmpty()) {
            int first = parentFieldName.offsetByCodePoints(0, 1);
            expected = parentFieldName.substring(0, first).toUpperCase(java.util.Locale.ROOT) + parentFieldName.substring(first);
        }
        Object actual = Json.asList(rt).get(0);
        if (!Json.pyEquals(actual, expected)) {
            return "W: Records_Type '" + Json.pyStr(actual) + "' in nested doc at col '" + parentFieldName
                    + "' should be '" + expected + "' by convention";
        }
        return null;
    }

    // ------------------------------------------------------------------ recursive walk

    private static NestedCell walkCell(Object value, int row, int col, String fieldName, int depth,
                                       Set<String> seenFingerprints, String parentFingerprint,
                                       Map<Integer, String> colSchemas) {
        if (depth > NESTING_MAX_DEPTH) {
            LOG.log(Level.WARNING, "[NESTING] depth cap {0} hit at row={1} col={2}",
                    new Object[] {Integer.valueOf(NESTING_MAX_DEPTH), Integer.valueOf(row), Integer.valueOf(col)});
            return null;
        }
        if (!isCandidate(value)) return null;

        NestedCell cached = cacheGet(parentFingerprint, row, col, depth);
        if (cached != null) return cached;

        Map<String, Object> parsedDoc = parseCell((String) value);
        if (parsedDoc == null) return null;

        QuickResult qr = quickValidate104(parsedDoc);
        boolean isValid = qr.ok;
        List<String> cellErrors = qr.errors;
        if (!isValid && cellErrors.isEmpty()) return null;

        List<String> cellWarnings = new ArrayList<String>();

        if (isValid) {
            String sig = fieldsSignature(parsedDoc);
            Integer colKey = Integer.valueOf(col);
            if (!colSchemas.containsKey(colKey)) {
                colSchemas.put(colKey, sig);
            } else if (!colSchemas.get(colKey).equals(sig)) {
                cellErrors.add("E" + BejsonErrors.E_NESTING_SCHEMA_MISMATCH + ": nested doc at row=" + row + " col=" + col
                        + " has different Fields schema than other docs in this column");
                isValid = false;
            }
        }

        if (isValid) BejsonCore.getFieldMap(parsedDoc);

        if (isValid) {
            String w = checkRecordsTypeConvention(parsedDoc, fieldName);
            if (w != null) cellWarnings.add(w);
        }

        String nestedFingerprint = docFingerprint(parsedDoc);

        if (seenFingerprints.contains(nestedFingerprint)) {
            List<String> errs = new ArrayList<String>();
            errs.add("E" + BejsonErrors.E_NESTING_CIRCULAR_REF + ": circular reference at depth " + depth);
            NestedCell circular = new NestedCell(row, col, fieldName, depth, parsedDoc, false, errs, cellWarnings);
            cachePut(parentFingerprint, row, col, depth, circular);
            return circular;
        }

        NestedCell resolved = new NestedCell(row, col, fieldName, depth, parsedDoc, isValid, cellErrors, cellWarnings);

        if (isValid) {
            Set<String> childSeen = new HashSet<String>(seenFingerprints);
            childSeen.add(nestedFingerprint);
            Map<Integer, String> childColSchemas = new LinkedHashMap<Integer, String>();
            Map<String, Integer> childFieldMap = BejsonCore.getFieldMap(parsedDoc);
            Map<Integer, String> nameByIndex = new LinkedHashMap<Integer, String>();
            for (Map.Entry<String, Integer> e : childFieldMap.entrySet()) nameByIndex.put(e.getValue(), e.getKey());
            List<Object> childValues = Json.asList(parsedDoc.containsKey("Values") ? parsedDoc.get("Values") : new ArrayList<Object>());
            if (childValues == null) childValues = new ArrayList<Object>();
            for (int cr = 0; cr < childValues.size(); cr++) {
                List<Object> childRow = Json.asList(childValues.get(cr));
                if (childRow == null) continue;
                for (int cc = 0; cc < childRow.size(); cc++) {
                    String childName = nameByIndex.get(Integer.valueOf(cc));
                    if (childName == null) childName = "col_" + cc;
                    NestedCell child = walkCell(childRow.get(cc), cr, cc, childName, depth + 1,
                            childSeen, nestedFingerprint, childColSchemas);
                    if (child != null) resolved.children.add(child);
                }
            }
        }

        cachePut(parentFingerprint, row, col, depth, resolved);
        return resolved;
    }

    // ------------------------------------------------------------------ public API

    /** Scans every cell of a (pre-validated) 104 document for embedded 104 documents. */
    public static NestingResult scan(Object docObj, boolean useCache) {
        NestingResult result = new NestingResult();
        if (!(docObj instanceof Map)) {
            result.errors.add("E" + BejsonErrors.E_NESTING_NOT_BEJSON + ": doc must be a dict");
            return result;
        }
        Map<String, Object> doc = Json.asMap(docObj);
        Object fieldsObj = doc.containsKey("Fields") ? doc.get("Fields") : new ArrayList<Object>();
        Object valuesObj = doc.containsKey("Values") ? doc.get("Values") : new ArrayList<Object>();
        if (!(fieldsObj instanceof List) || !(valuesObj instanceof List)) {
            result.errors.add("E" + BejsonErrors.E_NESTING_VALIDATION_FAILED + ": Fields/Values malformed");
            return result;
        }
        List<Object> fieldsList = Json.asList(fieldsObj);
        List<Object> valuesList = Json.asList(valuesObj);

        String documentFingerprint = docFingerprint(doc);
        Set<String> rootSeen = new HashSet<String>();
        rootSeen.add(documentFingerprint);
        Map<Integer, String> colSchemas = new LinkedHashMap<Integer, String>();

        for (int rowIndex = 0; rowIndex < valuesList.size(); rowIndex++) {
            List<Object> row = Json.asList(valuesList.get(rowIndex));
            if (row == null) continue;
            for (int colIndex = 0; colIndex < row.size(); colIndex++) {
                Object cellValue = row.get(colIndex);
                result.scannedCells++;
                if (!isCandidate(cellValue)) continue;

                String resolvedFieldName;
                if (colIndex < fieldsList.size()) {
                    Map<String, Object> fd = Json.asMap(fieldsList.get(colIndex));
                    if (fd == null || !fd.containsKey("name")) {
                        throw new IllegalStateException("KeyError: 'name'");
                    }
                    Object nm = fd.get("name");
                    resolvedFieldName = nm instanceof String ? (String) nm : Json.pyStr(nm);
                } else {
                    resolvedFieldName = "col_" + colIndex;
                }

                if (useCache) {
                    NestedCell hit = cacheGet(documentFingerprint, rowIndex, colIndex, 1);
                    if (hit != null) {
                        result.nestedFound++;
                        if (hit.isValid) result.nestedValid++; else result.nestedInvalid++;
                        result.maxDepthSeen = Math.max(result.maxDepthSeen, hit.depth);
                        result.schemaErrors += countSchemaErrors(hit.errors);
                        result.cells.add(hit);
                        continue;
                    }
                }

                NestedCell nested = walkCell(cellValue, rowIndex, colIndex, resolvedFieldName, 1,
                        rootSeen, documentFingerprint, colSchemas);
                if (nested == null) continue;

                result.nestedFound++;
                if (nested.isValid) result.nestedValid++; else result.nestedInvalid++;
                result.maxDepthSeen = Math.max(result.maxDepthSeen, nested.depth);
                result.schemaErrors += countSchemaErrors(nested.errors);
                result.warnings.addAll(nested.warnings);
                result.cells.add(nested);
            }
        }
        return result;
    }

    public static NestingResult scan(Object doc) { return scan(doc, true); }

    private static int countSchemaErrors(List<String> errors) {
        String tag = "E" + BejsonErrors.E_NESTING_SCHEMA_MISMATCH;
        int n = 0;
        for (String m : errors) if (m.contains(tag)) n++;
        return n;
    }

    /**
     * Applies fn to nestedCell.doc, then serializes it back into parentDoc.Values[row][col]. Append-only schema:
     * removals, reorders and retypes raise IllegalArgumentException (Python ValueError); added fields null-pad rows.
     */
    public static void mutate(Map<String, Object> parentDoc, NestedCell nestedCell, Mutation fn) {
        int row = nestedCell.row;
        int col = nestedCell.col;

        List<Object> parentValues = Json.asList(parentDoc.containsKey("Values") ? parentDoc.get("Values") : new ArrayList<Object>());
        if (parentValues == null) parentValues = new ArrayList<Object>();
        boolean bad = row < 0 || col < 0 || row >= parentValues.size();
        if (!bad) {
            List<Object> prow = Json.asList(parentValues.get(row));
            bad = prow == null || col >= prow.size();
        }
        if (bad) {
            throw new IllegalArgumentException("E" + BejsonErrors.E_NESTING_INVALID_CELL + ": address row=" + row
                    + " col=" + col + " out of bounds");
        }

        Map<String, Object> doc = nestedCell.doc;
        List<Object> fieldsNow = Json.asList(doc.containsKey("Fields") ? doc.get("Fields") : new ArrayList<Object>());
        List<Map<String, Object>> fieldsBefore = new ArrayList<Map<String, Object>>();
        if (fieldsNow != null) {
            for (Object fd : fieldsNow) {
                Map<String, Object> m = Json.asMap(fd);
                if (m == null) throw new IllegalStateException("AttributeError: field definition has no attribute 'copy'");
                fieldsBefore.add(new LinkedHashMap<String, Object>(m));
            }
        }
        int countBefore = fieldsBefore.size();

        fn.apply(doc);

        List<Object> fieldsAfter = Json.asList(doc.containsKey("Fields") ? doc.get("Fields") : new ArrayList<Object>());
        if (fieldsAfter == null) fieldsAfter = new ArrayList<Object>();
        int countAfter = fieldsAfter.size();

        if (countAfter < countBefore) {
            throw new IllegalArgumentException("E" + BejsonErrors.E_NESTING_SCHEMA_MISMATCH + ": mutation removed fields "
                    + "— field removal is a breaking change and is not permitted");
        }

        int pairs = Math.min(countBefore, countAfter);
        for (int i = 0; i < pairs; i++) {
            Map<String, Object> before = fieldsBefore.get(i);
            Map<String, Object> after = Json.asMap(fieldsAfter.get(i));
            if (after == null) throw new IllegalStateException("AttributeError: field definition has no attribute 'get'");
            if (!Json.pyEquals(before.get("name"), after.get("name")) || !Json.pyEquals(before.get("type"), after.get("type"))) {
                throw new IllegalArgumentException("E" + BejsonErrors.E_NESTING_SCHEMA_MISMATCH
                        + ": mutation reordered or retyped field at index " + i
                        + " — only append-only additions are permitted");
            }
        }

        if (countAfter > countBefore) {
            int added = countAfter - countBefore;
            List<Object> vals = Json.asList(doc.containsKey("Values") ? doc.get("Values") : new ArrayList<Object>());
            if (vals != null) {
                for (Object vr : vals) {
                    List<Object> rowList = Json.asList(vr);
                    if (rowList != null) for (int k = 0; k < added; k++) rowList.add(null);
                }
            }
        }

        doc.remove(BejsonCore.FIELD_MAP_CACHE_KEY);

        Json.asList(parentValues.get(row)).set(col, Json.dumps(doc, -1, false));
    }

    /** Field-name to column map for a NestedCell's embedded document (Core field-map cache). */
    public static Map<String, Integer> getFieldMap(NestedCell nestedCell) {
        return BejsonCore.getFieldMap(nestedCell.doc);
    }

    /** Depth-first flat list of every NestedCell in a result. */
    public static List<NestedCell> flatten(NestingResult result) {
        List<NestedCell> out = new ArrayList<NestedCell>();
        collect(result.cells, out);
        return out;
    }

    private static void collect(List<NestedCell> cells, List<NestedCell> out) {
        for (NestedCell c : cells) {
            out.add(c);
            collect(c.children, out);
        }
    }

    /** Top-level (depth 1) cells for one column. */
    public static List<NestedCell> getColumn(NestingResult result, int col) {
        List<NestedCell> out = new ArrayList<NestedCell>();
        for (NestedCell c : result.cells) if (c.col == col) out.add(c);
        return out;
    }

    /** Human-readable summary of a NestingResult. */
    public static String summary(NestingResult result) {
        List<String> lines = new ArrayList<String>();
        lines.add("BEJSON Core_Nesting Scan Summary");
        lines.add("  Cells scanned    : " + result.scannedCells);
        lines.add("  Nested found     : " + result.nestedFound);
        lines.add("  Valid nested     : " + result.nestedValid);
        lines.add("  Invalid nested   : " + result.nestedInvalid);
        lines.add("  Max depth seen   : " + result.maxDepthSeen);
        lines.add("  Schema errors    : " + result.schemaErrors);
        if (!result.errors.isEmpty()) {
            lines.add("  Scan errors:");
            for (String m : result.errors) lines.add("    - " + m);
        }
        if (!result.warnings.isEmpty()) {
            lines.add("  Warnings:");
            for (String m : result.warnings) lines.add("    ~ " + m);
        }
        for (NestedCell c : flatten(result)) {
            String status = c.isValid ? "VALID" : "INVALID";
            StringBuilder ind = new StringBuilder("  ");
            for (int i = 0; i < c.depth - 1; i++) ind.append("  ");
            String indent = ind.toString();
            lines.add(indent + "[" + status + "] row=" + c.row + " col=" + c.col + " field='" + c.fieldName
                    + "' depth=" + c.depth);
            for (String m : c.errors) lines.add(indent + "  ! " + m);
            for (String m : c.warnings) lines.add(indent + "  ~ " + m);
        }
        return join(lines, "\n");
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ dotted-path queries

    private static final class Segment {
        final String name;
        /** 0 = none (treated as '*'), 1 = '*', 2 = index. */
        final int kind;
        final int index;

        Segment(String name, int kind, int index) {
            this.name = name;
            this.kind = kind;
            this.index = index;
        }
    }

    private static boolean asciiAlpha(char c) { return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'); }

    /** Hand-written equivalent of ^([A-Za-z_][A-Za-z0-9_]*)(\[(\*|\d+)\])?$ with Python re.match semantics. */
    private static Segment matchSegment(String raw) {
        int n = raw.length();
        if (n == 0) return null;
        char c0 = raw.charAt(0);
        if (!(asciiAlpha(c0) || c0 == '_')) return null;
        int i = 1;
        while (i < n) {
            char c = raw.charAt(i);
            if (asciiAlpha(c) || (c >= '0' && c <= '9') || c == '_') i++; else break;
        }
        String name = raw.substring(0, i);
        int kind = 0;
        int index = 0;
        if (i < n && raw.charAt(i) == '[') {
            int j = i + 1;
            if (j < n && raw.charAt(j) == '*') {
                kind = 1;
                j++;
            } else {
                int start = j;
                BigInteger acc = BigInteger.ZERO;
                while (j < n) {
                    int cp = raw.codePointAt(j);
                    if (!Character.isDigit(cp)) break;
                    acc = acc.multiply(BigInteger.TEN).add(BigInteger.valueOf(Character.digit(cp, 10)));
                    j += Character.charCount(cp);
                }
                if (j == start) return null;
                kind = 2;
                index = acc.bitLength() > 30 ? Integer.MAX_VALUE : acc.intValue();
            }
            if (j >= n || raw.charAt(j) != ']') return null;
            i = j + 1;
        }
        // '$' matches at the end or before one trailing '\n'
        if (i != n && !(i == n - 1 && raw.charAt(i) == '\n')) return null;
        return new Segment(name, kind, index);
    }

    /** Parses a dotted path; throws IllegalArgumentException with an E138/E139 prefix on bad grammar. */
    private static List<Segment> parsePath(String path) {
        if (path == null || path.isEmpty() || PyOps.strip(path).isEmpty()) {
            throw new IllegalArgumentException("E" + BejsonErrors.E_NESTING_QUERY_EMPTY_PATH + ": path is empty");
        }
        List<Segment> parsed = new ArrayList<Segment>();
        for (String raw : PyOps.strip(path).split("\\.", -1)) {
            Segment s = matchSegment(raw);
            if (s == null) {
                throw new IllegalArgumentException("E" + BejsonErrors.E_NESTING_QUERY_INVALID_PATH + ": bad segment '"
                        + raw + "' in path '" + path + "'");
            }
            parsed.add(s);
        }
        return parsed;
    }

    private static List<String> plusS(List<String> l, String v) {
        List<String> o = new ArrayList<String>(l);
        o.add(v);
        return o;
    }

    private static List<Integer> plusI(List<Integer> l, int v) {
        List<Integer> o = new ArrayList<Integer>(l);
        o.add(Integer.valueOf(v));
        return o;
    }

    private static void queryWalk(Object docObj, List<Segment> segments, int segIdx, String fullPath,
                                  List<String> fieldChain, List<Integer> rowChain, List<Integer> colChain,
                                  Object matchValue, List<QueryMatch> results) {
        if (!(docObj instanceof Map)) return;
        Map<String, Object> doc = Json.asMap(docObj);

        Segment seg = segments.get(segIdx);
        Map<String, Integer> fieldMap = BejsonCore.getFieldMap(doc);
        if (!fieldMap.containsKey(seg.name)) return;

        int col = fieldMap.get(seg.name).intValue();
        List<Object> values = Json.asList(doc.containsKey("Values") ? doc.get("Values") : new ArrayList<Object>());
        if (values == null) values = new ArrayList<Object>();
        boolean isLast = segIdx == segments.size() - 1;

        List<Integer> rowIndices = new ArrayList<Integer>();
        if (seg.kind == 2) {
            rowIndices.add(Integer.valueOf(seg.index));
        } else {
            for (int r = 0; r < values.size(); r++) rowIndices.add(Integer.valueOf(r));
        }

        for (Integer riObj : rowIndices) {
            int ri = riObj.intValue();
            if (ri < 0 || ri >= values.size()) continue;
            List<Object> row = Json.asList(values.get(ri));
            if (row == null || col >= row.size()) continue;
            Object cellValue = row.get(col);

            if (isLast) {
                if (matchValue == null || Json.pyEquals(cellValue, matchValue)) {
                    results.add(new QueryMatch(fullPath, cellValue, plusS(fieldChain, seg.name),
                            plusI(rowChain, ri), plusI(colChain, col)));
                }
                continue;
            }

            if (!isCandidate(cellValue)) continue;
            Map<String, Object> nestedDoc = parseCell((String) cellValue);
            if (nestedDoc == null) continue;

            queryWalk(nestedDoc, segments, segIdx + 1, fullPath, plusS(fieldChain, seg.name),
                    plusI(rowChain, ri), plusI(colChain, col), matchValue, results);
        }
    }

    /**
     * Resolves a dotted path (e.g. "player_inventory[*].item_id") through a document and its nested 104 cells.
     * matchValue == null returns every reached cell. Throws IllegalArgumentException only on malformed paths.
     */
    public static List<QueryMatch> query(Object doc, String path, Object matchValue) {
        List<Segment> segments = parsePath(path);
        List<QueryMatch> results = new ArrayList<QueryMatch>();
        queryWalk(doc, segments, 0, path, new ArrayList<String>(), new ArrayList<Integer>(),
                new ArrayList<Integer>(), matchValue, results);
        return results;
    }

    public static List<QueryMatch> query(Object doc, String path) { return query(doc, path, null); }

    /** First value at path, or dflt when nothing matches or the path is malformed. Never throws for bad paths. */
    public static Object getPath(Object doc, String path, Object dflt) {
        List<QueryMatch> matches;
        try {
            matches = query(doc, path);
        } catch (IllegalArgumentException e) {
            return dflt;
        }
        return matches.isEmpty() ? dflt : matches.get(0).value;
    }

    public static Object getPath(Object doc, String path) { return getPath(doc, path, null); }

    /** Human-readable summary of a query result list. */
    public static String querySummary(List<QueryMatch> matches) {
        if (matches.isEmpty()) return "BEJSON Core_Nesting Query Summary\n  0 matches";
        List<String> lines = new ArrayList<String>();
        lines.add("BEJSON Core_Nesting Query Summary — " + matches.size() + " match(es)");
        for (QueryMatch m : matches) {
            StringBuilder chain = new StringBuilder();
            int n = Math.min(m.fieldChain.size(), Math.min(m.rowChain.size(), m.colChain.size()));
            for (int i = 0; i < n; i++) {
                if (i > 0) chain.append(" -> ");
                chain.append(m.fieldChain.get(i)).append("[row=").append(m.rowChain.get(i))
                        .append(",col=").append(m.colChain.get(i)).append(']');
            }
            lines.add("  " + chain + " = " + Json.pyRepr(m.value));
        }
        return join(lines, "\n");
    }
}
