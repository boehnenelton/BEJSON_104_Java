/*
 * File Name:       MfdbArchive.java
 * Relative Path:   src/main/java/com/boehnenelton/bejson/core/MfdbArchive.java
 * Description:     Port of class MFDBArchive (lib_bejson_Core_mfdb_core.py 2.3.5): .mfdb.zip virtual mounting with a
 *                  session lock (.mfdb_lock), sticky re-mount, validated atomic repack, single-file resurrection.
 * Deviation:       Zip entries are written in sorted order (Python used os.walk order); extracted content is identical.
 * VERSION:         1.0.0 (reference 2.3.5)
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   36bd80a5-19a7-42d7-8e8f-db84cfd5a882
 */
package com.boehnenelton.bejson.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class MfdbArchive {
    private MfdbArchive() { }

    private static final Logger LOG = Logger.getLogger("lib_bejson_Core_mfdb_core");

    private static Map<String, Object> readLock(File lockFile) {
        Map<String, Object> m = Json.asMap(Json.loads(PyFs.readText(lockFile.getPath())));
        if (m == null) throw new IllegalStateException("lock file is not a JSON object");
        return m;
    }

    private static void writeLock(File lockFile, Map<String, Object> data) {
        PyFs.atomicWriteBytes(lockFile.getPath(), Json.dumps(data).getBytes(java.nio.charset.StandardCharsets.UTF_8), ".mfdb_lock-", ".tmp");
    }

    /** datetime.now(timezone.utc).isoformat() */
    static String utcIsoNow() {
        long ms = System.currentTimeMillis();
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        String base = f.format(new Date(ms));
        long micro = (ms % 1000) * 1000;
        String frac = micro == 0 ? "" : "." + String.format(Locale.ROOT, "%06d", micro);
        return base + frac + "+00:00";
    }

    /** Extracts a .mfdb.zip into targetDir with a session lock; returns the absolute manifest path. */
    public static String mount(String archivePath, String targetDir, boolean force, boolean sticky) {
        File arc = new File(archivePath);
        if (!arc.exists()) {
            throw new MfdbCoreException("Archive not found: " + archivePath, BejsonErrors.E_MFDB_CORE_ARCHIVE_ERROR);
        }
        File target = new File(targetDir);
        File lockFile = new File(target, ".mfdb_lock");
        File manifestPath = new File(target, "104a.mfdb.bejson");

        if (sticky && lockFile.exists() && manifestPath.exists()) {
            try {
                Map<String, Object> lockData = readLock(lockFile);
                String currentHash = MfdbCore.calculateFileHash(archivePath);
                if (Json.pyEquals(lockData.get("original_hash"), currentHash)) {
                    if (MfdbValidator.validateDatabase(manifestPath.getPath())) {
                        return PyFs.abspath(manifestPath.getPath());
                    }
                }
            } catch (RuntimeException e) {
                // fall through to full re-extract
            }
        }

        if (lockFile.exists() && !force) {
            Map<String, Object> lockData = readLock(lockFile);
            if (!Json.pyEquals(lockData.get("pid"), Long.valueOf(ResilientPidLock.currentPid()))) {
                throw new MfdbCoreException("Workspace " + targetDir + " is already locked by PID "
                        + Json.pyStr(lockData.get("pid")), BejsonErrors.E_MFDB_CORE_MOUNT_CONFLICT);
            }
        }

        if (target.exists()) PyFs.rmtree(targetDir);
        PyFs.makedirs(targetDir);

        ZipFile zip = null;
        try {
            zip = new ZipFile(archivePath);
            Enumeration<? extends ZipEntry> en = zip.entries();
            BejsonPathGuard.Budget budget = new BejsonPathGuard.Budget();
            while (en.hasMoreElements()) {
                ZipEntry member = en.nextElement();
                String name = member.getName();
                if (name.endsWith("/")) continue;
                budget.entry();
                try {
                    String safe = BejsonPathGuard.safeJoin(targetDir, name);
                    PyFs.makedirs(PyFs.dirname(safe));
                    InputStream in = zip.getInputStream(member);
                    try {
                        OutputStream out = new FileOutputStream(safe);
                        try {
                            BejsonPathGuard.copyBounded(in, out, budget);
                        } finally {
                            out.close();
                        }
                    } finally {
                        in.close();
                    }
                } catch (IllegalArgumentException e) {
                    LOG.log(Level.SEVERE, "[MFDB_CORE] Security Alert: {0}", e.getMessage());
                    throw new MfdbCoreException("Secure extraction failed: " + e.getMessage(),
                            BejsonErrors.E_MFDB_CORE_ARCHIVE_ERROR);
                }
            }
        } catch (IOException e) {
            throw BejsonOSError.wrap(e, archivePath);
        } finally {
            if (zip != null) {
                try { zip.close(); } catch (IOException ignored) { /* best effort */ }
            }
        }

        if (!manifestPath.exists()) {
            PyFs.rmtree(targetDir);
            throw new MfdbCoreException("Invalid MFDB Archive: 104a.mfdb.bejson missing.",
                    BejsonErrors.E_MFDB_CORE_ARCHIVE_ERROR);
        }

        Map<String, Object> lockData = new LinkedHashMap<String, Object>();
        lockData.put("pid", Long.valueOf(ResilientPidLock.currentPid()));
        lockData.put("mounted_at", utcIsoNow());
        lockData.put("original_hash", MfdbCore.calculateFileHash(archivePath));
        lockData.put("archive_path", PyFs.abspath(archivePath));
        writeLock(lockFile, lockData);
        return PyFs.abspath(manifestPath.getPath());
    }

    public static String mount(String archivePath, String targetDir) { return mount(archivePath, targetDir, false, true); }

    /** Repacks the workspace into the .mfdb.zip atomically; validate=true refuses to write an invalid MFDB. */
    public static String commit(String mountDir, String archivePath, boolean validate) {
        File lockFile = new File(mountDir, ".mfdb_lock");
        File manifestPath = new File(mountDir, "104a.mfdb.bejson");
        if (!lockFile.exists()) {
            throw new MfdbCoreException("No active mount session found in " + mountDir,
                    BejsonErrors.E_MFDB_CORE_INVALID_OPERATION);
        }
        if (validate) {
            if (!manifestPath.exists()) {
                throw new MfdbCoreException("Commit rejected: Manifest missing in workspace.",
                        BejsonErrors.E_MFDB_CORE_WRITE_FAILED);
            }
            try {
                MfdbValidator.validateDatabase(manifestPath.getPath());
            } catch (RuntimeException e) {
                throw new MfdbCoreException("Commit rejected: Validation failed. " + e.getMessage(),
                        BejsonErrors.E_MFDB_CORE_WRITE_FAILED);
            }
        }
        Map<String, Object> lockData = readLock(lockFile);
        Object destObj = (archivePath != null && !archivePath.isEmpty()) ? archivePath : lockData.get("archive_path");
        if (!Json.truthy(destObj)) {
            throw new MfdbCoreException("Destination archive path unknown.", BejsonErrors.E_MFDB_CORE_ARCHIVE_ERROR);
        }
        String dest = Json.pyStr(destObj);

        File tempArc = null;
        try {
            File destParent = new File(dest).getAbsoluteFile().getParentFile();
            if (destParent != null) destParent.mkdirs();
            tempArc = File.createTempFile("mfdbarc", ".mfdb.zip", destParent); // same filesystem as dest
            List<String> rels = new ArrayList<String>();
            collect(new File(mountDir), "", rels);
            Collections.sort(rels);
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempArc));
            try {
                for (String rel : rels) {
                    if (rel.equals(".mfdb_lock")) continue;
                    zos.putNextEntry(new ZipEntry(rel));
                    InputStream in = new FileInputStream(new File(mountDir, rel));
                    try {
                        byte[] buf = new byte[65536];
                        int r;
                        while ((r = in.read(buf)) > 0) zos.write(buf, 0, r);
                    } finally {
                        in.close();
                    }
                    zos.closeEntry();
                }
            } finally {
                zos.close();
            }
            File parent = new File(dest).getAbsoluteFile().getParentFile();
            if (parent != null) parent.mkdirs();
            try {
                Files.move(tempArc.toPath(), new File(dest).toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(tempArc.toPath(), new File(dest).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            tempArc = null;
            lockData.put("original_hash", MfdbCore.calculateFileHash(dest));
            writeLock(lockFile, lockData);
        } catch (IOException e) {
            throw new MfdbCoreException("Commit failed: " + e.getMessage(), BejsonErrors.E_MFDB_CORE_WRITE_FAILED);
        } catch (RuntimeException e) {
            throw new MfdbCoreException("Commit failed: " + e.getMessage(), BejsonErrors.E_MFDB_CORE_WRITE_FAILED);
        } finally {
            if (tempArc != null && tempArc.exists()) tempArc.delete();
        }
        return dest;
    }

    public static String commit(String mountDir) { return commit(mountDir, null, true); }

    private static void collect(File dir, String prefix, List<String> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (Files.isSymbolicLink(k.toPath())) continue; // links could pull outside files into the archive
            String rel = prefix.isEmpty() ? k.getName() : prefix + "/" + k.getName();
            if (k.isDirectory()) collect(k, rel, out); else out.add(rel);
        }
    }

    /** Pulls one file back out of the archive into the mounted workspace. */
    public static boolean resurrectFile(String mountDir, String relativePath) {
        File lockFile = new File(mountDir, ".mfdb_lock");
        if (!lockFile.exists()) return false;
        Map<String, Object> lockData = readLock(lockFile);
        Object ap = lockData.get("archive_path");
        if (!Json.truthy(ap) || !new File(Json.pyStr(ap)).exists()) return false;
        ZipFile zip = null;
        try {
            zip = new ZipFile(Json.pyStr(ap));
            ZipEntry e = zip.getEntry(relativePath);
            if (e != null) {
                String safe = BejsonPathGuard.safeJoin(mountDir, relativePath);
                if (e.isDirectory()) {
                    PyFs.makedirs(safe);
                } else {
                    PyFs.makedirs(PyFs.dirname(safe));
                    InputStream in = zip.getInputStream(e);
                    try {
                        OutputStream out = new FileOutputStream(safe);
                        try {
                            BejsonPathGuard.copyBounded(in, out, new BejsonPathGuard.Budget());
                        } finally {
                            out.close();
                        }
                    } finally {
                        in.close();
                    }
                }
                return true;
            }
        } catch (IOException ignored) {
            // fall through
        } catch (RuntimeException ignored) {
            // fall through
        } finally {
            if (zip != null) {
                try { zip.close(); } catch (IOException ignored) { /* best effort */ }
            }
        }
        return false;
    }

    /** Releases the session lock and, with cleanup, deletes the extracted workspace. */
    public static void unmount(String mountDir, boolean cleanup) {
        File lockFile = new File(mountDir, ".mfdb_lock");
        if (lockFile.exists()) lockFile.delete();
        if (cleanup && new File(mountDir).exists()) PyFs.rmtree(mountDir);
    }

    public static void unmount(String mountDir) { unmount(mountDir, true); }
}
