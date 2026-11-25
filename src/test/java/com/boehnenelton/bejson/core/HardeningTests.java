/*
 * File Name:       HardeningTests.java
 * Relative Path:   src/test/java/com/boehnenelton/bejson/core/HardeningTests.java
 * Description:     Robustness tests that have no Python oracle: deep JSON, concurrency, stale locks, symlink/zip-slip, bomb limits.
 * VERSION:         1.0.0
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   3b1f6d0e-8c52-4e7a-9a4f-6d2e1b7c5a90
 */
package com.boehnenelton.bejson.core;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class HardeningTests {
    private static List<Object> F(Object... nt) {
        List<Object> l = new ArrayList<Object>();
        for (int i = 0; i < nt.length; i += 2) { Map<String, Object> f = Json.obj(); f.put("name", nt[i]); f.put("type", nt[i + 1]); l.add(f); }
        return l;
    }

    public static void main(String[] args) throws Exception {
        final TestKit t = new TestKit("HardeningTests");
        File root = TestKit.tempDir("hard");
        String rp = root.getPath();

        // 1. deep JSON must not blow the stack
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100000; i++) deep.append('[');
        try { Json.loads(deep.toString()); t.check("deep loads throws", false); } catch (Json.JsonRecursionError e) { t.check("deep loads throws RecursionError", true); }
        t.check("deep validate is an error not a crash", !BejsonValidator.validateBejson(deep.toString()).valid);
        Object cyc = new ArrayList<Object>(); ((List<Object>) cyc).add(cyc);
        try { Json.dumps(cyc); t.check("cyclic dumps throws", false); } catch (Json.JsonRecursionError e) { t.check("cyclic dumps throws", true); }
        try { Json.deepCopy(cyc); t.check("cyclic deepCopy throws", false); } catch (Json.JsonRecursionError e) { t.check("cyclic deepCopy throws", true); }

        // 2. concurrent record adds serialised by the PID lock
        final String mp = MfdbCore.createDatabase(rp + "/db", "Conc", new ArrayList<Map<String, Object>>(java.util.Arrays.asList(entity("T", F("id", "integer")))));
        ExecutorService ex = Executors.newFixedThreadPool(8);
        List<Future<Boolean>> fs = new ArrayList<Future<Boolean>>();
        for (int w = 0; w < 8; w++) {
            final int base = w * 10;
            fs.add(ex.submit(new Callable<Boolean>() { public Boolean call() {
                for (int i = 0; i < 10; i++) MfdbCore.addEntityRecord(mp, "T", Json.asList(Json.arr(Long.valueOf(base + i))));
                return Boolean.TRUE; } }));
        }
        boolean allOk = true;
        for (Future<Boolean> f : fs) { try { allOk &= f.get().booleanValue(); } catch (Exception e) { allOk = false; System.err.println(e); } }
        t.check("concurrent adds all succeed", allOk);
        t.eq("concurrent adds: no lost rows", 80L, Long.valueOf(MfdbCore.loadEntity(mp, "T").size()));
        t.eq("concurrent adds: manifest count", 80L, Json.asMap(Json.asList(MfdbCore.loadManifest(mp)).get(0)).get("record_count"));
        t.check("lock dir released", !new File(rp + "/db/data/t.bejson.lockdir").exists());

        // 3. concurrent nesting scans / cache churn
        final Map<String, Object> inner = BejsonCore.create104("Items", F("sku", "string"), Json.asList(Json.arr(Json.arr("a"))));
        final Map<String, Object> outer = BejsonCore.create104("Order", F("items", "string"), Json.asList(Json.arr(Json.arr(Json.dumps(inner)))));
        List<Future<Boolean>> ns = new ArrayList<Future<Boolean>>();
        for (int w = 0; w < 8; w++) {
            ns.add(ex.submit(new Callable<Boolean>() { public Boolean call() {
                for (int i = 0; i < 300; i++) {
                    BejsonCoreNesting.scan(Json.deepCopy(outer));
                    if (i % 50 == 0) BejsonCoreNesting.cacheClear();
                    BejsonCoreNesting.cacheStats();
                }
                return Boolean.TRUE; } }));
        }
        boolean nok = true;
        for (Future<Boolean> f : ns) { try { nok &= f.get().booleanValue(); } catch (Exception e) { nok = false; System.err.println(e); } }
        t.check("concurrent nesting scans survive", nok);
        ex.shutdownNow();

        // 4. stale lock from a dead pid is taken over
        String target = rp + "/stale.json";
        File lockDir = new File(target + ".lockdir");
        lockDir.mkdirs();
        Files.write(new File(lockDir, "lock_meta.json").toPath(),
            "{\"pid\": 999999999, \"timestamp\": 1.0, \"token\": \"dead\"}".getBytes(StandardCharsets.UTF_8));
        ResilientPidLock l = ResilientPidLock.enter(target, 3);
        t.check("stale lock taken over", l != null);
        l.close();
        t.check("stale lock cleaned", !lockDir.exists());

        // 5. zip-slip variants are rejected and write nothing outside
        File evil = new File(rp + "/evil.zip");
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(evil));
        z.putNextEntry(new ZipEntry("104a.mfdb.bejson")); z.write("{}".getBytes(StandardCharsets.UTF_8)); z.closeEntry();
        z.putNextEntry(new ZipEntry("a/../../escape.txt")); z.write("x".getBytes(StandardCharsets.UTF_8)); z.closeEntry();
        z.close();
        try { MfdbArchive.mount(evil.getPath(), rp + "/m1"); t.check("evil zip rejected", false); } catch (RuntimeException e) { t.check("evil zip rejected", true); }
        t.check("no escape file", !new File(rp + "/escape.txt").exists());

        // 6. symlink planted inside the mount dir must not redirect writes outside it
        File outside = new File(rp + "/outside"); outside.mkdirs();
        File mnt = new File(rp + "/m2"); mnt.mkdirs();
        boolean symOk = true;
        try { Files.createSymbolicLink(new File(mnt, "link").toPath(), outside.toPath()); } catch (Exception e) { symOk = false; }
        if (symOk) {
            File zl = new File(rp + "/link.zip");
            ZipOutputStream z2 = new ZipOutputStream(new FileOutputStream(zl));
            z2.putNextEntry(new ZipEntry("link/pwned.txt")); z2.write("x".getBytes(StandardCharsets.UTF_8)); z2.closeEntry();
            z2.close();
            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zl);
            try { BejsonPathGuard.safeExtractZip(zf, mnt.getPath()); t.check("symlink escape rejected", false); } catch (RuntimeException e) { t.check("symlink escape rejected", true); }
            zf.close();
            t.check("nothing written through symlink", !new File(outside, "pwned.txt").exists());
        }

        // 7. oversized / bomb archive is refused by limits
        File bomb = new File(rp + "/bomb.zip");
        ZipOutputStream zb = new ZipOutputStream(new FileOutputStream(bomb));
        byte[] chunk = new byte[1 << 20];
        zb.putNextEntry(new ZipEntry("104a.mfdb.bejson"));
        for (int i = 0; i < 40; i++) zb.write(chunk);
        zb.closeEntry(); zb.close();
        BejsonPathGuard.setMaxExtractBytes(16L << 20);
        try { MfdbArchive.mount(bomb.getPath(), rp + "/m3"); t.check("zip bomb refused", false); } catch (RuntimeException e) { t.check("zip bomb refused", true); }
        BejsonPathGuard.setMaxExtractBytes(BejsonPathGuard.DEFAULT_MAX_EXTRACT_BYTES);

        // 8. directory/file links are never followed by copytree or the chunk walk (cycles, exfiltration)
        File tree = new File(rp + "/tree"); tree.mkdirs();
        PyFs.writeText(tree.getPath() + "/a.txt", "hello");
        File secret = new File(rp + "/secret.txt"); PyFs.writeText(secret.getPath(), "TOPSECRET");
        boolean link2 = true;
        try {
            Files.createSymbolicLink(new File(tree, "loop").toPath(), tree.toPath());
            Files.createSymbolicLink(new File(tree, "leak.txt").toPath(), secret.toPath());
        } catch (Exception e) { link2 = false; }
        if (link2) {
            try { PyFs.copytree(tree.getPath(), rp + "/tree_copy"); t.check("copytree survives link cycle", true); }
            catch (Throwable e) { t.check("copytree survives link cycle", false); }
            t.check("copytree skipped links", new File(rp + "/tree_copy/a.txt").exists() && !new File(rp + "/tree_copy/loop").exists());
            Map<String, Object> cd = BejsonChunking.createChunked104(tree.getPath(), "1.0", java.util.Arrays.asList(".txt"), null, "100");
            t.check("chunk walk skips linked file", !Json.dumps(cd).contains("TOPSECRET") && Json.dumps(cd).contains("hello"));
        }

        // 9. storage-root mapping respects path boundaries
        String sr = BejsonPathGuard.resolveStoragePath("/storage/emulated/0abc/x");
        t.check("storage prefix needs boundary", sr.equals("/storage/emulated/0abc/x"));
        String sm = BejsonPathGuard.resolveStoragePath("/storage/emulated/0/x");
        t.check("storage prefix mapped", !sm.startsWith("/storage/emulated/0") || BejsonEnv.getenv("BEJSON_STORAGE_ROOT") != null);

        // 10. sortByField(reverse) is one stable sort: descending, equal keys keep original order
        String sdoc = "{\"Format\":\"BEJSON\",\"Format_Version\":\"104\",\"Format_Creator\":\"x\",\"Records_Type\":[\"T\"],"
            + "\"Fields\":[{\"name\":\"k\",\"type\":\"integer\"},{\"name\":\"id\",\"type\":\"string\"}],"
            + "\"Values\":[[1,\"a\"],[2,\"b\"],[1,\"c\"],[2,\"d\"]]}";
        Map<String, Object> sd = Json.asMap(Json.loads(sdoc));
        BejsonCore.sortByField(sd, "k", true);
        t.check("sort reverse stable", Json.dumps(sd.get("Values")).replaceAll("\\s", "").equals("[[2,\"b\"],[2,\"d\"],[1,\"a\"],[1,\"c\"]]"));
        t.check("release version constant", BejsonCore.RELEASE_VERSION == 300);

        PyFs.rmtree(rp);
        t.finish();
    }

    private static Map<String, Object> entity(String name, List<Object> fields) {
        Map<String, Object> m = Json.obj(); m.put("name", name); m.put("fields", fields); return m;
    }
}
