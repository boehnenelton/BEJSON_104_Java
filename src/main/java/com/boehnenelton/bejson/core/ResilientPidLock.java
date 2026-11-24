/*
 * File Name:       ResilientPidLock.java
 * Relative Path:   src/main/java/com/boehnenelton/bejson/core/ResilientPidLock.java
 * Description:     Port of ResilientPIDLock (lib_bejson_Core_bejson_core.py 2.0.10): cross-process mutex for one file,
 *                  an atomic-mkdir lock directory "<path>.lockdir" with lock_meta.json {pid, timestamp, token}.
 *                  Same on-disk layout as the Python/other-language libraries, so handles interoperate.
 *                  Use: try (ResilientPidLock l = ResilientPidLock.enter(path, 10)) { ... }
 * Android note:    PID liveness uses /proc/&lt;pid&gt; where available, else ProcessHandle (Java 9+) by reflection.
 * VERSION:         1.0.0 (reference 2.0.10)
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   5e19840f-29b9-495a-86f9-85b04adaaafb
 */
package com.boehnenelton.bejson.core;

import java.io.File;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ResilientPidLock implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger("lib_bejson_Core_bejson_core");

    static final double INIT_GRACE = 2.0;
    static final double GUARD_STALE = 30.0;
    static final double POLL = 0.1;

    private final String target;
    private final File lockDir;
    private final File metaFile;
    private final double timeout;
    private final File guardDir;
    private String token;
    private long ownerPid = -1;

    public ResilientPidLock(String targetPath, int timeoutSeconds) {
        this.target = targetPath;
        this.lockDir = new File(targetPath + ".lockdir");
        this.metaFile = new File(lockDir, "lock_meta.json");
        this.timeout = timeoutSeconds;
        this.guardDir = new File(targetPath + ".lockdir.reclaim");
    }

    public ResilientPidLock(String targetPath) { this(targetPath, 10); }

    /** Context-manager entry: acquires or throws BejsonOSError(53, E_MFDB_CORE_LOCK_FAILED). */
    public static ResilientPidLock enter(String targetPath, int timeoutSeconds) {
        ResilientPidLock l = new ResilientPidLock(targetPath, timeoutSeconds);
        if (!l.acquire()) {
            throw new BejsonOSError(53, "Mutex lock timeout expired (E_MFDB_CORE_LOCK_FAILED)");
        }
        return l;
    }

    // ------------------------------------------------------------ pid helpers

    private static volatile long cachedPid = -2;

    /** PID of this process (Android and desktop JVMs). */
    public static long currentPid() {
        long p = cachedPid;
        if (p != -2) return p;
        p = -1;
        try {
            Class<?> ph = Class.forName("java.lang.ProcessHandle");
            Object cur = ph.getMethod("current").invoke(null);
            p = ((Number) ph.getMethod("pid").invoke(cur)).longValue();
        } catch (Throwable ignored) {
            // not Java 9+
        }
        if (p < 0) {
            try {
                Class<?> pr = Class.forName("android.os.Process");
                p = ((Number) pr.getMethod("myPid").invoke(null)).longValue();
            } catch (Throwable ignored) {
                // not Android
            }
        }
        if (p < 0) {
            try {
                p = Long.parseLong(new File("/proc/self").getCanonicalFile().getName());
            } catch (Throwable ignored) {
                p = Math.abs(System.identityHashCode(ResilientPidLock.class));
            }
        }
        cachedPid = p;
        return p;
    }

    static boolean pidAlive(long pid) {
        if (pid == currentPid()) return true;
        File procRoot = new File("/proc");
        if (procRoot.isDirectory()) {
            return new File(procRoot, Long.toString(pid)).exists();
        }
        try {
            Class<?> ph = Class.forName("java.lang.ProcessHandle");
            Method of = ph.getMethod("of", long.class);
            Object opt = of.invoke(null, Long.valueOf(pid));
            return ((Boolean) opt.getClass().getMethod("isPresent").invoke(opt)).booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------ internals

    private Map<String, Object> readMeta() {
        try {
            Object m = Json.loads(PyFs.readText(metaFile.getPath()));
            return Json.asMap(m);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Identity of the judged holder, or null when live / gone / too young. */
    private List<Object> judgeStale() {
        Map<String, Object> meta = readMeta();
        if (meta != null) {
            Object pid = meta.get("pid");
            long pidv = -1;
            if (pid instanceof Long) pidv = (Long) pid;
            else if (pid instanceof BigInteger) pidv = Long.MAX_VALUE;
            if (pidv > 0) {
                if (pidAlive(pidv)) return null;
                return Arrays.<Object>asList("dead", Long.valueOf(pidv), meta.get("token"), meta.get("timestamp"));
            }
        }
        if (!lockDir.exists()) return null;
        long mtime = lockDir.lastModified();
        if ((System.currentTimeMillis() - mtime) / 1000.0 > INIT_GRACE) {
            return Arrays.<Object>asList("abandoned", Long.valueOf(mtime));
        }
        return null;
    }

    private boolean discard(File path) {
        File trash = new File(path.getPath() + "." + PyFs.uuidHex() + ".trash");
        if (!path.renameTo(trash)) return false;
        PyFs.rmtree(trash);
        return true;
    }

    private boolean reclaimStale(List<Object> judged) {
        if (!guardDir.mkdir()) {
            if (guardDir.exists()) {
                long age = System.currentTimeMillis() - guardDir.lastModified();
                if (age / 1000.0 > GUARD_STALE) discard(guardDir);
            }
            return false;
        }
        try {
            List<Object> now = judgeStale();
            if (now == null || !now.equals(judged)) return false;
            return discard(lockDir);
        } finally {
            guardDir.delete();
        }
    }

    /** Failure cleanup: never delete a lock another process reclaimed and re-created while we were stalled. */
    private void discardIfStillOurs(String tok) {
        try {
            if (metaFile.exists()) {
                Object o = Json.loads(PyFs.readText(metaFile.getPath()));
                if (o instanceof Map && !tok.equals(((Map<?, ?>) o).get("token"))) return;
            }
        } catch (RuntimeException ignored) {
            // unreadable meta: fall through and clean up
        }
        discard(lockDir);
    }

    private boolean tryCreate() {
        if (!lockDir.mkdir()) {
            if (lockDir.exists()) return false;
            throw BejsonOSError.notFound(lockDir.getPath());
        }
        String tok = PyFs.uuidHex();
        File tmp = new File(lockDir, ".meta-" + tok + ".tmp");
        try {
            Map<String, Object> meta = new LinkedHashMap<String, Object>();
            meta.put("pid", Long.valueOf(currentPid()));
            meta.put("timestamp", Long.valueOf(System.currentTimeMillis() / 1000L));
            meta.put("token", tok);
            PyFs.writeText(tmp.getPath(), Json.dumps(meta));
            PyFs.replace(tmp, metaFile);
        } catch (java.io.IOException e) {
            discardIfStillOurs(tok);
            throw BejsonOSError.wrap(e, lockDir.getPath());
        } catch (RuntimeException e) {
            discardIfStillOurs(tok);
            throw e;
        }
        this.token = tok;
        this.ownerPid = currentPid();
        return true;
    }

    // ------------------------------------------------------------ public API

    /** Takes the lock, waiting up to the timeout (at least one attempt). False on timeout. */
    public boolean acquire() {
        long deadline = System.nanoTime() + (long) (timeout * 1e9);
        while (true) {
            if (tryCreate()) return true;
            double remaining = (deadline - System.nanoTime()) / 1e9;
            if (remaining <= 0) return false;
            List<Object> judged = judgeStale();
            if (judged != null && reclaimStale(judged)) continue;
            try {
                Thread.sleep((long) (Math.min(POLL, remaining) * 1000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** Releases the lock only if this handle still owns it. */
    public void release() {
        String tok = this.token;
        this.token = null;
        if (tok == null || ownerPid != currentPid()) return;
        Map<String, Object> meta = readMeta();
        if (meta == null || !tok.equals(meta.get("token"))) {
            LOG.log(Level.WARNING, "[BEJSON_CORE] Lock {0} is no longer owned by this handle; not releasing", lockDir);
            return;
        }
        discard(lockDir);
    }

    @Override
    public void close() { release(); }

    public String getTarget() { return target; }
}
