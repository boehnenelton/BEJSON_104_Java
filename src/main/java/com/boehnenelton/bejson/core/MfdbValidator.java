/*
 * File Name:       MfdbValidator.java
 * Relative Path:   src/main/java/com/boehnenelton/bejson/core/MfdbValidator.java
 * Description:     Port of lib_bejson_Core_mfdb_validator.py 2.3.1: MFDB validator - manifest (Level 1), entity files
 *                  with bidirectional manifest checks (Level 2), whole-database validation (Level 3), plus MFDB-132
 *                  chunked-package validation (isMfdb132Package, validateMfdb132Package, detectMfdbInChunk).
 *                  MFDB is a layer on BEJSON (104a manifest + dense 104 entity files), not 104db.
 * Deviation:       Messages that embed a Python exception text ({e}) match Python for JSON decode errors, missing
 *                  keys and wrong-type documents; other OS-level texts (e.g. IO errors) use Java wording.
 * VERSION:         1.0.0 (reference 2.3.1)
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   a850cbd3-6bcf-474b-aefe-2216cbac7db4
 */
package com.boehnenelton.bejson.core;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class MfdbValidator {
    private MfdbValidator() { }

    public static final String VERSION = "2.3.1";
    public static final String MFDB_MANIFEST_FILENAME = "104a.mfdb.bejson";
    public static final String[] VALID_NETWORK_ROLES = {"Master", "Slave", "Standalone"};

    // ------------------------------------------------------------ types

    /** Port of MFDBValidationError: numeric code plus optional context map. */
    public static class MfdbValidationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final int code;
        public final Map<String, Object> context;

        public MfdbValidationException(String message, int code, Map<String, Object> context) {
            super(message);
            this.code = code;
            this.context = context == null ? new LinkedHashMap<String, Object>() : context;
        }

        public MfdbValidationException(String message, int code) {
            this(message, code, null);
        }
    }

    /** Port of MFDBValidationResult. */
    public static class MfdbValidationResult {
        public boolean valid = true;
        public List<String> errors = new ArrayList<String>();
        public List<String> warnings = new ArrayList<String>();
        public Map<String, Object> findings = new LinkedHashMap<String, Object>();

        /** Records a hard error and marks the result invalid. */
        public void addError(String message, String location) {
            valid = false;
            boolean hasLoc = location != null && !location.isEmpty();
            errors.add(hasLoc ? "ERROR | Location: " + location + " | Message: " + message : "ERROR | Message: " + message);
        }

        public void addError(String message) { addError(message, ""); }

        /** Records a warning without invalidating the result. */
        public void addWarning(String message, String location) {
            boolean hasLoc = location != null && !location.isEmpty();
            warnings.add(hasLoc ? "WARNING | Location: " + location + " | Message: " + message : "WARNING | Message: " + message);
        }

        public void addWarning(String message) { addWarning(message, ""); }

        public boolean isValid() { return valid; }
    }

    // ------------------------------------------------------------ helpers

    /** Python-style dict access on a value that must be a mapping (AttributeError otherwise). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> asDoc(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        throw new IllegalArgumentException("'" + PyOps.typeName(o) + "' object has no attribute 'get'");
    }

    private static String strOf(Object o) { return Json.pyStr(o); }

    private static boolean eqStr(Object o, String s) { return o instanceof String && o.equals(s); }

    /** Hash key that follows Python equality for hashable JSON scalars (None, str, numbers, bool). */
    private static String hashKey(Object o) {
        if (o == null) return "N:";
        if (o instanceof String) return "S:" + o;
        if (o instanceof Boolean) return "D:" + (((Boolean) o).booleanValue() ? "1" : "0");
        if (o instanceof Number) {
            if (o instanceof Double && (((Double) o).isNaN() || ((Double) o).isInfinite())) return "F:" + o;
            BigDecimal d = o instanceof java.math.BigInteger ? new BigDecimal((java.math.BigInteger) o)
                    : o instanceof Double ? new BigDecimal(((Double) o).doubleValue()) : new BigDecimal(((Number) o).longValue());
            return "D:" + (d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString());
        }
        throw new IllegalArgumentException("unhashable type: '" + PyOps.typeName(o) + "'");
    }

    private static List<Object> fieldsOf(Map<String, Object> doc) {
        Object f = doc.containsKey("Fields") ? doc.get("Fields") : new ArrayList<Object>();
        List<Object> l = Json.asList(f);
        if (l == null) throw new IllegalArgumentException("'" + PyOps.typeName(f) + "' object is not iterable");
        return l;
    }

    private static Object fieldNameStrict(Object f) {
        return PyOps.subKey(f, "name");
    }

    /** Map key for a Python dict key of any hashable type (non-strings can never collide with a real field name). */
    private static String nameKey(Object n) {
        PyOps.hashable(n);
        return n instanceof String ? (String) n : "\u0000" + PyOps.hashKey(n);
    }

    /** {f["name"]: i} (last duplicate wins). */
    private static Map<String, Integer> fieldIndexMap(List<Object> fields) {
        Map<String, Integer> fm = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < fields.size(); i++) {
            Object n = fieldNameStrict(fields.get(i));
            fm.put(nameKey(n), Integer.valueOf(i));
        }
        return fm;
    }

    private static Object cell(List<Object> row, int idx) {
        int n = row.size();
        int i = idx < 0 ? n + idx : idx;
        if (i < 0 || i >= n) throw new IndexOutOfBoundsException("list index out of range");
        return row.get(i);
    }

    private static boolean truthyCell(Object o) { return Json.truthy(o); }

    // ------------------------------------------------------------ internal helpers (_load_json etc.)

    /** Reads JSON from a file or from the manifest inside a .zip archive. Raises on missing/invalid JSON. */
    public static Object loadJson(String path) {
        boolean isFile = PyFs.isFile(path);
        boolean zip = path.toLowerCase(java.util.Locale.ROOT).endsWith(".zip");
        if (isFile && !zip) {
            return Json.loads(PyFs.readText(path));
        }
        if (zip && isFile) {
            ZipFile z = null;
            try {
                z = new ZipFile(path);
                ZipEntry e = z.getEntry(MFDB_MANIFEST_FILENAME);
                if (e != null) {
                    java.io.InputStream in = z.getInputStream(e);
                    try {
                        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                        BejsonPathGuard.copyBounded(in, bos, new BejsonPathGuard.Budget());
                        return Json.loads(PyFs.decodeUtf8Strict(bos.toByteArray(), path));
                    } finally {
                        in.close();
                    }
                }
                throw new BejsonOSError(2, "104a.mfdb.bejson not found in archive: " + path,
                        BejsonOSError.Kind.FILE_NOT_FOUND, null);
            } catch (IOException ex) {
                throw new BejsonOSError(0, "File is not a zip file", BejsonOSError.Kind.GENERIC, ex);
            } finally {
                if (z != null) {
                    try { z.close(); } catch (IOException ignored) { /* ignore */ }
                }
            }
        }
        return Json.loads(PyFs.readText(path));
    }

    /** Turns Values into a list of {field_name: value} maps (zip semantics: shortest of names/row). */
    public static List<Map<String, Object>> rowsAsDicts(Map<String, Object> doc) {
        Object fieldsObj = PyOps.subKey(doc, "Fields");
        List<Object> names = new ArrayList<Object>();
        for (Object f : PyOps.iter(fieldsObj)) names.add(fieldNameStrict(f));
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object r : PyOps.iter(PyOps.subKey(doc, "Values"))) {
            if (!(r instanceof List) && !(r instanceof Map) && !(r instanceof String)) {
                throw new IllegalArgumentException("zip argument #2 must support iteration");
            }
            List<Object> row = PyOps.iter(r);
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            int n = Math.min(names.size(), row.size());
            for (int i = 0; i < n; i++) m.put(nameKey(names.get(i)), row.get(i));
            out.add(m);
        }
        return out;
    }

    /** Resolves an entity file_path against the manifest's directory (or the archive path for .zip). */
    public static String resolveEntityPath(String manifestPath, String filePathRel) {
        if (manifestPath.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
            return PyFs.join(manifestPath, filePathRel);
        }
        String manifestDir = PyFs.dirname(PyFs.abspath(manifestPath));
        return PyFs.normpath(PyFs.join(manifestDir, filePathRel));
    }

    private static String requireStr(Object o, String what) {
        if (o instanceof String) return (String) o;
        throw new IllegalArgumentException("'" + PyOps.typeName(o) + "' object has no attribute '" + what + "'");
    }

    // ------------------------------------------------------------ validation functions

    /** Validates a .mfdb.zip archive: is a zip with a manifest at its root. */
    public static MfdbValidationResult validateMfdbArchive(String archivePath) {
        MfdbValidationResult res = new MfdbValidationResult();
        if (!PyFs.exists(archivePath)) {
            res.addError("Archive not found: " + archivePath, "File System");
            return res;
        }
        if (PyFs.isDir(archivePath)) {
            res.addError("Invalid zip: [Errno 21] Is a directory: '" + archivePath + "'", "Zip Parser");
            return res;
        }
        ZipFile z = null;
        try {
            z = new ZipFile(archivePath);
            if (z.getEntry(MFDB_MANIFEST_FILENAME) == null) {
                res.addError("Archive missing 104a.mfdb.bejson at root", "Zip Structure");
            }
        } catch (IOException e) {
            res.addError("Invalid zip: File is not a zip file", "Zip Parser");
        } finally {
            if (z != null) {
                try { z.close(); } catch (IOException ignored) { /* ignore */ }
            }
        }
        return res;
    }

    /** Level 1: manifest validation (104a, Records_Type ["mfdb"], required fields, entity rows). */
    public static MfdbValidationResult validateMfdbManifest(String manifestPath) {
        MfdbValidationResult res = new MfdbValidationResult();
        if (!PyFs.exists(manifestPath)) {
            res.addError("[" + BejsonErrors.E_MFDB_MANIFEST_NOT_FOUND + "] Manifest not found: " + manifestPath, "File System");
            return res;
        }
        BejsonValidator.ValidationResult bej = BejsonValidator.validateBejson(manifestPath, true);
        if (!bej.valid) {
            for (String err : bej.errors) res.addError(err, "BEJSON Validation");
            return res;
        }

        Map<String, Object> doc = asDoc(loadJson(manifestPath));
        if (!eqStr(doc.get("Format_Version"), "104a") || !Json.pyEquals(doc.get("Records_Type"), Json.arr("mfdb"))) {
            res.addError("[" + BejsonErrors.E_MFDB_MANIFEST_RECORDS_TYPE + "] Invalid manifest format or records type "
                    + "(must be 104a with Records_Type [\"mfdb\"])", "Manifest");
            return res;
        }

        if (doc.containsKey("Network_Role") && doc.get("Network_Role") != null) {
            Object nr = doc.get("Network_Role");
            boolean ok = false;
            if (nr instanceof String) {
                for (String r : VALID_NETWORK_ROLES) if (r.equals(nr)) ok = true;
            }
            if (!ok) {
                res.addError("[" + BejsonErrors.E_MFDB_NOT_MANIFEST + "] Invalid Network_Role " + Json.pyRepr(nr)
                        + " (must be one of ('Master', 'Slave', 'Standalone'))", "Network_Role");
            }
        }

        List<String> fieldNames = new ArrayList<String>();
        for (Object f : fieldsOf(doc)) {
            Object n = fieldNameStrict(f);
            fieldNames.add(n instanceof String ? (String) n : "\u0000" + PyOps.hashKey(n));
        }
        for (String req : new String[] {"entity_name", "file_path"}) {
            if (!fieldNames.contains(req)) {
                res.addError("[" + BejsonErrors.E_MFDB_MISSING_REQUIRED_FIELD + "] Missing required field: " + req, "Fields");
            }
        }

        Set<String> seenNames = new HashSet<String>();
        Set<String> seenPaths = new HashSet<String>();
        List<Map<String, Object>> entries = rowsAsDicts(doc);
        for (int i = 0; i < entries.size(); i++) {
            Map<String, Object> entry = entries.get(i);
            Object en = entry.get("entity_name");
            Object fp = entry.get("file_path");
            if (!Json.truthy(en) || !Json.truthy(fp)) {
                res.addError("[" + BejsonErrors.E_MFDB_NULL_REQUIRED + "] Record " + i + ": null entity_name or file_path", "Values");
            }
            if (seenNames.contains(hashKey(en))) {
                res.addError("[" + BejsonErrors.E_MFDB_DUPLICATE_ENTRY + "] Duplicate entity: " + strOf(en), "Values");
            }
            if (seenPaths.contains(hashKey(fp))) {
                res.addError("[" + BejsonErrors.E_MFDB_DUPLICATE_ENTRY + "] Duplicate path: " + strOf(fp), "Values");
            }
            seenNames.add(hashKey(en));
            seenPaths.add(hashKey(fp));

            if (Json.truthy(fp) && BejsonPathGuard.mfdbEscapesRoot(requireStr(fp, "replace"))) {
                res.addError("[" + BejsonErrors.E_MFDB_ENTITY_NOT_FOUND + "] Path traversal detected in file_path for entity '"
                        + strOf(en) + "': '" + strOf(fp) + "'", "Values");
                continue;
            }

            String resolved = resolveEntityPath(manifestPath, requirePathStr(fp));
            if (!PyFs.exists(resolved)) {
                res.addError("[" + BejsonErrors.E_MFDB_ENTITY_NOT_FOUND + "] Entity file not found: " + strOf(fp), "File System");
            }
        }
        return res;
    }

    private static String requirePathStr(Object o) {
        if (o instanceof String) return (String) o;
        if (o == null) throw new IllegalArgumentException("expected str, bytes or os.PathLike object, not NoneType");
        throw new IllegalArgumentException("expected str, bytes or os.PathLike object, not " + PyOps.typeName(o));
    }

    private static boolean sameFile(String a, String b) {
        try {
            return Files.isSameFile(new java.io.File(a).toPath(), new java.io.File(b).toPath());
        } catch (IOException e) {
            return PyFs.realpath(a).equals(PyFs.realpath(b));
        } catch (RuntimeException e) {
            return PyFs.realpath(a).equals(PyFs.realpath(b));
        }
    }

    /** Level 2: entity file validation with optional bidirectional manifest check. */
    public static MfdbValidationResult validateMfdbEntityFile(String entityPath, boolean checkBidirectional,
                                                              String expectedEntityName, String expectedManifest) {
        MfdbValidationResult res = new MfdbValidationResult();
        if (!PyFs.exists(entityPath)) {
            res.addError("[" + BejsonErrors.E_MFDB_ENTITY_NOT_FOUND + "] Entity file not found: " + entityPath, "File System");
            return res;
        }
        BejsonValidator.ValidationResult bej = BejsonValidator.validateBejson(entityPath, true);
        if (!bej.valid) {
            for (String err : bej.errors) res.addError(err, "BEJSON Validation");
            return res;
        }

        Map<String, Object> doc = asDoc(loadJson(entityPath));
        if (!eqStr(doc.get("Format_Version"), "104")) {
            res.addError("[" + BejsonErrors.E_MFDB_NOT_ENTITY_FILE + "] Entity file must be Format_Version 104", "Format_Version");
            return res;
        }

        Object ph = doc.get("Parent_Hierarchy");
        if (!Json.truthy(ph)) {
            res.addError("[" + BejsonErrors.E_MFDB_NO_PARENT_HIERARCHY + "] Missing Parent_Hierarchy", "Structure");
            return res;
        }

        String manifestPath = PyFs.normpath(PyFs.join(PyFs.dirname(PyFs.abspath(entityPath)), requirePathStr(ph)));
        if (!PyFs.exists(manifestPath)) {
            res.addError("[" + BejsonErrors.E_MFDB_MANIFEST_NOT_FOUND + "] Manifest not found at " + manifestPath
                    + " (orphan entity)", "Parent_Hierarchy");
            return res;
        }

        Object rt = doc.get("Records_Type");
        List<Object> rtl = Json.asList(rt);
        if (!(rtl != null && rtl.size() == 1 && rtl.get(0) instanceof String && !((String) rtl.get(0)).isEmpty())) {
            res.addError("[" + BejsonErrors.E_MFDB_ENTITY_NAME_MISMATCH
                    + "] Records_Type must contain exactly one string (the entity name)", "Records_Type");
            return res;
        }
        String rt0 = (String) rtl.get(0);

        if (expectedManifest != null && !sameFile(manifestPath, expectedManifest)) {
            res.addError("[" + BejsonErrors.E_MFDB_BIDIRECTIONAL_FAIL + "] Parent_Hierarchy points to " + manifestPath
                    + ", not to the manifest that lists this file (" + expectedManifest + ")", "Parent_Hierarchy");
            return res;
        }
        if (expectedEntityName != null && !rt0.equals(expectedEntityName)) {
            res.addError("[" + BejsonErrors.E_MFDB_ENTITY_NAME_MISMATCH + "] Records_Type " + Json.pyRepr(rt0)
                    + " does not match manifest entity_name " + Json.pyRepr(expectedEntityName) + " (case-sensitive)", "Records_Type");
        }

        if (checkBidirectional) {
            List<Map<String, Object>> rows;
            try {
                Map<String, Object> mdoc = asDoc(loadJson(manifestPath));
                if (!eqStr(mdoc.get("Format_Version"), "104a") || !Json.pyEquals(mdoc.get("Records_Type"), Json.arr("mfdb"))) {
                    res.addError("[" + BejsonErrors.E_MFDB_NOT_MANIFEST + "] Parent_Hierarchy target is not an MFDB manifest",
                            "Parent_Hierarchy");
                    return res;
                }
                rows = rowsAsDicts(mdoc);
            } catch (RuntimeException e) {
                res.addError("[" + BejsonErrors.E_MFDB_NOT_MANIFEST + "] Parent_Hierarchy target unreadable as a manifest: "
                        + e.getMessage(), "Parent_Hierarchy");
                return res;
            }
            List<Map<String, Object>> mine = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> r : rows) {
                Object fp = r.get("file_path");
                if (Json.truthy(fp) && !BejsonPathGuard.mfdbEscapesRoot(requireStr(fp, "replace"))
                        && sameFile(resolveEntityPath(manifestPath, (String) fp), entityPath)) {
                    mine.add(r);
                }
            }
            if (mine.isEmpty()) {
                res.addError("[" + BejsonErrors.E_MFDB_BIDIRECTIONAL_FAIL + "] Bidirectional check failed: manifest "
                        + manifestPath + " has no file_path that resolves to this entity file", "Parent_Hierarchy");
            } else if (expectedEntityName == null) {
                boolean any = false;
                for (Map<String, Object> r : mine) {
                    if (Json.pyEquals(r.get("entity_name"), rt0)) { any = true; break; }
                }
                if (!any) {
                    res.addError("[" + BejsonErrors.E_MFDB_ENTITY_NAME_MISMATCH + "] Records_Type " + Json.pyRepr(rt0)
                            + " does not match the manifest entity_name for this file ("
                            + Json.pyRepr(mine.get(0).get("entity_name")) + ")", "Records_Type");
                }
            }
        }
        return res;
    }

    public static MfdbValidationResult validateMfdbEntityFile(String entityPath, boolean checkBidirectional) {
        return validateMfdbEntityFile(entityPath, checkBidirectional, null, null);
    }

    public static MfdbValidationResult validateMfdbEntityFile(String entityPath) {
        return validateMfdbEntityFile(entityPath, true, null, null);
    }

    /** Level 3: whole database - manifest, every entity (name match + bidirectional), record_count drift warning. */
    public static MfdbValidationResult validateMfdbDatabase(String manifestPath, boolean strictFk) {
        MfdbValidationResult res = validateMfdbManifest(manifestPath);
        if (!res.valid) return res;

        Map<String, Object> doc = asDoc(loadJson(manifestPath));
        for (Map<String, Object> entry : rowsAsDicts(doc)) {
            if (!entry.containsKey("file_path")) throw new IllegalArgumentException("'file_path'");
            if (!entry.containsKey("entity_name")) throw new IllegalArgumentException("'entity_name'");
            String resolved = resolveEntityPath(manifestPath, requirePathStr(entry.get("file_path")));
            Object en = entry.get("entity_name");
            String enStr = en instanceof String ? (String) en : null;
            MfdbValidationResult ent = validateMfdbEntityFile(resolved, true, enStr, manifestPath);
            if (!ent.valid) {
                for (String err : ent.errors) res.addError(err, "Entity:" + strOf(en));
            } else {
                Object declared = entry.get("record_count");
                if (declared != null) {
                    try {
                        Map<String, Object> edoc = asDoc(loadJson(resolved));
                        int actual = lenOf(edoc.containsKey("Values") ? edoc.get("Values") : new ArrayList<Object>());
                        if (!Json.pyEquals(declared, Long.valueOf(actual))) {
                            res.addWarning("record_count " + strOf(declared) + " in manifest != " + actual + " rows in file",
                                    "Entity:" + strOf(en));
                        }
                    } catch (RuntimeException e) {
                        res.addWarning("could not compare record_count: " + e.getMessage(), "Entity:" + strOf(en));
                    }
                }
            }
        }
        return res;
    }

    public static MfdbValidationResult validateMfdbDatabase(String manifestPath) {
        return validateMfdbDatabase(manifestPath, false);
    }

    private static int lenOf(Object o) {
        if (o instanceof List) return ((List<?>) o).size();
        if (o instanceof String) return ((String) o).codePointCount(0, ((String) o).length());
        if (o instanceof Map) return ((Map<?, ?>) o).size();
        throw new IllegalArgumentException("object of type '" + PyOps.typeName(o) + "' has no len()");
    }

    // ------------------------------------------------------------ compatibility wrappers

    /** Raising wrapper over validateMfdbManifest. */
    public static boolean validateManifest(String p) {
        MfdbValidationResult res = validateMfdbManifest(p);
        if (!res.valid) throw new MfdbValidationException(res.errors.get(0), BejsonErrors.E_MFDB_NOT_MANIFEST);
        return true;
    }

    /** Raising wrapper over validateMfdbDatabase. */
    public static boolean validateDatabase(String p, boolean strictFk) {
        MfdbValidationResult res = validateMfdbDatabase(p, strictFk);
        if (!res.valid) throw new MfdbValidationException(res.errors.get(0), BejsonErrors.E_MFDB_NOT_MANIFEST);
        return true;
    }

    public static boolean validateDatabase(String p) { return validateDatabase(p, false); }

    // ------------------------------------------------------------ MFDB-132 chunked package validation

    /** True if a Chunked-104a document is a packaged MFDB-132. */
    public static boolean isMfdb132Package(Map<String, Object> doc) {
        return eqStr(doc.get("Format_Version"), "104a")
                && eqStr(doc.get("Schema_Name"), "MFDB-132")
                && eqStr(doc.get("Package_Format"), "MFDB-Chunked-104a")
                && Json.truthy(doc.get("MFDB_Version"))
                && Json.truthy(doc.get("DB_Name"));
    }

    private static Map<String, Object> verdict(List<Object> errors, List<Object> warnings) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("valid", Boolean.valueOf(errors.isEmpty()));
        m.put("errors", errors);
        m.put("warnings", warnings);
        return m;
    }

    /** Level-1 validation of an MFDB-132 package document; returns {valid, errors, warnings}. */
    public static Map<String, Object> validateMfdb132Package(Map<String, Object> doc) {
        List<Object> errors = new ArrayList<Object>();
        List<Object> warnings = new ArrayList<Object>();

        if (!isMfdb132Package(doc)) {
            errors.add("Document is not a recognized MFDB-132 package "
                    + "(missing/incorrect Schema_Name/Package_Format/MFDB_Version/DB_Name).");
            return verdict(errors, warnings);
        }
        if (!Json.pyEquals(doc.get("Records_Type"), Json.arr("MFDB-132"))) {
            errors.add("Records_Type must be exactly ['MFDB-132'] for an MFDB-132 package.");
        }

        Map<String, Integer> fm = fieldIndexMap(fieldsOf(doc));
        Integer relIdx = fm.get("Relative_Path");
        int idx = relIdx == null ? -1 : relIdx.intValue();
        boolean manifestFound = false;
        Object valsObj = doc.containsKey("Values") ? doc.get("Values") : new ArrayList<Object>();
        List<Object> vals = Json.asList(valsObj);
        if (vals == null) throw new IllegalArgumentException("'" + PyOps.typeName(valsObj) + "' object is not iterable");
        for (Object r : vals) {
            List<Object> row = Json.asList(r);
            if (row == null) throw new IllegalArgumentException("'" + PyOps.typeName(r) + "' object is not subscriptable");
            if (Json.pyEquals(cell(row, idx), MFDB_MANIFEST_FILENAME)) { manifestFound = true; break; }
        }
        if (!manifestFound) {
            errors.add("Chunked package does not contain the MFDB manifest (" + MFDB_MANIFEST_FILENAME
                    + ") — not a complete MFDB package.");
        }
        return verdict(errors, warnings);
    }

    /** Finds the chunk row whose Relative_Path equals relPath; null when absent. */
    public static List<Object> findRowByRelpath(Map<String, Object> doc, String relPath) {
        return findRowByRelpathObj(doc, relPath);
    }

    /** Same as findRowByRelpath but compares with Python == against any JSON value. */
    static List<Object> findRowByRelpathObj(Map<String, Object> doc, Object relPath) {
        Map<String, Integer> fm = fieldIndexMap(fieldsOf(doc));
        Integer relIdx = fm.get("Relative_Path");
        if (relIdx == null) return null;
        Object valsObj = doc.containsKey("Values") ? doc.get("Values") : new ArrayList<Object>();
        List<Object> vals = Json.asList(valsObj);
        if (vals == null) throw new IllegalArgumentException("'" + PyOps.typeName(valsObj) + "' object is not iterable");
        for (Object r : vals) {
            List<Object> row = Json.asList(r);
            if (row == null) throw new IllegalArgumentException("'" + PyOps.typeName(r) + "' object is not subscriptable");
            if (Json.pyEquals(cell(row, relIdx.intValue()), relPath)) return row;
        }
        return null;
    }

    private static Object parseContent(Object content) {
        if (!(content instanceof String)) {
            throw new IllegalArgumentException("the JSON object must be str, bytes or bytearray, not " + PyOps.typeName(content));
        }
        return Json.loads((String) content);
    }

    /** Finds an embedded MFDB inside any Chunked-104a document and validates it; returns a report map. */
    public static Map<String, Object> detectMfdbInChunk(Map<String, Object> doc) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("mfdb_detected", Boolean.FALSE);
        result.put("valid", Boolean.FALSE);
        result.put("db_name", null);
        result.put("mfdb_version", null);
        List<Object> entities = new ArrayList<Object>();
        List<Object> errors = new ArrayList<Object>();
        result.put("entities", entities);
        result.put("errors", errors);
        result.put("warnings", new ArrayList<Object>());

        Map<String, Integer> fm = fieldIndexMap(fieldsOf(doc));
        for (String k : new String[] {"Relative_Path", "File_Content", "Is_Binary"}) {
            if (!fm.containsKey(k)) {
                errors.add("Chunk document is missing required Chunked-104 fields.");
                return result;
            }
        }

        List<Object> manifestRow = findRowByRelpath(doc, MFDB_MANIFEST_FILENAME);
        if (manifestRow == null) {
            errors.add("No manifest (" + MFDB_MANIFEST_FILENAME + ") found in chunk — no MFDB present.");
            return result;
        }
        if (truthyCell(cell(manifestRow, fm.get("Is_Binary").intValue()))) {
            errors.add("Manifest row is flagged Is_Binary — its content was never stored, cannot validate.");
            return result;
        }

        Map<String, Object> manifestDoc;
        try {
            manifestDoc = asDocLenient(parseContent(cell(manifestRow, fm.get("File_Content").intValue())));
        } catch (RuntimeException e) {
            errors.add("Manifest content is not valid JSON: " + e.getMessage());
            return result;
        }

        // Level 1: manifest checks
        result.put("mfdb_detected", Boolean.TRUE);
        result.put("db_name", manifestDoc.get("DB_Name"));
        result.put("mfdb_version", manifestDoc.get("MFDB_Version"));

        if (!eqStr(manifestDoc.get("Format_Version"), "104a")) errors.add("Manifest Format_Version must be '104a'.");
        if (!Json.pyEquals(manifestDoc.get("Records_Type"), Json.arr("mfdb"))) {
            errors.add("Manifest Records_Type must be exactly ['mfdb'].");
        }

        Map<String, Integer> manifestFm = fieldIndexMap(fieldsOf(manifestDoc));
        if (!manifestFm.containsKey("entity_name") || !manifestFm.containsKey("file_path")) {
            errors.add("Manifest Fields must include 'entity_name' and 'file_path'.");
            return result;
        }

        Set<String> seenNames = new HashSet<String>();
        Set<String> seenPaths = new HashSet<String>();
        Object mvals = manifestDoc.containsKey("Values") ? manifestDoc.get("Values") : new ArrayList<Object>();
        List<Object> mrows = Json.asList(mvals);
        if (mrows == null) throw new IllegalArgumentException("'" + PyOps.typeName(mvals) + "' object is not iterable");

        // Level 2: per-entity checks
        for (Object er : mrows) {
            List<Object> entityRow = Json.asList(er);
            if (entityRow == null) throw new IllegalArgumentException("'" + PyOps.typeName(er) + "' object is not subscriptable");
            Object entityName = cell(entityRow, manifestFm.get("entity_name").intValue());
            Object filePath = cell(entityRow, manifestFm.get("file_path").intValue());
            Map<String, Object> ent = new LinkedHashMap<String, Object>();
            List<Object> entErrors = new ArrayList<Object>();
            ent.put("entity_name", entityName);
            ent.put("file_path", filePath);
            ent.put("found_in_chunk", Boolean.FALSE);
            ent.put("valid", Boolean.FALSE);
            ent.put("errors", entErrors);

            if (!Json.truthy(entityName) || !Json.truthy(filePath)) entErrors.add("entity_name/file_path must not be null.");
            if (seenNames.contains(hashKey(entityName))) {
                entErrors.add("Duplicate entity_name '" + strOf(entityName) + "' in manifest.");
            }
            if (seenPaths.contains(hashKey(filePath))) {
                entErrors.add("Duplicate file_path '" + strOf(filePath) + "' in manifest.");
            }
            seenNames.add(hashKey(entityName));
            seenPaths.add(hashKey(filePath));

            List<Object> chunkRow = findRowByRelpathObj(doc, filePath);
            if (chunkRow == null) {
                entErrors.add("Entity file '" + strOf(filePath) + "' listed in manifest was not found in chunk.");
                entities.add(ent);
                continue;
            }

            ent.put("found_in_chunk", Boolean.TRUE);
            if (truthyCell(cell(chunkRow, fm.get("Is_Binary").intValue()))) {
                entErrors.add("Entity row is flagged Is_Binary — content was never stored, cannot validate.");
                entities.add(ent);
                continue;
            }

            Map<String, Object> entityDoc;
            try {
                entityDoc = asDocLenient(parseContent(cell(chunkRow, fm.get("File_Content").intValue())));
            } catch (RuntimeException e) {
                entErrors.add("Entity file content is not valid JSON: " + e.getMessage());
                entities.add(ent);
                continue;
            }

            if (!eqStr(entityDoc.get("Format_Version"), "104")) entErrors.add("Entity Format_Version must be '104'.");
            if (!Json.pyEquals(entityDoc.get("Records_Type"), Json.arr(entityName))) {
                entErrors.add("Entity Records_Type must be exactly ['" + strOf(entityName) + "'].");
            }
            if (!entityDoc.containsKey("Parent_Hierarchy")) {
                entErrors.add("Entity is missing mandatory 'Parent_Hierarchy' key.");
            }
            ent.put("valid", Boolean.valueOf(entErrors.isEmpty()));
            entities.add(ent);
        }

        boolean allValid = true;
        for (Object e : entities) {
            if (!Boolean.TRUE.equals(Json.asMap(e).get("valid"))) { allValid = false; break; }
        }
        result.put("valid", Boolean.valueOf(errors.isEmpty() && allValid));
        return result;
    }

    /**
     * Inside the Python try-block json.loads may return a non-dict; the following manifest_doc.get() calls then raise
     * AttributeError outside the try. Reproduced by throwing out of the caller's catch is not possible, so a
     * non-map result is returned wrapped and re-raised at first access.
     */
    private static Map<String, Object> asDocLenient(Object parsed) {
        if (parsed instanceof Map) return asDoc(parsed);
        return new NonDoc(parsed);
    }

    /** Map that raises Python's AttributeError on first use, mirroring a non-dict json.loads result. */
    private static final class NonDoc extends LinkedHashMap<String, Object> {
        private static final long serialVersionUID = 1L;
        private final String typeName;

        NonDoc(Object o) { this.typeName = PyOps.typeName(o); }

        private RuntimeException err() {
            return new IllegalArgumentException("'" + typeName + "' object has no attribute 'get'");
        }

        @Override public Object get(Object k) { throw err(); }

        @Override public boolean containsKey(Object k) { throw err(); }
    }
}
