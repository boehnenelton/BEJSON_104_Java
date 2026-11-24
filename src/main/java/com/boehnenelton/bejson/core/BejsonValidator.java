/*
 * File Name:       BejsonValidator.java
 * Relative Path:   src/main/java/com/boehnenelton/bejson/core/BejsonValidator.java
 * Description:     Port of lib_bejson_Core_bejson_validator.py: seven-step validation of BEJSON 104 / 104a / 104db
 *                  documents (JSON string, in-memory document, or file) with identical error messages and codes.
 * VERSION:         1.0.0
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   a01dfa0b-47fd-4745-be2e-65b3a96d756f
 */
package com.boehnenelton.bejson.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class BejsonValidator {
    private BejsonValidator() { }

    public static final Set<String> VALID_VERSIONS =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList("104", "104a", "104db")));
    public static final Set<String> VALID_FIELD_TYPES = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("string", "integer", "number", "boolean", "array", "object")));
    public static final String[] MANDATORY_KEYS =
            {"Format", "Format_Version", "Format_Creator", "Records_Type", "Fields", "Values"};
    public static final Set<String> IN_MEMORY_ONLY_KEYS =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList("_nesting_field_map")));
    public static final String FIELD_MAP_CACHE_KEY = "_bejson_field_map";

    /** Port of ValidationResult. */
    public static final class ValidationResult {
        public boolean valid = true;
        public final List<String> errors = new ArrayList<String>();
        public final List<String> warnings = new ArrayList<String>();
        public String currentFile = "";

        public void addError(String message) { valid = false; errors.add(message); }

        public void addWarning(String message) { warnings.add(message); }

        public boolean isValid() { return valid; }
    }

    private static BejsonValidationException err(String msg, int code) {
        return new BejsonValidationException(msg, code);
    }

    /** Step 1: parse the input (JSON string, document map, or file path when isFile). */
    public static Object checkJsonSyntax(Object input, ValidationResult res, boolean isFile) {
        Object text;
        if (isFile) {
            String path = String.valueOf(input);
            if (!PyFs.exists(path)) throw err("File not found: " + input, BejsonErrors.E_FILE_NOT_FOUND);
            text = PyFs.readText(path);
            res.currentFile = path;
        } else {
            text = input;
        }
        if (text instanceof Map) return text;
        try {
            if (!(text instanceof String)) {
                throw new IllegalArgumentException("the JSON object must be str, bytes or bytearray, not " + PyOps.typeName(text));
            }
            return Json.loads((String) text);
        } catch (RuntimeException e) {
            throw err("Invalid JSON: " + e.getMessage(), BejsonErrors.E_INVALID_JSON);
        }
    }

    /** Step 2: mandatory keys, Format, Format_Creator, Format_Version; returns the version. */
    public static String checkMandatoryKeys(Object docObj) {
        Map<String, Object> doc = Json.asMap(docObj);
        if (doc == null) {
            // Python: `key not in doc` -- list membership, str substring, TypeError for numbers/None/bool
            if (docObj instanceof List) throw err("Missing key: " + MANDATORY_KEYS[0], BejsonErrors.E_MISSING_MANDATORY_KEY);
            if (docObj instanceof String) {
                for (String key : MANDATORY_KEYS) {
                    if (!((String) docObj).contains(key)) throw err("Missing key: " + key, BejsonErrors.E_MISSING_MANDATORY_KEY);
                }
                throw new IllegalStateException("string indices must be integers, not 'str'");
            }
            throw new IllegalStateException("argument of type '" + PyOps.typeName(docObj) + "' is not iterable");
        }
        for (String key : MANDATORY_KEYS) {
            if (!doc.containsKey(key)) throw err("Missing key: " + key, BejsonErrors.E_MISSING_MANDATORY_KEY);
        }
        if (!"BEJSON".equals(doc.get("Format"))) throw err("Invalid Format", BejsonErrors.E_INVALID_FORMAT);
        if (!"Elton Boehnen".equals(doc.get("Format_Creator"))) {
            throw err("Invalid Format_Creator: Must be 'Elton Boehnen'", BejsonErrors.E_INVALID_FORMAT);
        }
        Object version = doc.containsKey("Format_Version") ? doc.get("Format_Version") : "";
        PyOps.hashable(version);
        if (!(version instanceof String) || !VALID_VERSIONS.contains(version)) {
            throw err("Invalid version: " + Json.pyStr(version), BejsonErrors.E_INVALID_VERSION);
        }
        return (String) version;
    }

    /** Step 3: Records_Type list; exactly one type for 104/104a, two or more for 104db. */
    public static void checkRecordsType(Map<String, Object> doc, String version) {
        Object rt = doc.get("Records_Type");
        if (!(rt instanceof List)) throw err("Records_Type must be a list", BejsonErrors.E_INVALID_RECORDS_TYPE);
        int count = ((List<?>) rt).size();
        if (version.equals("104") || version.equals("104a")) {
            if (count != 1) {
                throw err("BEJSON " + version + " must have exactly 1 record type. Found " + count + ".",
                        BejsonErrors.E_INVALID_RECORDS_TYPE);
            }
        } else if (version.equals("104db")) {
            if (count < 2) throw err("104db requires 2+ types", BejsonErrors.E_INVALID_RECORDS_TYPE);
        }
    }

    /** Step 4 (104db only): first field Record_Type_Parent and every row's column 0 one of Records_Type. */
    public static boolean checkRecordTypeParent(Map<String, Object> doc, String version) {
        if (!version.equals("104db")) return true;
        Object fields = doc.get("Fields");
        if (!Json.truthy(fields) || !Json.pyEquals("Record_Type_Parent", PyOps.get(PyOps.index(fields, 0), "name"))) {
            throw err("104db first field must be 'Record_Type_Parent'", BejsonErrors.E_INVALID_RECORD_TYPE_PARENT);
        }
        List<Object> validTypes = PyOps.iter(doc.get("Records_Type"));
        for (Object t : validTypes) PyOps.hashable(t);
        List<Object> values = PyOps.iter(doc.get("Values"));
        for (int i = 0; i < values.size(); i++) {
            Object record = values.get(i);
            if (!Json.truthy(record)) continue;
            Object rtp = PyOps.index(record, 0);
            if (!PyOps.inSet(rtp, validTypes)) {
                throw err("Invalid Record_Type_Parent '" + Json.pyStr(rtp) + "' at row " + i,
                        BejsonErrors.E_INVALID_RECORD_TYPE_PARENT);
            }
        }
        return true;
    }

    /** Step 5: field names / types; returns the field count. */
    public static int checkFieldsStructure(Map<String, Object> doc, String version) {
        Object fieldsObj = doc.get("Fields");
        List<Object> fields = PyOps.iter(fieldsObj);
        for (int i = 0; i < fields.size(); i++) {
            Object f = fields.get(i);
            Object fname = PyOps.get(f, "name");
            Object ftype = PyOps.get(f, "type");
            if (!Json.truthy(fname) || !Json.truthy(ftype)) {
                throw err("Field " + i + " missing name or type", BejsonErrors.E_INVALID_FIELDS);
            }
            PyOps.hashable(ftype);
            if (!(ftype instanceof String) || !VALID_FIELD_TYPES.contains(ftype)) {
                throw err("Field '" + Json.pyStr(fname) + "' (index " + i + ") has invalid type '" + Json.pyStr(ftype)
                        + "'. Must be exactly one of " + sortedTypes() + " (lowercase).", BejsonErrors.E_INVALID_FIELDS);
            }
            if (version.equals("104a") && (ftype.equals("array") || ftype.equals("object"))) {
                throw err("104a forbids complex type '" + ftype + "'. Use Format_Version \"104\" for schemas requiring array or object fields.",
                        BejsonErrors.E_INVALID_FIELDS);
            }
            if (version.equals("104db") && !Json.pyEquals("Record_Type_Parent", fname) && !((Map<?, ?>) f).containsKey("Record_Type_Parent")) {
                throw err("Field '" + Json.pyStr(fname) + "' missing Record_Type_Parent in 104db",
                        BejsonErrors.E_INVALID_RECORD_TYPE_PARENT);
            }
        }
        return PyOps.len(fieldsObj);
    }

    private static String sortedTypes() {
        List<String> s = new ArrayList<String>(new TreeSet<String>(VALID_FIELD_TYPES));
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < s.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('\'').append(s.get(i)).append('\'');
        }
        return sb.append(']').toString();
    }

    /** Step 6: row lengths and cell types. */
    public static void checkValues(Map<String, Object> doc, String version, int fieldsCount) {
        Object fields = doc.get("Fields");
        List<Object> values = PyOps.iter(doc.get("Values"));
        for (int i = 0; i < values.size(); i++) {
            Object recObj = values.get(i);
            if (PyOps.len(recObj) != fieldsCount) throw err("Length mismatch at row " + i, BejsonErrors.E_RECORD_LENGTH_MISMATCH);
            List<Object> record = PyOps.iter(recObj);
            for (int j = 0; j < record.size(); j++) {
                Object f = PyOps.index(fields, j);
                Object ftype = PyOps.get(f, "type");
                Object val = record.get(j);
                if (val == null) continue;
                if ("string".equals(ftype) && !(val instanceof String)) {
                    throw err(pos(i, j, f) + "string", BejsonErrors.E_TYPE_MISMATCH);
                } else if ("integer".equals(ftype) && (!Json.pyIsInt(val) || val instanceof Boolean)) {
                    throw err(pos(i, j, f) + "integer", BejsonErrors.E_TYPE_MISMATCH);
                } else if ("number".equals(ftype) && (!(Json.pyIsInt(val) || Json.isFloat(val)) || val instanceof Boolean)) {
                    throw err(pos(i, j, f) + "number, got bool", BejsonErrors.E_TYPE_MISMATCH);
                } else if ("boolean".equals(ftype) && !(val instanceof Boolean)) {
                    throw err(pos(i, j, f) + "boolean", BejsonErrors.E_TYPE_MISMATCH);
                } else if ("array".equals(ftype) && !(val instanceof List)) {
                    throw err(pos(i, j, f) + "array", BejsonErrors.E_TYPE_MISMATCH);
                } else if ("object".equals(ftype) && !(val instanceof Map)) {
                    throw err(pos(i, j, f) + "object", BejsonErrors.E_TYPE_MISMATCH);
                }
            }
        }
    }

    /** "Type mismatch at row i, col j (name): expected " -- fields[j]['name'] raises KeyError when the field has no name. */
    private static String pos(int i, int j, Object f) {
        Map<?, ?> fm = (Map<?, ?>) f;
        if (!fm.containsKey("name")) throw new IllegalStateException("'name'");
        return "Type mismatch at row " + i + ", col " + j + " (" + Json.pyStr(fm.get("name")) + "): expected ";
    }

    /** Duplicate field names are rejected (E_INVALID_FIELDS). */
    public static void checkFieldNamesUnique(Map<String, Object> doc) {
        Set<String> seen = new HashSet<String>();
        List<Object> fields = PyOps.iter(doc.get("Fields"));
        for (int i = 0; i < fields.size(); i++) {
            Object fname = PyOps.get(fields.get(i), "name");
            if (!seen.add(PyOps.hashKey(fname))) {
                throw err("Duplicate field name '" + Json.pyStr(fname) + "' at index " + i, BejsonErrors.E_INVALID_FIELDS);
            }
        }
    }

    /** Reserved in-memory key _bejson_field_map: forbidden when persisted; must match Fields in memory. */
    public static void checkFieldMapCache(Map<String, Object> doc, boolean persisted) {
        if (!doc.containsKey(FIELD_MAP_CACHE_KEY)) return;
        if (persisted) {
            throw err("Reserved key '" + FIELD_MAP_CACHE_KEY + "' must not be persisted (in-memory cache only)",
                    BejsonErrors.E_RESERVED_KEY_COLLISION);
        }
        Object cached = doc.get(FIELD_MAP_CACHE_KEY);
        List<Object> fields = Json.asList(doc.get("Fields"));
        Map<String, Object> expected = new LinkedHashMap<String, Object>();
        for (int i = 0; i < fields.size(); i++) expected.put(BejsonCore.fieldName(fields.get(i)), Long.valueOf(i));
        boolean ok = cached instanceof Map && Json.pyEquals(cached, expected) && expected.size() == fields.size();
        if (!ok) {
            throw err("In-memory '" + FIELD_MAP_CACHE_KEY + "' does not match Fields (stale or forged)",
                    BejsonErrors.E_RESERVED_KEY_COLLISION);
        }
    }

    /** Step 7: custom top-level keys. */
    public static void checkCustomHeaders(Map<String, Object> doc, String version, boolean persisted) {
        Set<String> mandatory = new HashSet<String>(Arrays.asList(MANDATORY_KEYS));
        for (String key : doc.keySet()) {
            if (mandatory.contains(key) || key.equals("Parent_Hierarchy") || key.equals(FIELD_MAP_CACHE_KEY)) continue;
            if (key.startsWith("_")) {
                if (persisted || !IN_MEMORY_ONLY_KEYS.contains(key)) {
                    throw err("Reserved key collision: '" + key + "' uses the reserved '_' prefix",
                            BejsonErrors.E_RESERVED_KEY_COLLISION);
                }
                continue;
            }
            if (version.equals("104") || version.equals("104db")) {
                throw err("Custom key '" + key + "' forbidden in " + version, BejsonErrors.E_RESERVED_KEY_COLLISION);
            }
        }
    }

    public static void checkCustomHeaders(Map<String, Object> doc, String version) {
        checkCustomHeaders(doc, version, true);
    }

    /** Runs every step and returns a ValidationResult. */
    public static ValidationResult validateBejson(Object inputData, boolean isFile) {
        ValidationResult res = new ValidationResult();
        try {
            Object docObj = checkJsonSyntax(inputData, res, isFile);
            String version = checkMandatoryKeys(docObj);
            Map<String, Object> doc = Json.asMap(docObj);
            boolean persisted = isFile || !(inputData instanceof Map);
            checkCustomHeaders(doc, version, persisted);
            checkRecordsType(doc, version);
            checkRecordTypeParent(doc, version);
            int fieldsCount = checkFieldsStructure(doc, version);
            checkFieldNamesUnique(doc);
            checkFieldMapCache(doc, persisted);
            checkValues(doc, version, fieldsCount);
        } catch (BejsonValidationException e) {
            res.addError(e.getMessage());
        } catch (RuntimeException e) {
            res.addError("Unexpected validation error: " + e.getMessage());
        }
        return res;
    }

    public static ValidationResult validateBejson(Object inputData) { return validateBejson(inputData, false); }

    /** Human-readable report. */
    public static String getReport(Object inputData, boolean isFile) {
        ValidationResult res = validateBejson(inputData, isFile);
        List<String> lines = new ArrayList<String>();
        lines.add("BEJSON Validation Report");
        lines.add("  File: " + (res.currentFile.isEmpty() ? "<string>" : res.currentFile));
        lines.add("  Valid: " + (res.valid ? "True" : "False"));
        if (!res.errors.isEmpty()) {
            lines.add("  Errors:");
            for (String e : res.errors) lines.add("    - " + e);
        }
        if (!res.warnings.isEmpty()) {
            lines.add("  Warnings:");
            for (String w : res.warnings) lines.add("    - " + w);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    /** Validates a JSON string; throws BejsonValidationException(E_INVALID_FORMAT) on the first error. */
    public static boolean validateString(String jsonString) {
        ValidationResult res = validateBejson(jsonString, false);
        if (!res.valid) throw err(res.errors.get(0), BejsonErrors.E_INVALID_FORMAT);
        return true;
    }

    /** Validates a file; throws on the first error. */
    public static void validateFile(String filePath) {
        ValidationResult res = validateBejson(filePath, true);
        if (!res.valid) throw err(res.errors.get(0), BejsonErrors.E_INVALID_FORMAT);
    }
}
