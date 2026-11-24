/*
 * File Name:       Mfdb132Archive.java
 * Relative Path:   src/main/java/com/boehnenelton/bejson/core/Mfdb132Archive.java
 * Description:     Port of class MFDB132Archive (lib_bejson_Core_bejson_chunking.py 1.9.5): session-based mount of an
 *                  MFDB-132 chunk document (mount / commit / resurrectFile / unmount) with the .mfdb132_lock session
 *                  lock and the Session_Is_Mounted / Mount_Path headers on the chunk document. Chunked-104a / MFDB-132
 *                  packaging only (NOT 104db). No zip handling exists in the reference, so none is used here.
 * Format_Scope:    Chunked-104a and MFDB-132 packaging (NOT 104db)
 * Deviations:      (1) mounted_at has millisecond (not microsecond) resolution. (2) Python exception types map to
 *                  IllegalArgumentException (ValueError), BejsonOSError/PERMISSION (PermissionError) and
 *                  IllegalStateException (RuntimeError); messages are identical. (3) Warnings go to java.util.logging.
 * VERSION:         1.0.0 (reference 1.9.5)
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   23f072bd-f4d0-464c-930c-32dfc7d7bb94
 */
package com.boehnenelton.bejson.core;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TimeZone;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Mfdb132Archive {
    private Mfdb132Archive() { }

    public static final String LOCK_FILE_132 = BejsonChunking.LOCK_FILE_132;
    private static final Logger LOG = Logger.getLogger("lib_bejson_Core_bejson_chunking");

    // ── helpers ──────────────────────────────────────────────────────────────

    /** SHA-256 of a file on disk. */
    static String calculateChunkHash(String filePath) {
        return PyFs.sha256Hex(PyFs.readBytes(filePath));
    }

    private static Map<String, Object> loadMap(String path, String what) {
        Object o = Json.loads(PyFs.readText(path));
        Map<String, Object> m = Json.asMap(o);
        if (m == null) {
            throw new IllegalArgumentException("'" + PyOps.typeName(o) + "' object has no attribute 'get' (" + what + ")");
        }
        return m;
    }

    private static void writeLock(String lockFile, Map<String, Object> lockData) {
        PyFs.writeBytes(lockFile, Json.dumps(lockData).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Updates Session_Is_Mounted / Mount_Path in a chunk document on disk; failures are logged, never thrown. */
    static void setChunkDocHeaders(String chunkDocPath, boolean isMounted, String mountPath) {
        try {
            Map<String, Object> doc = loadMap(chunkDocPath, "chunk doc");
            doc.remove("_bejson_field_map");
            doc.put("Session_Is_Mounted", Boolean.valueOf(isMounted));
            doc.remove("Is_Mounted");
            doc.put("Mount_Path", mountPath);
            PyFs.writeBytes(chunkDocPath, Json.dumps(doc, 2, true).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "[MFDB132] Could not update chunk doc headers: {0}", String.valueOf(e.getMessage()));
        }
    }

    /** datetime.now(timezone.utc).isoformat(). */
    private static String isoNowUtc() {
        long ms = System.currentTimeMillis();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        String s = f.format(new Date(ms));
        int micro = (int) (ms % 1000L) * 1000;
        if (micro != 0) s += String.format(Locale.ROOT, ".%06d", Integer.valueOf(micro));
        return s + "+00:00";
    }

    private static boolean isTruthyString(Object o) {
        return Json.truthy(o);
    }

    // ── mount ────────────────────────────────────────────────────────────────

    /** Unchunks an MFDB-132 package into a workspace and creates the session lock; returns the manifest path. */
    public static String mount(String chunkDocPath, String targetDir, boolean force, boolean sticky) {
        String chunkP = PyFs.realpath(chunkDocPath);
        if (!PyFs.exists(chunkP)) {
            throw new IllegalArgumentException("Chunk doc not found: " + chunkDocPath);
        }

        String targetP = BejsonChunking.pathStr(targetDir);
        String lockFile = BejsonChunking.pathJoin(targetP, LOCK_FILE_132);
        String manifestPath = BejsonChunking.pathJoin(targetP, BejsonChunking.MFDB_MANIFEST_FILENAME);
        String currentHash = calculateChunkHash(chunkP);

        // Sticky reuse
        if (sticky && PyFs.exists(lockFile) && PyFs.exists(manifestPath)) {
            try {
                Map<String, Object> lockData = loadMap(lockFile, "lock");
                if (Json.pyEquals(lockData.get("original_hash"), currentHash)) {
                    if (MfdbValidator.validateDatabase(manifestPath)) {
                        return BejsonChunking.pathAbsolute(manifestPath);
                    }
                }
            } catch (RuntimeException e) {
                // fall through to full re-unchunk
            }
        }

        // Ownership check
        if (PyFs.exists(lockFile) && !force) {
            Map<String, Object> lockData = loadMap(lockFile, "lock");
            Object pid = lockData.get("pid");
            if (!Json.pyEquals(pid, Long.valueOf(ResilientPidLock.currentPid()))) {
                throw new BejsonOSError(13, "Workspace " + targetDir + " is locked by PID " + Json.pyStr(pid)
                        + ". Pass force=True to override.", BejsonOSError.Kind.PERMISSION, null);
            }
        }

        // Clear workspace, unchunk
        if (PyFs.exists(targetP)) {
            PyFs.rmtree(targetDir);
        }
        PyFs.makedirs(targetP);

        Map<String, Object> doc = loadMap(chunkP, "chunk doc");
        int count = BejsonChunking.unchunkChunked104(doc, targetDir);
        if (count == 0) {
            PyFs.rmtree(targetDir);
            throw new IllegalArgumentException("Unchunk produced zero files — chunk doc may be empty.");
        }
        if (!PyFs.exists(manifestPath)) {
            PyFs.rmtree(targetDir);
            throw new IllegalArgumentException("Invalid MFDB132 package: 104a.mfdb.bejson missing after unchunk.");
        }

        // Write session lock
        Map<String, Object> lockData = Json.obj();
        lockData.put("pid", Long.valueOf(ResilientPidLock.currentPid()));
        lockData.put("mounted_at", isoNowUtc());
        lockData.put("original_hash", currentHash);
        lockData.put("chunk_doc_path", chunkP);
        lockData.put("workspace_dir", BejsonChunking.pathAbsolute(targetP));
        writeLock(lockFile, lockData);

        // Activate headers in chunk doc
        setChunkDocHeaders(chunkP, true, BejsonChunking.pathAbsolute(targetP));
        return BejsonChunking.pathAbsolute(manifestPath);
    }

    public static String mount(String chunkDocPath, String targetDir) {
        return mount(chunkDocPath, targetDir, false, true);
    }

    // ── commit ───────────────────────────────────────────────────────────────

    /** Re-chunks the workspace back into an MFDB-132 package atomically (validation is a pre-write gate). */
    public static String commit(String mountDir, String outputPath, boolean validate) {
        String mountP = BejsonChunking.pathStr(mountDir);
        String lockFile = BejsonChunking.pathJoin(mountP, LOCK_FILE_132);
        String manifestPath = BejsonChunking.pathJoin(mountP, BejsonChunking.MFDB_MANIFEST_FILENAME);

        if (!PyFs.exists(lockFile)) {
            throw new IllegalStateException("No active 132 mount session in " + mountDir);
        }
        Map<String, Object> lockData = loadMap(lockFile, "lock");

        if (validate) {
            if (!PyFs.exists(manifestPath)) {
                throw new IllegalStateException("Commit rejected: manifest missing in workspace.");
            }
            try {
                MfdbValidator.validateDatabase(manifestPath);
            } catch (RuntimeException e) {
                throw new IllegalStateException("Commit rejected: validation failed — " + e.getMessage(), e);
            }
        }

        Object destObj = Json.truthy(outputPath) ? outputPath : lockData.get("chunk_doc_path");
        if (!isTruthyString(destObj)) {
            throw new IllegalStateException("Destination chunk doc path unknown.");
        }
        String destPath = Json.pyStr(destObj);

        Map<String, Object> manifestDoc = loadMap(manifestPath, "manifest");
        Object dbName = manifestDoc.containsKey("DB_Name") ? manifestDoc.get("DB_Name") : "";

        File temp;
        try {
            temp = File.createTempFile("tmp", ".mfdb132.bejson");
        } catch (IOException e) {
            throw BejsonOSError.wrap(e, "tmp.mfdb132.bejson");
        }
        try {
            Map<String, Object> newDoc = BejsonChunking.createMfdb132Package(mountDir, "");
            newDoc.put("DB_Name", dbName);
            PyFs.writeBytes(temp.getPath(), Json.dumps(newDoc, 2, true).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            move(temp, destPath);
        } catch (RuntimeException e) {
            if (temp.exists()) temp.delete();
            throw new IllegalStateException("Commit failed during rechunk: " + e.getMessage(), e);
        }

        String newHash = calculateChunkHash(destPath);
        lockData.put("original_hash", newHash);
        writeLock(lockFile, lockData);

        setChunkDocHeaders(destPath, true, BejsonChunking.pathAbsolute(mountP));
        return destPath;
    }

    public static String commit(String mountDir) {
        return commit(mountDir, null, true);
    }

    /** shutil.move(src, dst) for a file: rename, else copy + delete; an existing directory dst receives the file. */
    private static void move(File src, String dst) {
        File d = new File(dst);
        if (d.isDirectory()) {
            d = new File(d, src.getName());
            if (d.exists()) throw BejsonOSError.exists(d.getPath());
        }
        try {
            PyFs.replace(src, d);
        } catch (IOException e) {
            try {
                PyFs.writeBytes(d.getPath(), PyFs.readBytes(src.getPath()));
                src.delete();
            } catch (RuntimeException e2) {
                throw e2;
            }
        }
    }

    // ── resurrect ────────────────────────────────────────────────────────────

    /** Re-extracts a single file from the chunk doc into the mounted workspace (recovery); false if not possible. */
    public static boolean resurrectFile(String mountDir, String relativePath) {
        String mountP = BejsonChunking.pathStr(mountDir);
        String lockFile = BejsonChunking.pathJoin(mountP, LOCK_FILE_132);
        if (!PyFs.exists(lockFile)) return false;

        Map<String, Object> lockData = loadMap(lockFile, "lock");
        Object cdp = lockData.get("chunk_doc_path");
        if (!Json.truthy(cdp) || !(cdp instanceof String) || !PyFs.exists((String) cdp)) return false;
        String chunkDocPath = (String) cdp;

        try {
            Map<String, Object> doc = loadMap(chunkDocPath, "chunk doc");
            Map<String, Integer> fm = new java.util.LinkedHashMap<String, Integer>();
            List<Object> fields = doc.containsKey("Fields") ? Json.asList(doc.get("Fields")) : BejsonChunking.chunked104Fields();
            if (fields == null) throw new IllegalArgumentException("Fields is not iterable");
            for (int i = 0; i < fields.size(); i++) {
                Map<String, Object> f = Json.asMap(fields.get(i));
                if (f == null || !f.containsKey("name")) throw new NoSuchElementException("KeyError: 'name'");
                fm.put(Json.pyStr(f.get("name")), Integer.valueOf(i));
            }
            String targetRel = BejsonChunking.pathStr(relativePath);

            List<Object> values = Json.asList(doc.containsKey("Values") ? doc.get("Values") : new java.util.ArrayList<Object>());
            if (values == null) throw new IllegalArgumentException("Values is not iterable");
            for (Object rowObj : values) {
                List<Object> row = Json.asList(rowObj);
                if (row == null) throw new IllegalArgumentException("row is not a list");
                Object rp = row.get(idx(fm, "Relative_Path"));
                if (!(rp instanceof String)) throw new IllegalArgumentException("expected str path");
                String rowRel = BejsonChunking.pathStr((String) rp);
                if (rowRel.equals(targetRel)) {
                    String targetFile = BejsonPathGuard.safeJoin(mountP, rowRel);
                    PyFs.makedirs(PyFs.dirname(targetFile));
                    Object isBinary = row.get(idx(fm, "Is_Binary"));
                    Object content = row.get(idx(fm, "File_Content"));
                    if (!(content instanceof String)) throw new IllegalArgumentException("content must be str");
                    if (Json.truthy(isBinary)) {
                        PyFs.writeBytes(targetFile, BejsonChunking.b64decode((String) content));
                    } else {
                        PyFs.writeBytes(targetFile, BejsonChunking.encodeUtf8Strict((String) content));
                    }
                    return true;
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "[MFDB132] resurrect_file failed for {0}: {1}",
                    new Object[] {relativePath, String.valueOf(e.getMessage())});
        }
        return false;
    }

    private static int idx(Map<String, Integer> fm, String name) {
        Integer i = fm.get(name);
        if (i == null) throw new NoSuchElementException("KeyError: '" + name + "'");
        return i.intValue();
    }

    // ── unmount ──────────────────────────────────────────────────────────────

    /** Releases the session lock, clears the chunk doc mount headers, and optionally deletes the workspace. */
    public static void unmount(String mountDir, boolean cleanup) {
        String mountP = BejsonChunking.pathStr(mountDir);
        String lockFile = BejsonChunking.pathJoin(mountP, LOCK_FILE_132);

        if (PyFs.exists(lockFile)) {
            try {
                Map<String, Object> lockData = loadMap(lockFile, "lock");
                Object cdp = lockData.get("chunk_doc_path");
                if (Json.truthy(cdp) && cdp instanceof String && PyFs.exists((String) cdp)) {
                    setChunkDocHeaders((String) cdp, false, "");
                }
            } catch (RuntimeException e) {
                // ignored, as in the reference
            }
            if (!new File(lockFile).delete()) {
                throw BejsonOSError.notFound(lockFile);
            }
        }

        if (cleanup && PyFs.exists(mountP)) {
            PyFs.rmtree(mountDir);
        }
    }

    public static void unmount(String mountDir) {
        unmount(mountDir, true);
    }
}
