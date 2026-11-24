/*
 * File Name:       BejsonPathGuard.java
 * Relative Path:   src/main/java/com/boehnenelton/bejson/core/BejsonPathGuard.java
 * Description:     Port of lib_bejson_Core_bejson_path_guard.py 1.3.1: traversal-safe path joining, storage-root mapping,
 *                  MFDB root-escape check and zip-slip-safe archive extraction.
 * VERSION:         1.0.0 (reference 1.3.1)
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   f406f938-3335-4b90-98cd-24f8dccba499
 */
package com.boehnenelton.bejson.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class BejsonPathGuard {
    private BejsonPathGuard() { }

    public static final String VERSION = "1.3.1";

    /** Joins paths under baseDir; throws IllegalArgumentException (Python ValueError) if the result leaves baseDir. */
    public static String safeJoin(String baseDir, String... paths) {
        String base = PyFs.realpath(baseDir);
        String cur = base;
        for (String p : paths) cur = PyFs.join(cur, PyFs.expandvars(p));
        String target = PyFs.realpath(cur);
        boolean inside = target.equals(base);
        if (!inside) {
            String prefix = base.endsWith("/") ? base : base + "/";
            inside = target.startsWith(prefix);
        }
        if (!inside) {
            throw new IllegalArgumentException("Path traversal detected: " + target + " is outside of " + base);
        }
        return target;
    }

    /** Maps /storage/emulated/0 onto $BEJSON_STORAGE_ROOT (default: home). */
    public static String resolveStoragePath(String path) {
        String storageRoot = BejsonEnv.getenv("BEJSON_STORAGE_ROOT");
        if (storageRoot == null || storageRoot.isEmpty()) storageRoot = PyFs.expanduser("~");
        if (path == null || path.isEmpty()) return storageRoot;
        String pre = "/storage/emulated/0";
        if (path.equals(pre) || path.startsWith(pre + "/")) return storageRoot + path.substring(pre.length());
        return path;
    }

    /** True if a relative path would climb above the MFDB root (or is absolute). */
    public static boolean mfdbEscapesRoot(String relativePath) {
        String normalized = relativePath.replace("\\", "/");
        if (normalized.startsWith("/")
                || (normalized.length() > 1 && normalized.charAt(1) == ':' && Character.isLetter(normalized.charAt(0)))) {
            return true;
        }
        int depth = 0;
        for (String part : normalized.split("/", -1)) {
            if (part.equals("..")) {
                depth--;
                if (depth < 0) return true;
            } else if (!part.equals(".") && !part.isEmpty()) {
                depth++;
            }
        }
        return false;
    }

    /** Default cap on the total uncompressed bytes (and entries) accepted from one archive (zip-bomb defence). */
    public static final long DEFAULT_MAX_EXTRACT_BYTES = 2L << 30;
    public static final int MAX_ARCHIVE_ENTRIES = 100000;
    private static volatile long maxExtractBytes = DEFAULT_MAX_EXTRACT_BYTES;

    /** Adjusts the per-archive uncompressed-size cap (default 2 GiB). */
    public static void setMaxExtractBytes(long bytes) { maxExtractBytes = bytes; }

    public static long getMaxExtractBytes() { return maxExtractBytes; }

    /** A running allowance shared by all members of one archive. */
    public static final class Budget {
        long remaining = maxExtractBytes;
        int entries;
        public Budget() { }
        /** Budget capped at {@code bytes} (never above the global limit); used for in-memory reads. */
        public Budget(long bytes) { remaining = Math.min(bytes, maxExtractBytes); }
        void entry() {
            if (++entries > MAX_ARCHIVE_ENTRIES) throw new IllegalArgumentException("Archive has too many entries (limit " + MAX_ARCHIVE_ENTRIES + ")");
        }
    }

    /** Copies in to out, failing (IllegalArgumentException) once the archive's total uncompressed size exceeds the budget. */
    public static void copyBounded(java.io.InputStream in, OutputStream out, Budget budget) throws java.io.IOException {
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) > 0) {
            budget.remaining -= r;
            if (budget.remaining < 0) throw new IllegalArgumentException("Archive exceeds the extraction size limit (" + maxExtractBytes + " bytes)");
            out.write(buf, 0, r);
        }
    }

    /** Extracts every member after validating all names (zip-slip safe, all-or-nothing validation). */
    public static void safeExtractZip(ZipFile zf, String destDir) {
        String dest = PyFs.realpath(destDir);
        List<ZipEntry> entries = new ArrayList<ZipEntry>();
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) entries.add(en.nextElement());
        for (ZipEntry e : entries) safeJoin(dest, e.getName());
        Budget budget = new Budget();
        try {
            for (ZipEntry e : entries) {
                budget.entry();
                String name = e.getName();
                File out = new File(safeJoin(dest, name));
                if (e.isDirectory() || name.endsWith("/")) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                InputStream in = zf.getInputStream(e);
                try {
                    OutputStream os = new FileOutputStream(out);
                    try {
                        copyBounded(in, os, budget);
                    } finally {
                        os.close();
                    }
                } finally {
                    in.close();
                }
            }
        } catch (IOException ex) {
            throw BejsonOSError.wrap(ex, destDir);
        }
    }
}
