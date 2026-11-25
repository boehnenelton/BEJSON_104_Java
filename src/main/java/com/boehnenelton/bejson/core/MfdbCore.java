/*
 * File Name:       MfdbCore.java
 * Relative Path:   src/main/java/com/boehnenelton/bejson/core/MfdbCore.java
 * Description:     Port of lib_bejson_Core_mfdb_core.py 2.3.5: multi-file database orchestrator - discovery, deep verify,
 *                  self-heal, smart repair, entity CRUD under PID locks, queries/joins, database creation, federation,
 *                  and the debug/meta log. (Archive mounting lives in MfdbArchive.)
 * Architecture:    MFDB = one BEJSON 104a manifest (104a.mfdb.bejson, Records_Type ["mfdb"]) registering many BEJSON 104
 *                  entity files, each with Parent_Hierarchy pointing back to the manifest. Not 104db.
 * Format_Scope:    MFDB only
 * VERSION:         1.0.0 (reference 2.3.5)
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   619e2713-1fb5-4da1-baaa-bf7b616456d0
 */
package com.boehnenelton.bejson.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class MfdbCore {
    private MfdbCore() { }

    public static final String VERSION = "2.3.5";
    private static final Logger LOG = Logger.getLogger("lib_bejson_Core_mfdb_core");

    /** Single-entity row filter (Python: Callable[[dict], bool]). */
    public interface RowPredicate {
        boolean test(Map<String, Object> row);
    }

    // ------------------------------------------------------------------ internal helpers

    private static Map<String, Object> loadJsonMap(String path) {
        return Json.asMap(MfdbValidator.loadJson(path));
    }

    static List<Map<String, Object>> getManifestEntries(String manifestPath) {
        return MfdbValidator.rowsAsDicts(loadJsonMap(manifestPath));
    }

    static Map<String, Object> getManifestEntry(String manifestPath, String entityName) {
        for (Map<String, Object> e : getManifestEntries(manifestPath)) {
            if (Json.pyEquals(e.get("entity_name"), entityName)) return e;
        }
        throw new MfdbCoreException("Entity '" + entityName + "' not found in manifest: " + manifestPath,
                BejsonErrors.E_MFDB_CORE_ENTITY_NOT_FOUND);
    }

    /** Reads a text file, or one file inside a .zip on the path; null on failure. */
    static String readFileContent(String path) {
        try {
            File p = new File(path);
            if (p.isFile() && !path.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                return PyFs.readText(path);
            }
            String norm = PyFs.normpath(path);
            boolean abs = norm.startsWith("/");
            String[] parts = norm.split("/");
            List<String> segs = new ArrayList<String>();
            for (String s : parts) if (!s.isEmpty()) segs.add(s);
            for (int i = 0; i < segs.size(); i++) {
                if (segs.get(i).toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    StringBuilder zp = new StringBuilder(abs ? "/" : "");
                    for (int k = 0; k <= i; k++) {
                        if (k > 0) zp.append('/');
                        zp.append(segs.get(k));
                    }
                    StringBuilder inner = new StringBuilder();
                    for (int k = i + 1; k < segs.size(); k++) {
                        if (k > i + 1) inner.append('/');
                        inner.append(segs.get(k));
                    }
                    String zipPath = zp.toString();
                    if (new File(zipPath).exists()) {
                        java.util.zip.ZipFile z = new java.util.zip.ZipFile(zipPath);
                        try {
                            String innerName = inner.toString();
                            java.util.zip.ZipEntry e = z.getEntry(innerName);
                            if (e != null && !innerName.isEmpty()) {
                                return PyFs.decodeUtf8Strict(readAll(z.getInputStream(e)), zipPath);
                            } else if (innerName.isEmpty()) {
                                java.util.zip.ZipEntry m = z.getEntry("104a.mfdb.bejson");
                                if (m != null) return PyFs.decodeUtf8Strict(readAll(z.getInputStream(m)), zipPath);
                            }
                        } finally {
                            z.close();
                        }
                    }
                }
            }
            if (!p.exists()) return null;
            return PyFs.readText(path);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            BejsonPathGuard.copyBounded(in, bos, new BejsonPathGuard.Budget(64L << 20)); // in-memory reads: 64 MiB
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    static String getEntityPath(String manifestPath, String entityName) {
        Map<String, Object> entry = getManifestEntry(manifestPath, entityName);
        if (!entry.containsKey("file_path")) throw new IllegalStateException("KeyError: 'file_path'");
        String rel = Json.pyStr(entry.get("file_path"));
        if (BejsonPathGuard.mfdbEscapesRoot(rel)) {
            throw new IllegalArgumentException("[E_MFDB_PATH_ESCAPE] Entity file_path escapes the MFDB root: " + rel);
        }
        return MfdbValidator.resolveEntityPath(manifestPath, rel);
    }

    static Map<String, Object> loadEntityDoc(String manifestPath, String entityName) {
        String entityPath = getEntityPath(manifestPath, entityName);
        String content = readFileContent(entityPath);
        if (content == null) {
            throw new MfdbCoreException("Failed to read entity file: " + entityName + " (" + entityPath + ")",
                    BejsonErrors.E_MFDB_CORE_ENTITY_NOT_FOUND);
        }
        Map<String, Object> doc = BejsonCore.loadString(content);
        if (doc == null) {
            throw new MfdbCoreException("Failed to load entity doc: " + entityName + " (" + entityPath + ")",
                    BejsonErrors.E_MFDB_CORE_ENTITY_NOT_FOUND);
        }
        return doc;
    }

    static void writeEntityDoc(Map<String, Object> doc, String entityPath) {
        if (!BejsonCore.atomicWrite(entityPath, doc)) {
            throw new MfdbCoreException("Failed to write entity doc to " + entityPath, BejsonErrors.E_MFDB_CORE_WRITE_FAILED);
        }
    }

    static void writeManifestDoc(Map<String, Object> doc, String manifestPath) {
        if (!BejsonCore.atomicWrite(manifestPath, doc)) {
            throw new MfdbCoreException("Failed to write manifest doc to " + manifestPath, BejsonErrors.E_MFDB_CORE_WRITE_FAILED);
        }
    }

    static void writeChecked(Map<String, Object> doc, String path, String label) {
        if (!BejsonCore.atomicWrite(path, doc)) {
            throw new MfdbCoreException("Failed to write " + label + " to " + path, BejsonErrors.E_MFDB_CORE_WRITE_FAILED);
        }
    }

    static void writeChecked(Map<String, Object> doc, String path) { writeChecked(doc, path, "file"); }

    private static int fieldNameIndex(List<Object> fields, String name) {
        for (int i = 0; i < fields.size(); i++) {
            Map<String, Object> f = Json.asMap(fields.get(i));
            if (f == null || !f.containsKey("name")) throw new IllegalStateException("KeyError: 'name'");
            if (Json.pyEquals(f.get("name"), name)) return i;
        }
        return -1;
    }

    /** Writes a corrected record_count for ONE entity into the manifest row. */
    static void updateManifestRecordCount(String manifestPath, String entityName, Object count) {
        // Serialize the manifest read-modify-write across entities (entity locks alone do not cover it). Lock order: entity -> manifest.
        ResilientPidLock mlock = ResilientPidLock.enter(manifestPath, 10);
        try {
            updateManifestRecordCountLocked(manifestPath, entityName, count);
        } finally {
            mlock.close();
        }
    }

    private static void updateManifestRecordCountLocked(String manifestPath, String entityName, Object count) {
        Map<String, Object> doc = loadJsonMap(manifestPath);
        List<Object> fields = Json.asList(doc.get("Fields"));
        int rc = fieldNameIndex(fields, "record_count");
        int en = fieldNameIndex(fields, "entity_name");
        if (rc < 0 || en < 0) return;
        for (Object rowObj : Json.asList(doc.get("Values"))) {
            List<Object> row = Json.asList(rowObj);
            if (Json.pyEquals(row.get(en), entityName)) {
                row.set(rc, count);
                break;
            }
        }
        writeManifestDoc(doc, manifestPath);
    }

    /** SHA-256 of a file for archive integrity checks. */
    static String calculateFileHash(String filePath) {
        return PyFs.sha256File(filePath);
    }

    // ------------------------------------------------------------------ discovery

    /** 'manifest' | 'entity' | 'archive' | 'standalone'. */
    public static String discover(String filePath) {
        File p = new File(filePath);
        if (!p.exists()) {
            throw new MfdbCoreException("File not found: " + filePath, BejsonErrors.E_MFDB_CORE_MANIFEST_NOT_FOUND);
        }
        String name = p.getName();
        if (name.endsWith(".zip") && name.contains(".mfdb")) return "archive";
        Map<String, Object> doc;
        try {
            doc = loadJsonMap(filePath);
            if (doc == null) throw new IllegalStateException("not a dict");
        } catch (RuntimeException e) {
            return "standalone";
        }
        Object version = doc.containsKey("Format_Version") ? doc.get("Format_Version") : "";
        if ("104a".equals(version) && name.endsWith(".mfdb.bejson")) return "manifest";
        if ("104".equals(version) && Json.truthy(doc.get("Parent_Hierarchy"))) return "entity";
        return "standalone";
    }

    // ------------------------------------------------------------------ recovery and repair

    private static Map<String, Object> finding(String entity) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("entity", entity);
        return m;
    }

    /** Full audit; returns findings (empty list = healthy). */
    public static List<Map<String, Object>> deepVerify(String manifestPath) {
        List<Map<String, Object>> findings = new ArrayList<Map<String, Object>>();
        Map<String, Object> manifestDoc = BejsonCore.loadFile(manifestPath);
        if (manifestDoc == null) throw new IllegalStateException("'NoneType' object is not subscriptable");
        List<Map<String, Object>> entries = MfdbValidator.rowsAsDicts(manifestDoc);

        for (Map<String, Object> entry : entries) {
            Object entityName = entry.get("entity_name");
            Object filePathRel = entry.get("file_path");
            Object expectedCount = entry.get("record_count");
            String en = entityName == null ? null : Json.pyStr(entityName);

            String entityPath = MfdbValidator.resolveEntityPath(manifestPath, Json.pyStr(filePathRel));
            if (!new File(entityPath).exists()) {
                Map<String, Object> f = finding(en);
                f.put("entity", entityName);
                f.put("error", "MISSING_FILE");
                f.put("path", filePathRel);
                findings.add(f);
                continue;
            }
            try {
                Map<String, Object> entityDoc = BejsonCore.loadFile(entityPath);
                if (entityDoc == null) throw new IllegalStateException("'NoneType' object has no attribute 'get'");
                List<Object> fields = entityDoc.containsKey("Fields") ? Json.asList(entityDoc.get("Fields")) : new ArrayList<Object>();
                int fieldCount = fields.size();
                List<Object> values = entityDoc.containsKey("Values") ? Json.asList(entityDoc.get("Values")) : new ArrayList<Object>();
                int actualCount = values.size();

                if (expectedCount != null && !Json.pyEquals(expectedCount, Long.valueOf(actualCount))) {
                    Map<String, Object> f = finding(en);
                    f.put("entity", entityName);
                    f.put("warning", "COUNT_MISMATCH");
                    f.put("expected", expectedCount);
                    f.put("actual", Long.valueOf(actualCount));
                    findings.add(f);
                }
                for (int i = 0; i < values.size(); i++) {
                    List<Object> row = Json.asList(values.get(i));
                    if (row.size() != fieldCount) {
                        Map<String, Object> f = finding(en);
                        f.put("entity", entityName);
                        f.put("error", "POSITIONAL_VIOLATION");
                        f.put("row", Long.valueOf(i));
                        f.put("expected", Long.valueOf(fieldCount));
                        f.put("actual", Long.valueOf(row.size()));
                        findings.add(f);
                    }
                    for (int j = 0; j < row.size(); j++) {
                        Object val = row.get(j);
                        if (val == null) continue;
                        if (j >= fields.size()) throw new IndexOutOfBoundsException("list index out of range");
                        Map<String, Object> fd = Json.asMap(fields.get(j));
                        Object fType = fd.get("type");
                        String expected = null;
                        if ("integer".equals(fType) && !Json.pyIsInt(val)) expected = "integer";
                        else if ("number".equals(fType) && !(Json.pyIsInt(val) || Json.isFloat(val))) expected = "number";
                        else if ("boolean".equals(fType) && !(val instanceof Boolean)) expected = "boolean";
                        if (expected != null) {
                            Map<String, Object> f = finding(en);
                            f.put("entity", entityName);
                            f.put("warning", "TYPE_MISMATCH");
                            f.put("row", Long.valueOf(i));
                            f.put("field", fd.get("name"));
                            f.put("expected", expected);
                            f.put("actual", PyOps.typeName(val));
                            findings.add(f);
                        }
                    }
                }
            } catch (RuntimeException e) {
                Map<String, Object> f = finding(en);
                f.put("entity", entityName);
                f.put("error", "CORRUPT_JSON");
                f.put("message", String.valueOf(e.getMessage()));
                findings.add(f);
            }
        }
        return findings;
    }

    /** Applies safe automatic fixes for deepVerify findings, one finding at a time. */
    public static Map<String, Object> selfHeal(String manifestPath) {
        List<Object> actions = new ArrayList<Object>();
        List<Object> remaining = new ArrayList<Object>();
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("actions", actions);
        report.put("remaining_errors", remaining);
        List<Map<String, Object>> findings = deepVerify(manifestPath);

        for (Map<String, Object> f : findings) {
            Object entityObj = f.get("entity");
            String entity = entityObj == null ? "None" : Json.pyStr(entityObj);
            try {
                if ("COUNT_MISMATCH".equals(f.get("warning"))) {
                    Object actual = f.get("actual");
                    if (actual == null) {
                        remaining.add("Skipped COUNT_MISMATCH heal for " + entity + ": finding has no 'actual' value");
                        continue;
                    }
                    updateManifestRecordCount(manifestPath, entity, actual);
                    actions.add("Resynced record_count for " + entity + " to " + Json.pyStr(actual));
                } else if ("POSITIONAL_VIOLATION".equals(f.get("error"))) {
                    String entityPath = getEntityPath(manifestPath, entity);
                    try {
                        Map<String, Object> doc = BejsonCore.loadFile(entityPath);
                        int fieldCount = Json.asList(doc.get("Fields")).size();
                        int repaired = 0;
                        List<Object> vals = Json.asList(doc.get("Values"));
                        for (int i = 0; i < vals.size(); i++) {
                            List<Object> row = Json.asList(vals.get(i));
                            if (row.size() < fieldCount) {
                                List<Object> nr = new ArrayList<Object>(row);
                                while (nr.size() < fieldCount) nr.add(null);
                                vals.set(i, nr);
                                repaired++;
                            } else if (row.size() > fieldCount) {
                                vals.set(i, new ArrayList<Object>(row.subList(0, fieldCount)));
                                repaired++;
                            }
                        }
                        if (repaired > 0) {
                            writeEntityDoc(doc, entityPath);
                            actions.add("Repaired " + repaired + " positional violations in " + entity);
                        }
                    } catch (RuntimeException e) {
                        remaining.add("Failed to repair " + entity + ": " + e.getMessage());
                    }
                } else if ("MISSING_FILE".equals(f.get("error"))) {
                    String mountDir = PyFs.dirname(PyFs.abspath(manifestPath));
                    Object fpath = f.get("path");
                    if (fpath == null) {
                        remaining.add("Skipped resurrection for " + entity + ": finding has no 'path' value");
                        continue;
                    }
                    if (MfdbArchive.resurrectFile(mountDir, Json.pyStr(fpath))) {
                        actions.add("Resurrected missing entity file: " + Json.pyStr(fpath));
                    } else {
                        remaining.add("Could not resurrect " + entity);
                    }
                } else if (Json.truthy(f.get("error"))) {
                    Object msg = f.containsKey("message") ? f.get("message") : "";
                    remaining.add(entity + ": " + Json.pyStr(f.get("error")) + " - " + Json.pyStr(msg));
                }
            } catch (RuntimeException e) {
                remaining.add("Unexpected error healing finding for " + entity + ": " + e.getMessage());
            }
        }
        return report;
    }

    static boolean repairHierarchy(String entityPath, String newHierarchy) {
        try {
            Map<String, Object> doc = BejsonCore.loadFile(entityPath);
            doc.put("Parent_Hierarchy", newHierarchy);
            writeEntityDoc(doc, entityPath);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Repairs the workspace from a specific MFDB validation error; true if a repair was applied. */
    public static boolean smartRepair(String manifestPath, MfdbValidator.MfdbValidationException error) {
        String mountDir = PyFs.dirname(PyFs.abspath(manifestPath));
        Map<String, Object> ctx = error.context;
        if (error.code == BejsonErrors.E_MFDB_ENTITY_NOT_FOUND || error.code == 33) {
            Object rel = ctx.get("file_path_rel");
            if (Json.truthy(rel)) return MfdbArchive.resurrectFile(mountDir, Json.pyStr(rel));
        }
        if (error.code == BejsonErrors.E_MFDB_BIDIRECTIONAL_FAIL || error.code == BejsonErrors.E_MFDB_MANIFEST_NOT_FOUND) {
            Object entityPath = ctx.get("actual_path");
            Object newH = ctx.get("suggested_hierarchy");
            if (!Json.truthy(newH) && Json.truthy(entityPath)) newH = "../104a.mfdb.bejson";
            if (Json.truthy(entityPath) && Json.truthy(newH)) {
                return repairHierarchy(Json.pyStr(entityPath), Json.pyStr(newH));
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ read operations

    /** Validates and loads the manifest; one field-name keyed map per registered entity. */
    public static List<Map<String, Object>> loadManifest(String manifestPath) {
        MfdbValidator.validateManifest(manifestPath);
        Map<String, Object> doc = loadJsonMap(manifestPath);
        if (doc == null) {
            throw new MfdbCoreException("Failed to load manifest: " + manifestPath + " (not a dict)",
                    BejsonErrors.E_MFDB_CORE_MANIFEST_NOT_FOUND);
        }
        return MfdbValidator.rowsAsDicts(doc);
    }

    /** All records of one entity as field-name keyed maps (logs a READ entry when Debug_Reads is on). */
    public static List<Map<String, Object>> loadEntity(String manifestPath, String entityName) {
        long t0 = System.nanoTime();
        Map<String, Object> doc = loadEntityDoc(manifestPath, entityName);
        List<Map<String, Object>> result = MfdbValidator.rowsAsDicts(doc);
        metaLog(manifestPath, "READ", entityName, null, null, null, true,
                (int) ((System.nanoTime() - t0) / 1000000L), "rows_returned=" + result.size(), true);
        return result;
    }

    /** Raw BEJSON 104 document of an entity file. */
    public static Map<String, Object> getEntityDoc(String manifestPath, String entityName) {
        return loadEntityDoc(manifestPath, entityName);
    }

    /** Summary of the whole MFDB. */
    public static Map<String, Object> getStats(String manifestPath) {
        Map<String, Object> doc = loadJsonMap(manifestPath);
        List<Map<String, Object>> entries = MfdbValidator.rowsAsDicts(doc);
        List<Object> entityStats = new ArrayList<Object>();
        for (Map<String, Object> entry : entries) {
            if (!entry.containsKey("file_path")) throw new IllegalStateException("KeyError: 'file_path'");
            String resolved = MfdbValidator.resolveEntityPath(manifestPath, Json.pyStr(entry.get("file_path")));
            long recCount;
            long fieldCount;
            if (new File(resolved).exists()) {
                Map<String, Object> edoc = loadJsonMap(resolved);
                List<Object> v = edoc.containsKey("Values") ? Json.asList(edoc.get("Values")) : new ArrayList<Object>();
                List<Object> f = edoc.containsKey("Fields") ? Json.asList(edoc.get("Fields")) : new ArrayList<Object>();
                recCount = v.size();
                fieldCount = f.size();
            } else {
                recCount = -1;
                fieldCount = -1;
            }
            if (!entry.containsKey("entity_name")) throw new IllegalStateException("KeyError: 'entity_name'");
            Map<String, Object> es = new LinkedHashMap<String, Object>();
            es.put("entity_name", entry.get("entity_name"));
            es.put("file_path", entry.get("file_path"));
            es.put("record_count", Long.valueOf(recCount));
            es.put("field_count", Long.valueOf(fieldCount));
            es.put("primary_key", entry.get("primary_key"));
            entityStats.add(es);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("db_name", doc.containsKey("DB_Name") ? doc.get("DB_Name") : "");
        out.put("schema_version", doc.containsKey("Schema_Version") ? doc.get("Schema_Version") : "");
        out.put("entity_count", Long.valueOf(entries.size()));
        out.put("entities", entityStats);
        return out;
    }

    // ------------------------------------------------------------------ query operations

    /** Rows of an entity for which predicate is true. */
    public static List<Map<String, Object>> queryEntity(String manifestPath, String entityName, RowPredicate predicate) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : loadEntity(manifestPath, entityName)) if (predicate.test(r)) out.add(r);
        return out;
    }

    /** Python dict-key normalisation: True == 1 == 1.0. */
    static Object pyKey(Object k) {
        if (k instanceof Boolean) return Long.valueOf(((Boolean) k).booleanValue() ? 1L : 0L);
        if (k instanceof Double) {
            double d = (Double) k;
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 9.0e18) return Long.valueOf((long) d);
        }
        return k;
    }

    /** In-memory {field value -> row} index (rows whose field is null are skipped). */
    public static Map<Object, Map<String, Object>> buildIndex(String manifestPath, String entityName, String fieldName) {
        Map<Object, Map<String, Object>> idx = new LinkedHashMap<Object, Map<String, Object>>();
        for (Map<String, Object> r : loadEntity(manifestPath, entityName)) {
            if (r.get(fieldName) != null) idx.put(pyKey(r.get(fieldName)), r);
            else if (!r.containsKey(fieldName)) { /* Python r.get() is None -> skipped */ }
        }
        return idx;
    }

    /** Application-level join: each from-row merged with the matching to-row's fields prefixed "<to>__". */
    public static List<Map<String, Object>> join(String manifestPath, String fromEntity, String toEntity,
                                                 String fromFk, String toPk) {
        List<Map<String, Object>> fromRecords = loadEntity(manifestPath, fromEntity);
        Map<Object, Map<String, Object>> toIndex = buildIndex(manifestPath, toEntity, toPk);
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> record : fromRecords) {
            Object fkVal = record.get(fromFk);
            Map<String, Object> target = fkVal == null ? null : toIndex.get(pyKey(fkVal));
            Map<String, Object> merged = new LinkedHashMap<String, Object>(record);
            if (target != null) {
                for (Map.Entry<String, Object> e : target.entrySet()) merged.put(toEntity + "__" + e.getKey(), e.getValue());
            }
            results.add(merged);
        }
        return results;
    }

    // ------------------------------------------------------------------ write operations

    private static int elapsedMs(long t0) {
        return (int) ((System.nanoTime() - t0) / 1000000L);
    }

    /** Appends one row to an entity file and syncs the manifest record_count; returns the entity document. */
    public static Map<String, Object> addEntityRecord(String manifestPath, String entityName, List<Object> values,
                                                      boolean syncCount) {
        long t0 = System.nanoTime();
        boolean success = false;
        String entityPath = getEntityPath(manifestPath, entityName);
        try {
            ResilientPidLock lock = ResilientPidLock.enter(entityPath, 10);
            Map<String, Object> doc;
            try {
                doc = loadEntityDoc(manifestPath, entityName);
                if (!BejsonCore.addRecord(doc, values)) {
                    throw new BejsonCoreException("Failed to add record to " + entityName);
                }
                writeEntityDoc(doc, entityPath);
                if (syncCount) {
                    updateManifestRecordCount(manifestPath, entityName, Long.valueOf(Json.asList(doc.get("Values")).size()));
                }
            } finally {
                lock.close();
            }
            success = true;
            return doc;
        } finally {
            metaLog(manifestPath, "ADD", entityName, null, null, null, success, elapsedMs(t0),
                    success ? "" : "add_record returned False");
        }
    }

    public static Map<String, Object> addEntityRecord(String manifestPath, String entityName, List<Object> values) {
        return addEntityRecord(manifestPath, entityName, values, true);
    }

    /** Removes the row at recordIndex and syncs the manifest record_count. */
    public static Map<String, Object> removeEntityRecord(String manifestPath, String entityName, int recordIndex,
                                                         boolean syncCount) {
        long t0 = System.nanoTime();
        boolean success = false;
        String entityPath = getEntityPath(manifestPath, entityName);
        try {
            ResilientPidLock lock = ResilientPidLock.enter(entityPath, 10);
            Map<String, Object> doc;
            try {
                doc = loadEntityDoc(manifestPath, entityName);
                if (!BejsonCore.removeRecord(doc, recordIndex)) {
                    throw new BejsonCoreException("Failed to remove record " + recordIndex + " from " + entityName);
                }
                writeEntityDoc(doc, entityPath);
                if (syncCount) {
                    updateManifestRecordCount(manifestPath, entityName, Long.valueOf(Json.asList(doc.get("Values")).size()));
                }
            } finally {
                lock.close();
            }
            success = true;
            return doc;
        } finally {
            metaLog(manifestPath, "REMOVE", entityName, null, null, Integer.valueOf(recordIndex), success, elapsedMs(t0), "");
        }
    }

    public static Map<String, Object> removeEntityRecord(String manifestPath, String entityName, int recordIndex) {
        return removeEntityRecord(manifestPath, entityName, recordIndex, true);
    }

    private static boolean hasFieldNamed(Map<String, Object> doc, String name) {
        List<Object> fields = doc.containsKey("Fields") ? Json.asList(doc.get("Fields")) : new ArrayList<Object>();
        for (Object f : fields) {
            Map<String, Object> fm = Json.asMap(f);
            if (fm == null || !fm.containsKey("name")) throw new IllegalStateException("KeyError: 'name'");
            if (Json.pyEquals(fm.get("name"), name)) return true;
        }
        return false;
    }

    /** Updates one field of one row (resolved through the field map). */
    public static Map<String, Object> updateEntityRecord(String manifestPath, String entityName, int recordIndex,
                                                         String fieldName, Object newValue) {
        long t0 = System.nanoTime();
        boolean success = false;
        Boolean fieldExists = null;
        String entityPath = getEntityPath(manifestPath, entityName);
        try {
            ResilientPidLock lock = ResilientPidLock.enter(entityPath, 10);
            Map<String, Object> doc;
            try {
                doc = loadEntityDoc(manifestPath, entityName);
                fieldExists = Boolean.valueOf(hasFieldNamed(doc, fieldName));
                if (!BejsonCore.updateField(doc, recordIndex, fieldName, newValue)) {
                    throw new BejsonCoreException("Failed to update field '" + fieldName + "' in " + entityName);
                }
                writeEntityDoc(doc, entityPath);
            } finally {
                lock.close();
            }
            success = true;
            return doc;
        } finally {
            boolean fe = fieldExists != null && fieldExists.booleanValue();
            metaLog(manifestPath, "UPDATE", entityName, fieldName, fieldExists, Integer.valueOf(recordIndex), success,
                    elapsedMs(t0), fe ? "" : "SCHEMA DRIFT: field '" + fieldName + "' not in Fields[]");
        }
    }

    /** Updates several fields of one row in a single atomic write. */
    public static Map<String, Object> updateEntityRecordBulk(String manifestPath, String entityName, int recordIndex,
                                                             Map<String, Object> updates) {
        long t0 = System.nanoTime();
        boolean success = false;
        List<Object> missing = new ArrayList<Object>();
        String entityPath = getEntityPath(manifestPath, entityName);
        try {
            ResilientPidLock lock = ResilientPidLock.enter(entityPath, 10);
            Map<String, Object> doc;
            try {
                doc = loadEntityDoc(manifestPath, entityName);
                for (String k : updates.keySet()) if (!hasFieldNamed(doc, k)) missing.add(k);
                for (Map.Entry<String, Object> e : updates.entrySet()) {
                    if (!BejsonCore.updateField(doc, recordIndex, e.getKey(), e.getValue())) {
                        throw new BejsonCoreException("Failed to update field '" + e.getKey() + "' in " + entityName);
                    }
                }
                writeEntityDoc(doc, entityPath);
            } finally {
                lock.close();
            }
            success = true;
            return doc;
        } finally {
            String notes = missing.isEmpty() ? "" : "SCHEMA DRIFT: unknown fields " + Json.pyRepr(missing);
            StringBuilder names = new StringBuilder();
            boolean first = true;
            for (String k : updates.keySet()) {
                if (!first) names.append(',');
                first = false;
                names.append(k);
            }
            metaLog(manifestPath, "UPDATE_BULK", entityName, names.toString(), Boolean.valueOf(missing.isEmpty()),
                    Integer.valueOf(recordIndex), success, elapsedMs(t0), notes);
        }
    }

    // ------------------------------------------------------------------ manifest sync

    /** Re-counts the real rows of one entity file and writes the count into the manifest. */
    public static int syncManifestCount(String manifestPath, String entityName) {
        String entityPath = getEntityPath(manifestPath, entityName);
        Map<String, Object> edoc = loadJsonMap(entityPath);
        int count = edoc.containsKey("Values") ? Json.asList(edoc.get("Values")).size() : 0;
        updateManifestRecordCount(manifestPath, entityName, Long.valueOf(count));
        return count;
    }

    /** syncManifestCount for every entity; returns {entity_name: count}. */
    public static Map<String, Object> syncAllCounts(String manifestPath) {
        Map<String, Object> results = new LinkedHashMap<String, Object>();
        for (Map<String, Object> entry : getManifestEntries(manifestPath)) {
            if (!entry.containsKey("entity_name")) throw new IllegalStateException("KeyError: 'entity_name'");
            String name = Json.pyStr(entry.get("entity_name"));
            results.put(name, Long.valueOf(syncManifestCount(manifestPath, name)));
        }
        return results;
    }

    // ------------------------------------------------------------------ database creation

    /** Creates a new empty entity file and registers it in the manifest; returns the resolved entity path. */
    public static String createEntityFile(String manifestPath, String entityName, List<Object> fields,
                                          String description, String primaryKey, String schemaVersion,
                                          String filePathRel) {
        String manifestDir = PyFs.dirname(PyFs.abspath(manifestPath));
        if (filePathRel == null || filePathRel.isEmpty()) {
            filePathRel = "data/" + entityName.toLowerCase(Locale.ROOT) + ".bejson";
        }
        String resolved = PyFs.normpath(PyFs.join(manifestDir, filePathRel));
        PyFs.makedirs(PyFs.dirname(resolved));
        String entityDir = PyFs.dirname(resolved);
        String relToManifest = PyFs.relpath(manifestPath, entityDir);

        Map<String, Object> entityDoc = new LinkedHashMap<String, Object>();
        entityDoc.put("Format", "BEJSON");
        entityDoc.put("Format_Version", "104");
        entityDoc.put("Format_Creator", "Elton Boehnen");
        entityDoc.put("Parent_Hierarchy", relToManifest);
        entityDoc.put("Records_Type", Json.arr(entityName));
        entityDoc.put("Fields", fields);
        entityDoc.put("Values", new ArrayList<Object>());
        writeEntityDoc(entityDoc, resolved);

        Map<String, Object> manifestDoc = loadJsonMap(manifestPath);
        List<Object> newRow = new ArrayList<Object>();
        for (Object f : Json.asList(manifestDoc.get("Fields"))) {
            Map<String, Object> fm = Json.asMap(f);
            if (fm == null || !fm.containsKey("name")) throw new IllegalStateException("KeyError: 'name'");
            String fn = Json.pyStr(fm.get("name"));
            if (fn.equals("entity_name")) newRow.add(entityName);
            else if (fn.equals("file_path")) newRow.add(filePathRel);
            else if (fn.equals("description")) newRow.add(description == null || description.isEmpty() ? null : description);
            else if (fn.equals("record_count")) newRow.add(Long.valueOf(0));
            else if (fn.equals("schema_version")) newRow.add(schemaVersion);
            else if (fn.equals("primary_key")) newRow.add(primaryKey == null || primaryKey.isEmpty() ? null : primaryKey);
            else newRow.add(null);
        }
        Json.asList(manifestDoc.get("Values")).add(newRow);
        writeManifestDoc(manifestDoc, manifestPath);
        return resolved;
    }

    public static String createEntityFile(String manifestPath, String entityName, List<Object> fields) {
        return createEntityFile(manifestPath, entityName, fields, "", "", "1.0", "");
    }

    /** Creates a new MFDB on disk (manifest + one 104 entity file per entity definition); returns the manifest path. */
    public static String createDatabase(String rootDir, String dbName, List<Map<String, Object>> entities,
                                        String dbDescription, String schemaVersion, String author, String mfdbVersion,
                                        String networkRole, boolean debugMode, int debugRowCap, boolean debugReads) {
        PyFs.makedirs(rootDir);
        String manifestPath = PyFs.normpath(PyFs.join(rootDir, "104a.mfdb.bejson"));

        List<Object> manifestFields = new ArrayList<Object>();
        String[][] mf = {{"entity_name", "string"}, {"file_path", "string"}, {"description", "string"},
            {"record_count", "integer"}, {"schema_version", "string"}, {"primary_key", "string"}};
        for (String[] f : mf) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("name", f[0]);
            m.put("type", f[1]);
            manifestFields.add(m);
        }

        List<Object> manifestValues = new ArrayList<Object>();
        List<Object[]> defs = new ArrayList<Object[]>();
        for (Map<String, Object> entity : entities) {
            if (!entity.containsKey("name")) throw new IllegalStateException("KeyError: 'name'");
            String name = Json.pyStr(entity.get("name"));
            Object fpRel = entity.containsKey("file_path") ? entity.get("file_path")
                    : "data/" + name.toLowerCase(Locale.ROOT) + ".bejson";
            Object desc = entity.containsKey("description") ? entity.get("description") : "";
            Object pk = entity.containsKey("primary_key") ? entity.get("primary_key") : "";
            Object sv = entity.containsKey("schema_version") ? entity.get("schema_version") : "1.0";
            if (!entity.containsKey("fields")) throw new IllegalStateException("KeyError: 'fields'");
            Object fields = entity.get("fields");
            manifestValues.add(Json.arr(name, fpRel, Json.truthy(desc) ? desc : null, Long.valueOf(0), sv,
                    Json.truthy(pk) ? pk : null));
            defs.add(new Object[] {name, fpRel, fields});
        }

        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String createdAt = f.format(new java.util.Date());

        Map<String, Object> manifestDoc = new LinkedHashMap<String, Object>();
        manifestDoc.put("Format", "BEJSON");
        manifestDoc.put("Format_Version", "104a");
        manifestDoc.put("Format_Creator", "Elton Boehnen");
        manifestDoc.put("MFDB_Version", mfdbVersion);
        manifestDoc.put("Network_Role", networkRole);
        manifestDoc.put("DB_Name", dbName);
        manifestDoc.put("DB_Description", dbDescription);
        manifestDoc.put("Schema_Version", schemaVersion);
        manifestDoc.put("Author", author);
        manifestDoc.put("Created_At", createdAt);
        manifestDoc.put("Records_Type", Json.arr("mfdb"));
        manifestDoc.put("Fields", manifestFields);
        manifestDoc.put("Values", manifestValues);
        writeManifestDoc(manifestDoc, manifestPath);

        for (Object[] d : defs) {
            String entityName = (String) d[0];
            String resolved = PyFs.normpath(PyFs.join(rootDir, Json.pyStr(d[1])));
            String entityDir = PyFs.dirname(resolved);
            PyFs.makedirs(entityDir);
            String relToManifest = PyFs.relpath(manifestPath, entityDir);
            Map<String, Object> entityDoc = new LinkedHashMap<String, Object>();
            entityDoc.put("Format", "BEJSON");
            entityDoc.put("Format_Version", "104");
            entityDoc.put("Format_Creator", "Elton Boehnen");
            entityDoc.put("Parent_Hierarchy", relToManifest);
            entityDoc.put("Records_Type", Json.arr(entityName));
            entityDoc.put("Fields", d[2]);
            entityDoc.put("Values", new ArrayList<Object>());
            writeEntityDoc(entityDoc, resolved);
        }
        if (debugMode) enableDebug(manifestPath, debugRowCap, debugReads);
        return manifestPath;
    }

    public static String createDatabase(String rootDir, String dbName, List<Map<String, Object>> entities) {
        return createDatabase(rootDir, dbName, entities, "", "1.0.0", "Elton Boehnen", "1.31", "Master", false, 500, false);
    }

    /** Expands placeholders through BejsonEnv.resolvePath. */
    public static String resolvePath(String pathStr) {
        if (pathStr == null || pathStr.isEmpty()) return pathStr;
        return BejsonEnv.resolvePath(pathStr);
    }

    // ------------------------------------------------------------------ federation

    public static final List<Object> CONNECTED_SLAVE_SCHEMA = fieldList(
            "slave_id", "string", "label", "string", "url", "string", "role", "string", "status", "string",
            "supported_entities", "array");

    static List<Object> fieldList(String... nameType) {
        List<Object> out = new ArrayList<Object>();
        for (int i = 0; i < nameType.length; i += 2) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("name", nameType[i]);
            m.put("type", nameType[i + 1]);
            out.add(m);
        }
        return out;
    }

    /** On a Master manifest registers the ConnectedSlave entity; IllegalArgumentException (ValueError) otherwise. */
    public static String createConnectedSlaveEntity(String manifestPath) {
        Map<String, Object> manifestDoc = loadJsonMap(manifestPath);
        Object role = manifestDoc.containsKey("Network_Role") ? manifestDoc.get("Network_Role") : "";
        if (!"Master".equals(role)) {
            throw new IllegalArgumentException("ConnectedSlave entity may only be created on a Master node "
                    + "(Network_Role='Master'). Got: '" + Json.pyStr(role) + "'");
        }
        return createEntityFile(manifestPath, "ConnectedSlave", CONNECTED_SLAVE_SCHEMA,
                "Registry of Slave nodes connected to this Master.", "slave_id", "1.0", "");
    }

    /** Master to Slave atomic drop-zone push of a 104a document. */
    public static boolean federationPushConfig(Map<String, Object> configDoc, String slaveTargetPath) {
        String dest = PyFs.abspath(slaveTargetPath);
        PyFs.makedirs(PyFs.dirname(dest));
        try {
            byte[] bytes = Json.dumps(configDoc, 2, true).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            PyFs.atomicWriteBytes(dest, bytes, "tmp", ".tmp");
            return true;
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "[MFDB_FEDERATION] push_config failed: {0}", e.getMessage());
            return false;
        }
    }

    /** Callback for federationPollDropzone. */
    public interface DropzoneCallback {
        void accept(String path, Object doc);
    }

    /** Slave: polls a drop-zone directory for *.bejson configs for timeout seconds; returns the processed count. */
    public static int federationPollDropzone(String dropzoneDir, DropzoneCallback callback, double pollInterval,
                                             double timeout) {
        PyFs.makedirs(dropzoneDir);
        int processed = 0;
        long deadline = System.currentTimeMillis() + (long) (timeout * 1000);
        while (System.currentTimeMillis() < deadline) {
            List<String> names = new ArrayList<String>();
            for (String n : PyFs.listdir(dropzoneDir)) if (n.endsWith(".bejson")) names.add(n);
            Collections.sort(names);
            for (String n : names) {
                String fpath = PyFs.join(dropzoneDir, n);
                try {
                    Object doc = Json.loads(PyFs.readText(fpath));
                    callback.accept(fpath, doc);
                    if (!new File(fpath).delete()) throw new BejsonOSError(1, "cannot unlink " + fpath);
                    processed++;
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "[MFDB_FEDERATION] poll_dropzone skipped {0}: {1}", new Object[] {fpath, e.getMessage()});
                }
            }
            try {
                Thread.sleep((long) (pollInterval * 1000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return processed;
    }

    public static int federationPollDropzone(String dropzoneDir, DropzoneCallback callback) {
        return federationPollDropzone(dropzoneDir, callback, 2.0, 60.0);
    }

    /** Slave to Master log distillation (preserves the reference's resolve-by-entity-name behaviour). */
    public static boolean federationDistillLogs(String slaveManifestPath, String entityName, String masterPollDir,
                                                int maxRows) {
        try {
            String entityPath = MfdbValidator.resolveEntityPath(slaveManifestPath, entityName);
            Map<String, Object> doc = loadJsonMap(entityPath);
            List<Object> rows = doc.containsKey("Values") ? Json.asList(doc.get("Values")) : new ArrayList<Object>();
            if (rows.size() <= maxRows) return true;

            List<Object> overflow;
            List<Object> kept;
            if (maxRows == 0) {            // Python: rows[:-0] == [] and rows[-0:] == rows
                overflow = new ArrayList<Object>();
                kept = new ArrayList<Object>(rows);
            } else {
                overflow = new ArrayList<Object>(rows.subList(0, rows.size() - maxRows));
                kept = new ArrayList<Object>(rows.subList(rows.size() - maxRows, rows.size()));
            }
            PyFs.makedirs(masterPollDir);
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            String ts = f.format(new java.util.Date());
            String dest = PyFs.join(masterPollDir, "distilled_" + entityName + "_" + ts + ".bejson");

            Map<String, Object> summary = new LinkedHashMap<String, Object>();
            summary.put("Format", "BEJSON");
            summary.put("Format_Version", "104a");
            summary.put("Format_Creator", "Elton Boehnen");
            summary.put("Distill_Source", entityName);
            summary.put("Distill_Timestamp", MfdbArchive.utcIsoNow());
            summary.put("Records_Type", Json.arr("DistilledLog"));
            summary.put("Fields", doc.containsKey("Fields") ? doc.get("Fields") : new ArrayList<Object>());
            summary.put("Values", overflow);
            if (!federationPushConfig(summary, dest)) return false;

            doc.put("Values", kept);
            writeEntityDoc(doc, entityPath);

            Map<String, Object> manifestDoc = loadJsonMap(slaveManifestPath);
            List<Object> fields = manifestDoc.containsKey("Fields") ? Json.asList(manifestDoc.get("Fields")) : new ArrayList<Object>();
            Map<String, Integer> fm = new LinkedHashMap<String, Integer>();
            for (int i = 0; i < fields.size(); i++) fm.put(Json.pyStr(Json.asMap(fields.get(i)).get("name")), Integer.valueOf(i));
            List<Object> mvals = manifestDoc.containsKey("Values") ? Json.asList(manifestDoc.get("Values")) : new ArrayList<Object>();
            for (Object r : mvals) {
                List<Object> row = Json.asList(r);
                Integer en = fm.containsKey("entity_name") ? fm.get("entity_name") : Integer.valueOf(0);
                if (Json.pyEquals(row.get(en.intValue()), entityName) && fm.containsKey("record_count")) {
                    row.set(fm.get("record_count").intValue(), Long.valueOf(kept.size()));
                    break;
                }
            }
            writeManifestDoc(manifestDoc, slaveManifestPath);
            return true;
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "[MFDB_FEDERATION] distill_logs failed: {0}", e.getMessage());
            return false;
        }
    }

    public static boolean federationDistillLogs(String slaveManifestPath, String entityName, String masterPollDir) {
        return federationDistillLogs(slaveManifestPath, entityName, masterPollDir, 100);
    }

    // ------------------------------------------------------------------ meta-GUID debug entity system

    public static final List<Object> META_DEBUG_FIELDS = fieldList(
            "timestamp", "string", "operation", "string", "target_entity", "string", "field_name", "string",
            "field_exists", "boolean", "row_index", "integer", "success", "boolean", "duration_ms", "integer",
            "pid", "integer", "notes", "string");

    private static boolean debugFlag(String manifestPath, String key) {
        try {
            Map<String, Object> doc = loadJsonMap(manifestPath);
            Object v = doc.containsKey(key) ? doc.get(key) : "false";
            return PyOps.lower(Json.pyStr(v)).equals("true");
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean debugIsEnabled(String manifestPath) { return debugFlag(manifestPath, "Debug_Mode"); }

    static boolean debugReadsEnabled(String manifestPath) { return debugFlag(manifestPath, "Debug_Reads"); }

    static String debugGetMetaEntityName(String manifestPath) {
        try {
            Map<String, Object> doc = loadJsonMap(manifestPath);
            Object name = doc.containsKey("Debug_Meta_Entity") ? doc.get("Debug_Meta_Entity") : "";
            return Json.truthy(name) ? Json.pyStr(name) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Direct writer for the meta debug entity (bypasses normal write functions; never throws). */
    static void metaLog(String manifestPath, String operation, String targetEntity, String fieldName,
                        Boolean fieldExists, Integer rowIndex, boolean success, int durationMs, String notes,
                        boolean readsOnly) {
        try {
            if (!debugIsEnabled(manifestPath)) return;
            if (readsOnly && !debugReadsEnabled(manifestPath)) return;
            String metaName = debugGetMetaEntityName(manifestPath);
            if (metaName == null) return;
            String metaPath = getEntityPath(manifestPath, metaName);
            List<Object> row = new ArrayList<Object>();
            row.add(MfdbArchive.utcIsoNow());
            row.add(operation);
            row.add(targetEntity);
            row.add(fieldName);
            row.add(fieldExists);
            row.add(rowIndex == null ? null : Long.valueOf(rowIndex.longValue()));
            row.add(Boolean.valueOf(success));
            row.add(Long.valueOf(durationMs));
            row.add(Long.valueOf(ResilientPidLock.currentPid()));
            row.add(notes == null || notes.isEmpty() ? "" : notes);

            ResilientPidLock lock = ResilientPidLock.enter(metaPath, 5);
            try {
                Map<String, Object> doc = loadJsonMap(metaPath);
                if (!doc.containsKey("Values")) doc.put("Values", new ArrayList<Object>());
                Json.asList(doc.get("Values")).add(row);
                writeChecked(doc, metaPath, "debug meta log");
            } finally {
                lock.close();
            }
            metaAutoTrim(manifestPath, metaName, metaPath);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "[MFDB_DEBUG] meta log write failed (non-fatal): {0}", e.getMessage());
        }
    }

    static void metaLog(String manifestPath, String operation, String targetEntity, String fieldName,
                        Boolean fieldExists, Integer rowIndex, boolean success, int durationMs, String notes) {
        metaLog(manifestPath, operation, targetEntity, fieldName, fieldExists, rowIndex, success, durationMs, notes, false);
    }

    /** Python int(x) for the values a manifest header can hold. */
    static long pyInt(Object o) {
        if (o instanceof Boolean) return ((Boolean) o).booleanValue() ? 1 : 0;
        if (o instanceof Double) return (long) ((Double) o).doubleValue();
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof String) {
            String s = PyOps.strip((String) o);
            if (s.matches("[-+]?\\d+(_\\d+)*")) return Long.parseLong(s.replace("_", "").replace("+", ""));
        }
        throw new IllegalArgumentException("invalid literal for int()");
    }

    static void metaAutoTrim(String manifestPath, String metaName, String metaPath) {
        try {
            Map<String, Object> m = loadJsonMap(manifestPath);
            long cap = pyInt(m.containsKey("Debug_Row_Cap") ? m.get("Debug_Row_Cap") : "500");
            Map<String, Object> doc = loadJsonMap(metaPath);
            List<Object> rows = doc.containsKey("Values") ? Json.asList(doc.get("Values")) : new ArrayList<Object>();
            if (rows.size() > cap) {
                long start = -cap;                     // Python rows[-cap:]
                if (start < 0) start += rows.size();
                if (start < 0) start = 0;
                if (start > rows.size()) start = rows.size();
                doc.put("Values", new ArrayList<Object>(rows.subList((int) start, rows.size())));
                writeChecked(doc, metaPath, "debug meta log");
                updateManifestRecordCount(manifestPath, metaName, Long.valueOf(Json.asList(doc.get("Values")).size()));
            }
        } catch (RuntimeException e) {
            // best effort, like the reference
        }
    }

    static void debugSchemaSnapshot(String manifestPath, String metaName) {
        try {
            for (Map<String, Object> entry : getManifestEntries(manifestPath)) {
                Object en = entry.containsKey("entity_name") ? entry.get("entity_name") : "";
                if (!Json.truthy(en) || Json.pyEquals(en, metaName)) continue;
                String ename = Json.pyStr(en);
                try {
                    Map<String, Object> edoc = loadJsonMap(getEntityPath(manifestPath, ename));
                    List<Object> fl = edoc.containsKey("Fields") ? Json.asList(edoc.get("Fields")) : new ArrayList<Object>();
                    StringBuilder names = new StringBuilder();
                    for (int i = 0; i < fl.size(); i++) {
                        Object n = Json.asMap(fl.get(i)).get("name");
                        if (!(n instanceof String)) throw new IllegalArgumentException("sequence item: expected str");
                        if (i > 0) names.append(',');
                        names.append((String) n);
                    }
                    metaLog(manifestPath, "SCHEMA_SNAPSHOT", ename, names.toString(), Boolean.TRUE, null, true, 0,
                            "field_count=" + fl.size());
                } catch (RuntimeException e) {
                    // skip this entity
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "[MFDB_DEBUG] schema snapshot failed: {0}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ public debug API

    /** Activates debug on an existing MFDB; returns the meta entity name. */
    public static String enableDebug(String manifestPath, int rowCap, boolean debugReads) {
        Map<String, Object> doc = loadJsonMap(manifestPath);
        Object existing = doc.containsKey("Debug_Meta_Entity") ? doc.get("Debug_Meta_Entity") : "";
        String metaName = Json.truthy(existing) ? Json.pyStr(existing) : "meta-" + java.util.UUID.randomUUID();

        doc.put("Debug_Mode", "true");
        doc.put("Debug_Meta_Entity", metaName);
        doc.put("Debug_Row_Cap", Integer.toString(rowCap));
        doc.put("Debug_Reads", debugReads ? "true" : "false");
        writeManifestDoc(doc, manifestPath);

        String metaFpRel = "data/" + metaName + ".bejson";
        String manifestDir = PyFs.dirname(PyFs.abspath(manifestPath));
        String metaAbs = PyFs.join(manifestDir, metaFpRel);
        if (!PyFs.exists(metaAbs)) {
            PyFs.makedirs(PyFs.dirname(metaAbs));
            String relToManifest = PyFs.relpath(manifestPath, PyFs.dirname(metaAbs));
            Map<String, Object> metaDoc = new LinkedHashMap<String, Object>();
            metaDoc.put("Format", "BEJSON");
            metaDoc.put("Format_Version", "104");
            metaDoc.put("Format_Creator", "Elton Boehnen");
            metaDoc.put("Parent_Hierarchy", relToManifest);
            metaDoc.put("Records_Type", Json.arr(metaName));
            metaDoc.put("Fields", META_DEBUG_FIELDS);
            metaDoc.put("Values", new ArrayList<Object>());
            writeChecked(metaDoc, metaAbs, "debug meta entity");

            boolean registered = false;
            for (Map<String, Object> e : getManifestEntries(manifestPath)) {
                if (Json.pyEquals(e.get("entity_name"), metaName)) { registered = true; break; }
            }
            if (!registered) {
                Map<String, Object> doc2 = loadJsonMap(manifestPath);
                if (!doc2.containsKey("Values")) doc2.put("Values", new ArrayList<Object>());
                Json.asList(doc2.get("Values")).add(Json.arr(metaName, metaFpRel, "Debug audit log (auto-generated)",
                        Long.valueOf(0), "1.0", null));
                writeManifestDoc(doc2, manifestPath);
            }
        }
        debugSchemaSnapshot(manifestPath, metaName);
        return metaName;
    }

    public static String enableDebug(String manifestPath) { return enableDebug(manifestPath, 500, false); }

    /** Sets Debug_Mode to false (meta entity and rows are kept). */
    public static void disableDebug(String manifestPath) {
        Map<String, Object> doc = loadJsonMap(manifestPath);
        doc.put("Debug_Mode", "false");
        writeManifestDoc(doc, manifestPath);
    }

    /** All meta-entity rows as maps keyed by field name (empty when debug is off or the meta entity is missing). */
    public static List<Map<String, Object>> getDebugLog(String manifestPath) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        String metaName = debugGetMetaEntityName(manifestPath);
        if (metaName == null) return out;
        try {
            Map<String, Object> doc = loadJsonMap(getEntityPath(manifestPath, metaName));
            Map<String, Integer> fm = new LinkedHashMap<String, Integer>();
            List<Object> fl = doc.containsKey("Fields") ? Json.asList(doc.get("Fields")) : META_DEBUG_FIELDS;
            for (int i = 0; i < fl.size(); i++) fm.put(Json.pyStr(Json.asMap(fl.get(i)).get("name")), Integer.valueOf(i));
            List<Object> vals = doc.containsKey("Values") ? Json.asList(doc.get("Values")) : new ArrayList<Object>();
            for (Object r : vals) {
                List<Object> row = Json.asList(r);
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                for (Map.Entry<String, Integer> e : fm.entrySet()) {
                    if (e.getValue().intValue() >= row.size()) throw new IndexOutOfBoundsException("list index out of range");
                    m.put(e.getKey(), row.get(e.getValue().intValue()));
                }
                out.add(m);
            }
            return out;
        } catch (RuntimeException e) {
            return new ArrayList<Map<String, Object>>();
        }
    }

    /** Failed operations (success == false) oldest first. */
    public static List<Map<String, Object>> getFailedOps(String manifestPath) {
        List<Map<String, Object>> failed = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : getDebugLog(manifestPath)) if (Boolean.FALSE.equals(r.get("success"))) failed.add(r);
        Collections.sort(failed, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                Object x = a.containsKey("timestamp") ? a.get("timestamp") : "";
                Object y = b.containsKey("timestamp") ? b.get("timestamp") : "";
                return PyOps.compare(x, y);
            }
        });
        return failed;
    }

    /** Wipes all meta rows (schema kept); returns the number removed. */
    public static int clearDebugLog(String manifestPath) {
        String metaName = debugGetMetaEntityName(manifestPath);
        if (metaName == null) return 0;
        try {
            String metaPath = getEntityPath(manifestPath, metaName);
            int deleted;
            ResilientPidLock lock = ResilientPidLock.enter(metaPath, 10);
            try {
                Map<String, Object> doc = loadJsonMap(metaPath);
                deleted = doc.containsKey("Values") ? Json.asList(doc.get("Values")).size() : 0;
                doc.put("Values", new ArrayList<Object>());
                writeChecked(doc, metaPath, "debug meta log");
            } finally {
                lock.close();
            }
            updateManifestRecordCount(manifestPath, metaName, Long.valueOf(0));
            return deleted;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Aggregated debug-log view (empty map when debug is off). */
    public static Map<String, Object> debugSummary(String manifestPath) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (!debugIsEnabled(manifestPath)) return out;
        List<Map<String, Object>> rows = getDebugLog(manifestPath);
        if (rows.isEmpty()) {
            out.put("total_ops", Long.valueOf(0));
            return out;
        }
        Set<String> writeOps = new HashSet<String>(Arrays.asList("ADD", "REMOVE", "UPDATE", "UPDATE_BULK"));
        Map<String, Object> opsByType = new LinkedHashMap<String, Object>();
        for (Map<String, Object> r : rows) {
            String op = Json.pyStr(r.get("operation"));
            Object cur = opsByType.get(op);
            opsByType.put(op, Long.valueOf((cur == null ? 0L : ((Long) cur).longValue()) + 1));
        }
        List<Object> entities = new ArrayList<Object>();
        for (Map<String, Object> r : rows) {
            Object e = r.get("target_entity");
            boolean dup = false;
            for (Object x : entities) if (Json.pyEquals(x, e)) { dup = true; break; }
            if (!dup) entities.add(e);
        }
        Collections.sort(entities, PyOps.comparator());
        long failedOps = 0;
        long drift = 0;
        long reads = 0;
        long writes = 0;
        List<Map<String, Object>> slow = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : rows) {
            if (Boolean.FALSE.equals(r.get("success"))) failedOps++;
            if (Boolean.FALSE.equals(r.get("field_exists"))) drift++;
            String op = Json.pyStr(r.get("operation"));
            if (op.equals("READ")) reads++;
            if (writeOps.contains(op)) writes++;
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            s.put("op", r.get("operation"));
            s.put("entity", r.get("target_entity"));
            s.put("duration_ms", r.get("duration_ms"));
            slow.add(s);
        }
        Collections.sort(slow, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return -PyOps.compare(a.get("duration_ms"), b.get("duration_ms"));
            }
        });
        out.put("total_ops", Long.valueOf(rows.size()));
        out.put("unique_entities", entities);
        out.put("failed_ops", Long.valueOf(failedOps));
        out.put("schema_drift_hits", Long.valueOf(drift));
        out.put("top_3_slowest", new ArrayList<Object>(slow.subList(0, Math.min(3, slow.size()))));
        out.put("reads_logged", Long.valueOf(reads));
        out.put("writes_logged", Long.valueOf(writes));
        out.put("ops_by_type", opsByType);
        return out;
    }

    /** Compares each entity's live Fields with its latest SCHEMA_SNAPSHOT baseline. */
    public static Map<String, Object> detectSchemaDrift(String manifestPath) {
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        if (!debugIsEnabled(manifestPath)) return report;
        Map<String, List<String>> snapshots = new LinkedHashMap<String, List<String>>();
        for (Map<String, Object> r : getDebugLog(manifestPath)) {
            if ("SCHEMA_SNAPSHOT".equals(r.get("operation")) && Json.truthy(r.get("field_name"))) {
                List<String> snap = new ArrayList<String>();
                for (String s : Json.pyStr(r.get("field_name")).split(",", -1)) if (!snap.contains(s)) snap.add(s);
                snapshots.put(Json.pyStr(r.get("target_entity")), snap);
            }
        }
        if (snapshots.isEmpty()) return report;
        for (Map.Entry<String, List<String>> en : snapshots.entrySet()) {
            try {
                Map<String, Object> edoc = loadJsonMap(getEntityPath(manifestPath, en.getKey()));
                List<Object> fl = edoc.containsKey("Fields") ? Json.asList(edoc.get("Fields")) : new ArrayList<Object>();
                List<String> live = new ArrayList<String>();
                for (Object f : fl) {
                    String n = Json.pyStr(Json.asMap(f).get("name"));
                    if (!live.contains(n)) live.add(n);
                }
                List<Object> added = new ArrayList<Object>();
                for (String n : live) if (!en.getValue().contains(n)) added.add(n);
                List<Object> removed = new ArrayList<Object>();
                for (String n : en.getValue()) if (!live.contains(n)) removed.add(n);
                Collections.sort(added, PyOps.comparator());
                Collections.sort(removed, PyOps.comparator());
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("added_fields", added);
                m.put("removed_fields", removed);
                m.put("drifted", Boolean.valueOf(!added.isEmpty() || !removed.isEmpty()));
                report.put(en.getKey(), m);
            } catch (RuntimeException e) {
                // skip entity
            }
        }
        return report;
    }
}
