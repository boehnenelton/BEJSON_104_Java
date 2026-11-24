/*
 * File Name:       ParityD.java
 * Relative Path:   src/test/java/com/boehnenelton/bejson/core/ParityD.java
 * Description:     Parity driver D: chunking, MFDB-132, MFDB validator, archives, parse vs. dev/parity/parity_py_d.py.
 * VERSION:         1.0.0
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   98191c50-2c59-4102-8e9c-2f6314c6c6f1
 */
package com.boehnenelton.bejson.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ParityD {
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern TS = Pattern.compile("\\d{8}T\\d{6}Z|\\d{4}-\\d\\d-\\d\\d[T _][0-9:.]+(?:Z|[+-]\\d\\d:\\d\\d)?|\\d{8}_\\d{6}");
    private static String fx, work, out;
    private static TestKit t;
    private static Map<String, Object> exp;

    private static String norm(String s) {
        s = s.replace(fx, "<FX>").replace(work, "<WORK>").replace(out, "<OUT>");
        s = UUID.matcher(s).replaceAll("<UUID>");
        return TS.matcher(s).replaceAll("<TS>");
    }

    private static Object J(Object v) { return Json.loads(norm(Json.dumps(v, -1, true, true))); }

    private static Object attempt(Callable<Object> fn) {
        try { return fn.call(); }
        catch (Exception e) {
            String n = e.getClass().getSimpleName();
            Object code = null;
            if (e instanceof BejsonCoreException) { n = "BEJSONCoreError"; Integer c = ((BejsonCoreException) e).code; code = c == null ? null : Long.valueOf(c); }
            else if (e instanceof MfdbCoreException) { n = "MFDBCoreError"; code = Long.valueOf(((MfdbCoreException) e).code); }
            else if (e instanceof MfdbValidator.MfdbValidationException) { n = "MFDBValidationError"; code = Long.valueOf(((MfdbValidator.MfdbValidationException) e).code); }
            else if (e instanceof BejsonValidationException) { n = "BEJSONValidationError"; code = Long.valueOf(((BejsonValidationException) e).code); }
            else if (e instanceof Json.JsonParseException) n = "JSONDecodeError";
            else if (e instanceof IllegalArgumentException) n = "ValueError";
            else if (e instanceof IllegalStateException) n = "RuntimeError";
            Map<String, Object> m = Json.obj();
            m.put("EXC", n); m.put("msg", norm(String.valueOf(e.getMessage()))); m.put("code", code);
            return m;
        }
    }

    /** Python's os.walk order is filesystem-dependent; Java's is sorted (documented deviation), so compare rows ordered by Relative_Path. */
    private static Object sortRows(Object o) {
        Map<String, Object> doc = Json.asMap(o);
        final int ri = idx(doc, "Relative_Path");
        List<Object> vals = Json.asList(doc.get("Values"));
        if (ri >= 0 && vals != null) {
            Collections.sort(vals, new java.util.Comparator<Object>() {
                public int compare(Object a, Object b) { return String.valueOf(Json.asList(a).get(ri)).compareTo(String.valueOf(Json.asList(b).get(ri))); }
            });
        }
        return doc;
    }

    private static void diff(String n, Object a, Object b) {
        if (Json.pyEquals(a, b)) return;
        if (a instanceof Map && b instanceof Map) {
            for (Object k : ((Map<?, ?>) a).keySet()) if (!Json.pyEquals(((Map<?, ?>) a).get(k), ((Map<?, ?>) b).get(k)))
                System.err.println("  DIFF " + n + " key " + k + ": py=" + Json.pyRepr(((Map<?, ?>) a).get(k)).substring(0, Math.min(300, Json.pyRepr(((Map<?, ?>) a).get(k)).length())) + " | java=" + Json.pyRepr(((Map<?, ?>) b).get(k)).substring(0, Math.min(300, Json.pyRepr(((Map<?, ?>) b).get(k)).length())));
        }
    }

    private static Object tree(String d) {
        File f = new File(d);
        if (!f.isDirectory()) return null;
        Map<String, Object> r = new TreeMap<String, Object>();
        walk(f, f, r);
        return Json.loads(Json.dumps(r, -1, true, true));
    }

    private static void walk(File base, File f, Map<String, Object> out) {
        File[] kids = f.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) { walk(base, k, out); continue; }
            String rel = base.toURI().relativize(k.toURI()).getPath();
            try {
                byte[] h = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(k.toPath()));
                StringBuilder sb = new StringBuilder();
                for (byte b : h) sb.append(String.format("%02x", b & 0xff));
                String nm = k.getName();
                boolean vol = nm.equals(".mfdb132_lock") || nm.equals(".mfdb_lock") || nm.equals("_REPORT.txt") || nm.endsWith("_update.zip");
                out.put(rel, vol ? "<volatile>" : sb.toString());
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    private static void eq(String name, String key, Object got) { t.eq(name, exp.get(key), got); }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = Json.obj();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> R(MfdbValidator.MfdbValidationResult r) {
        return mapOf("valid", r.valid, "errors", new ArrayList<Object>(r.errors), "warnings", new ArrayList<Object>(r.warnings));
    }

    private static Map<String, Object> docOf(String path) { return Json.asMap(Json.loads(PyFs.readText(path))); }

    private static Map<String, Object> toObj(Map<String, Integer> m) {
        Map<String, Object> o = Json.obj();
        for (Map.Entry<String, Integer> e : m.entrySet()) o.put(e.getKey(), Long.valueOf(e.getValue()));
        return o;
    }

    private static void edit(String path, MapFn fn) {
        Map<String, Object> d = docOf(path);
        fn.apply(d);
        write(path, Json.dumps(d, 2, true));
    }

    private interface MapFn { void apply(Map<String, Object> d); }

    private static void write(String path, String text) {
        try { new File(path).getParentFile().mkdirs(); Files.write(new File(path).toPath(), text.getBytes(StandardCharsets.UTF_8)); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
    }

    private static int idx(Map<String, Object> doc, String name) {
        List<Object> f = Json.asList(doc.get("Fields"));
        for (int i = 0; i < f.size(); i++) if (name.equals(Json.asMap(f.get(i)).get("name"))) return i;
        return -1;
    }

    public static void main(String[] args) throws Exception {
        t = new TestKit("ParityD");
        out = new File(args[0]).getAbsolutePath();
        fx = out + "/fx";
        work = out + "/work_java";
        PyFs.rmtree(work); new File(work).mkdirs();
        exp = Json.asMap(Json.loads(PyFs.readText(args[0] + "/d_expected.json")));
        final String PR = fx + "/proj";
        // ---- basic helpers
        Map<String, Object> isb = Json.obj();
        for (String f : new String[] {"a.py", "bin/x.bin", "empty.txt", "latin1.txt", "note.xyz"}) isb.put(f, BejsonChunking.isBinary(PR + "/" + f));
        eq("is_binary", "is_binary", isb);
        eq("hash", "hash", Json.arr(BejsonChunking.hashFileBytes(new byte[0]), BejsonChunking.hashFileBytes("abc".getBytes(StandardCharsets.UTF_8))));
        List<Object> bumps = new ArrayList<Object>();
        bumps.add(BejsonChunking.bumpPackageVersion(null));
        bumps.add(BejsonChunking.bumpPackageVersion(Json.obj()));
        bumps.add(BejsonChunking.bumpPackageVersion(mapOf("Package_Version", "3")));
        bumps.add(BejsonChunking.bumpPackageVersion(mapOf("Package_Version", "x")));
        bumps.add(BejsonChunking.bumpPackageVersion(mapOf("Package_Version", 7L)));
        bumps.add(BejsonChunking.bumpPackageVersion(mapOf("Package_Version", null)));
        eq("bump", "bump", bumps);
        // ---- chunk / unchunk
        Map<String, Object> ec = Json.asMap(exp.get("chunk"));
        Map<String, Object> eu = Json.asMap(exp.get("unchunk"));
        String[] names = {"default", "ver", "ext", "excl", "pkg", "all"};
        Map<String, Map<String, Object>> docs = new java.util.LinkedHashMap<String, Map<String, Object>>();
        for (String k : names) {
            Map<String, Object> d;
            if (k.equals("default")) d = BejsonChunking.createChunked104(PR);
            else if (k.equals("ver")) d = BejsonChunking.createChunked104(PR, "2.0");
            else if (k.equals("ext")) d = BejsonChunking.createChunked104(PR, "latest", Arrays.asList(".py", ".md"), null, null);
            else if (k.equals("excl")) d = BejsonChunking.createChunked104(PR, "latest", null, Arrays.asList("docs"), null);
            else if (k.equals("pkg")) d = BejsonChunking.createChunked104(PR, "latest", null, null, "9");
            else d = BejsonChunking.createChunked104(PR, "v", Arrays.asList(".py", ".bin", ".xyz", ".json", ".txt", ".md"), null, "5");
            docs.put(k, d);
            Object gj = sortRows(J(d)), ej = sortRows(Json.deepCopy(ec.get(k)));
            diff("chunk " + k, ej, gj); t.eq("chunk " + k, ej, gj);
            final String o = work + "/un_" + k;
            final Map<String, Object> dd = Json.asMap(Json.deepCopy(d));
            Object n = attempt(new Callable<Object>() { public Object call() { return BejsonChunking.unchunkChunked104(dd, o); } });
            t.eq("unchunk " + k, eu.get(k), mapOf("n", n, "tree", tree(o)));
        }
        Map<String, Object> bad = Json.asMap(Json.deepCopy(docs.get("default")));
        Json.asList(Json.asList(bad.get("Values")).get(0)).set(idx(bad, "Relative_Path"), "../escape.txt");
        final String oe = work + "/un_escape"; final Map<String, Object> badF = bad;
        Object r = attempt(new Callable<Object>() { public Object call() { return BejsonChunking.unchunkChunked104(badF, oe); } });
        eq("unchunk_escape", "unchunk_escape", mapOf("r", r, "tree", tree(oe), "esc", new File(work + "/escape.txt").exists()));
        Map<String, Object> hb = Json.asMap(Json.deepCopy(docs.get("default")));
        Json.asList(Json.asList(hb.get("Values")).get(0)).set(idx(hb, "File_Hash"), "deadbeef");
        final String oh = work + "/un_hash"; final Map<String, Object> hbF = hb;
        r = attempt(new Callable<Object>() { public Object call() { return BejsonChunking.unchunkChunked104(hbF, oh); } });
        eq("unchunk_badhash", "unchunk_badhash", mapOf("r", r, "tree", tree(oh)));
        r = attempt(new Callable<Object>() { public Object call() { return BejsonChunking.unchunkChunked104(mapOf("x", 1L), work + "/un_nd"); } });
        eq("unchunk_notdoc", "unchunk_notdoc", r);

        // ---- MFDB validator
        final String DBA = fx + "/db_ok";
        final String mpA = DBA + "/104a.mfdb.bejson";
        Map<String, Object> emv = Json.asMap(exp.get("mv"));
        for (final String k : emv.keySet()) {
            final String d = fx + "/db_" + k;
            final String m = new File(d + "/104a.mfdb.bejson").exists() ? d + "/104a.mfdb.bejson" : d + "/manifest.json";
            Map<String, Object> got = Json.obj();
            got.put("manifest", attempt(new Callable<Object>() { public Object call() { return R(MfdbValidator.validateMfdbManifest(m)); } }));
            got.put("db", attempt(new Callable<Object>() { public Object call() { return R(MfdbValidator.validateMfdbDatabase(m)); } }));
            got.put("db_strict", attempt(new Callable<Object>() { public Object call() { return R(MfdbValidator.validateMfdbDatabase(m, true)); } }));
            got.put("entity", attempt(new Callable<Object>() { public Object call() { return R(MfdbValidator.validateMfdbEntityFile(d + "/data/customer.bejson")); } }));
            got.put("entity_nobidi", attempt(new Callable<Object>() { public Object call() { return R(MfdbValidator.validateMfdbEntityFile(d + "/data/order.bejson", false)); } }));
            got.put("raise_manifest", attempt(new Callable<Object>() { public Object call() { return MfdbValidator.validateManifest(m); } }));
            got.put("raise_db", attempt(new Callable<Object>() { public Object call() { return MfdbValidator.validateDatabase(m); } }));
            t.eq("mv " + k, emv.get(k), J(got));
        }
        Map<String, Object> hp = mapOf(
            "rows", MfdbValidator.rowsAsDicts(docOf(mpA)),
            "resolve", MfdbValidator.resolveEntityPath(mpA, "data/customer.bejson"),
            "load_bad", attempt(new Callable<Object>() { public Object call() { return MfdbValidator.loadJson(fx + "/db_manifest_notjson/104a.mfdb.bejson"); } }),
            "load_missing", attempt(new Callable<Object>() { public Object call() { return MfdbValidator.loadJson(DBA + "/zzz"); } }));
        Map<String, Object> hpj = Json.asMap(J(hp)), hpe = Json.asMap(Json.deepCopy(exp.get("mv_helpers")));
        // OS error wording is Python-internal ("[Errno 2] ..."); Java carries the same text without the errno prefix.
        t.check("mv_helpers load_missing text", String.valueOf(Json.asMap(hpe.get("load_missing")).get("msg")).endsWith(String.valueOf(Json.asMap(hpj.get("load_missing")).get("msg"))));
        hpj.remove("load_missing"); hpe.remove("load_missing");
        t.eq("mv_helpers", hpe, hpj);

        // ---- MFDB-132
        final Map<String, Object> pk = BejsonChunking.createMfdb132Package(DBA, "Shop");
        eq("m132_pkg", "m132_pkg", J(pk));
        eq("m132_pkg2", "m132_pkg2", J(BejsonChunking.createMfdb132Package(DBA, "Shop", null, null, "4", pk)));
        eq("m132_pkg3", "m132_pkg3", J(attempt(new Callable<Object>() { public Object call() { return BejsonChunking.createMfdb132Package(DBA, "Shop", null, null, null, pk); } })));
        final Map<String, Object> dflt = docs.get("default");
        eq("m132_is", "m132_is", Json.arr(BejsonChunking.isMfdb132Package(pk), BejsonChunking.isMfdb132Package(dflt), BejsonChunking.isMfdb132Package(Json.obj())));
        eq("m132_validate", "m132_validate", J(Json.arr(
            attempt(new Callable<Object>() { public Object call() { return BejsonChunking.validateMfdb132Package(pk); } }),
            attempt(new Callable<Object>() { public Object call() { return BejsonChunking.validateMfdb132Package(dflt); } }),
            attempt(new Callable<Object>() { public Object call() { return BejsonChunking.validateMfdb132Package(mapOf("x", 1L)); } }))));
        eq("m132_mv", "m132_mv", J(Json.arr(
            attempt(new Callable<Object>() { public Object call() { return MfdbValidator.validateMfdb132Package(pk); } }),
            attempt(new Callable<Object>() { public Object call() { return MfdbValidator.isMfdb132Package(pk); } }),
            attempt(new Callable<Object>() { public Object call() { return MfdbValidator.detectMfdbInChunk(pk); } }),
            attempt(new Callable<Object>() { public Object call() { return MfdbValidator.detectMfdbInChunk(dflt); } }),
            attempt(new Callable<Object>() { public Object call() { return MfdbValidator.findRowByRelpath(pk, "104a.mfdb.bejson"); } }))));
        eq("m132_detect", "m132_detect", J(Json.arr(
            attempt(new Callable<Object>() { public Object call() { return BejsonChunking.detectMfdbInChunk(pk); } }),
            attempt(new Callable<Object>() { public Object call() { return BejsonChunking.detectMfdbInChunk(dflt); } }),
            attempt(new Callable<Object>() { public Object call() { return BejsonChunking.detectMfdbInChunk(Json.obj()); } }))));
        eq("m132_fm", "m132_fm", J(toObj(BejsonChunking.mfdbGetFieldMap(pk))));
        eq("m132_schema", "m132_schema", Json.arr(BejsonChunking.mfdbDetectSchema(pk), BejsonChunking.mfdbDetectSchema(dflt),
            BejsonChunking.mfdbDetectSchema(docOf(mpA)), BejsonChunking.mfdbDetectSchema(docOf(DBA + "/data/customer.bejson")), BejsonChunking.mfdbDetectSchema(Json.obj())));
        eq("m132_ver", "m132_ver", Json.arr(BejsonChunking.mfdbCheckVersion(pk), BejsonChunking.mfdbCheckVersion(Json.obj()),
            BejsonChunking.mfdbCheckVersion(mapOf("MFDB_Version", "9.9")), BejsonChunking.mfdbCheckVersion(mapOf("MFDB_Version", 1.31))));
        final String om = work + "/m132";
        Object ur = attempt(new Callable<Object>() { public Object call() {
            BejsonChunking.UnchunkResult u = BejsonChunking.unchunkMfdb132Package(Json.asMap(Json.deepCopy(pk)), om);
            return Json.arr(Long.valueOf(u.count), u.validation); } });
        eq("m132_unchunk", "m132_unchunk", mapOf("r", J(ur), "tree", tree(om)));
        Object[][] gens = {{"m132_gen_chunked", dflt}, {"m132_gen_pk", pk}, {"m132_gen_manifest", docOf(mpA)}, {"m132_gen_entity", docOf(DBA + "/data/customer.bejson")}};
        for (Object[] g : gens) {
            final String o = work + "/" + g[0]; final Object dd = Json.deepCopy(g[1]);
            Object rr = attempt(new Callable<Object>() { public Object call() { return BejsonChunking.mfdbUnchunk(Json.asMap(dd), o); } });
            eq((String) g[0], (String) g[0], mapOf("r", J(rr), "tree", tree(o)));
        }

        // ---- MFDB132Archive
        final String pkf = fx + "/shop.chunk.bejson";
        final String mt = work + "/mnt132";
        Map<String, Object> ea = Json.asMap(exp.get("a132"));
        t.eq("a132 mount", ea.get("mount"), J(attempt(new Callable<Object>() { public Object call() { return Mfdb132Archive.mount(pkf, mt); } })));
        t.eq("a132 tree1", ea.get("tree1"), tree(mt));
        t.eq("a132 mount_again", ea.get("mount_again"), J(attempt(new Callable<Object>() { public Object call() { return Mfdb132Archive.mount(pkf, mt); } })));
        t.eq("a132 mount_force", ea.get("mount_force"), J(attempt(new Callable<Object>() { public Object call() { return Mfdb132Archive.mount(pkf, mt, true, true); } })));
        edit(mt + "/data/customer.bejson", new MapFn() { public void apply(Map<String, Object> d) { Json.asList(d.get("Values")).add(Json.arr(9L, "Zed")); } });
        final String outp = work + "/out132.bejson";
        t.eq("a132 commit", ea.get("commit"), J(attempt(new Callable<Object>() { public Object call() { return Mfdb132Archive.commit(mt, outp, true); } })));
        t.eq("a132 committed", ea.get("committed"), new File(outp).exists() ? J(docOf(outp)) : null);
        new File(mt + "/data/order.bejson").delete();
        t.eq("a132 resurrect", ea.get("resurrect"), attempt(new Callable<Object>() { public Object call() { return Mfdb132Archive.resurrectFile(mt, "data/order.bejson"); } }));
        t.eq("a132 resurrect_missing", ea.get("resurrect_missing"), attempt(new Callable<Object>() { public Object call() { return Mfdb132Archive.resurrectFile(mt, "data/nope.bejson"); } }));
        t.eq("a132 tree2", ea.get("tree2"), tree(mt));
        t.eq("a132 unmount", ea.get("unmount"), attempt(new Callable<Object>() { public Object call() { Mfdb132Archive.unmount(mt); return null; } }));
        t.eq("a132 exists_after", ea.get("exists_after"), new File(mt).exists());
        t.eq("a132 commit_nomount", ea.get("commit_nomount"), J(attempt(new Callable<Object>() { public Object call() { return Mfdb132Archive.commit(work + "/nomount", outp, true); } })));
        t.eq("a132 mount_missing", ea.get("mount_missing"), J(attempt(new Callable<Object>() { public Object call() { return Mfdb132Archive.mount(fx + "/nofile.json", work + "/m2"); } })));

        // ---- MfdbArchive (zip)
        final String zp = fx + "/shop.mfdb.zip";
        final String mt2 = work + "/mntzip";
        final String outz = work + "/out.zip";
        Map<String, Object> ez = Json.asMap(exp.get("az"));
        t.eq("az mount", ez.get("mount"), J(attempt(new Callable<Object>() { public Object call() { return MfdbArchive.mount(zp, mt2); } })));
        t.eq("az tree1", ez.get("tree1"), tree(mt2));
        t.eq("az mount_sticky", ez.get("mount_sticky"), J(attempt(new Callable<Object>() { public Object call() { return MfdbArchive.mount(zp, mt2); } })));
        MfdbCore.addEntityRecord(mt2 + "/104a.mfdb.bejson", "Customer", Json.asList(Json.arr(5L, "Eve")));
        t.eq("az mount_nonforce_modified", ez.get("mount_nonforce_modified"), J(attempt(new Callable<Object>() { public Object call() { return MfdbArchive.mount(zp, mt2); } })));
        t.eq("az commit", ez.get("commit"), J(attempt(new Callable<Object>() { public Object call() { return MfdbArchive.commit(mt2, outz, true); } })));
        List<Object> zn = null;
        if (new File(outz).exists()) {
            zn = new ArrayList<Object>();
            ZipFile zf = new ZipFile(outz);
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements();) zn.add(en.nextElement().getName());
            zf.close();
            Collections.sort((List) zn);
        }
        t.eq("az zip_names", ez.get("zip_names"), zn);
        t.eq("az resurrect_present", ez.get("resurrect_present"), attempt(new Callable<Object>() { public Object call() { return MfdbArchive.resurrectFile(mt2, "data/customer.bejson"); } }));
        new File(mt2 + "/data/order.bejson").delete();
        t.eq("az resurrect", ez.get("resurrect"), attempt(new Callable<Object>() { public Object call() { return MfdbArchive.resurrectFile(mt2, "data/order.bejson"); } }));
        t.eq("az resurrect_missing", ez.get("resurrect_missing"), attempt(new Callable<Object>() { public Object call() { return MfdbArchive.resurrectFile(mt2, "data/nope.bejson"); } }));
        t.eq("az unmount", ez.get("unmount"), attempt(new Callable<Object>() { public Object call() { MfdbArchive.unmount(mt2); return null; } }));
        t.eq("az exists_after", ez.get("exists_after"), new File(mt2).exists());
        t.eq("az mount_missing", ez.get("mount_missing"), J(attempt(new Callable<Object>() { public Object call() { return MfdbArchive.mount(fx + "/nofile.zip", work + "/m3"); } })));
        t.eq("az evil", ez.get("evil"), J(attempt(new Callable<Object>() { public Object call() { return MfdbArchive.mount(fx + "/evil.zip", work + "/m4"); } })));
        t.eq("az evil_escaped", ez.get("evil_escaped"), new File(work + "/evil.txt").exists());
        t.eq("az nomanifest", ez.get("nomanifest"), J(attempt(new Callable<Object>() { public Object call() { return MfdbArchive.mount(fx + "/nomanifest.zip", work + "/m5"); } })));

        // ---- parse
        Map<String, Object> ep = Json.asMap(exp.get("parse"));
        String[][] pj = {{"plain", "{\"a\": 1}"}, {"wrapped", "Here you go:\n```json\n{\"a\": [1,2]}\n```\nbye"}, {"nested", "x {\"a\": {\"b\": 1}} y {\"c\": 2}"},
            {"none", "no json"}, {"arr", "[1,2]"}, {"trail", "{\"a\": 1,}"}, {"empty", ""}, {"unicode", "\u00fc {\"\u00e9\": \"\uD83D\uDE00\"} z"}};
        for (final String[] c : pj) t.eq("parse " + c[0], ep.get(c[0]), J(attempt(new Callable<Object>() { public Object call() { return BejsonParse.parseJson(c[1]); } })));
        Map<String, Object> ex = Json.asMap(exp.get("extract"));
        Map<String, Object> ext = Json.obj();
        ext.put("empty", tab(Arrays.asList("a"), new ArrayList<Object>()));
        ext.put("basic", tab(Arrays.asList("Project Name", "File1 Name", "File1 Content", "file2_name", "File2-Content"),
            Json.asList(Json.arr(Json.arr("Pr<oj>/x", "a.txt", " hello ", "b.txt", "world"), Json.arr("Other", null, "x", "c.txt", "")))));
        ext.put("zip", tab(Arrays.asList("ZipFileName", "file1name", "file1content"), Json.asList(Json.arr(Json.arr("Z:z", "f", "c")))));
        ext.put("container", tab(Arrays.asList("container_name"), Json.asList(Json.arr(Json.arr("C")))));
        List<String> mf = new ArrayList<String>(); mf.add("projectname");
        List<Object> mr = new ArrayList<Object>(); mr.add("P");
        for (int i = 1; i <= 51; i++) { mf.add("file" + i + "name"); mr.add("n" + i); }
        for (int i = 1; i <= 51; i++) { mf.add("file" + i + "content"); mr.add("c" + i); }
        ext.put("many", tab(mf, Json.asList(Json.arr(mr))));
        ext.put("short_row", tab(Arrays.asList("projectname", "file1name", "file1content"), Json.asList(Json.arr(Json.arr("P")))));
        ext.put("nofields", mapOf("Values", Json.arr(Json.arr("a"))));
        Map<String, Object> num = Json.obj();
        num.put("Fields", Json.arr(mapOf("name", "projectname"), mapOf("name", "file1name"), mapOf("name", "file1content")));
        num.put("Values", Json.arr(Json.arr(5L, 6L, 7L)));
        ext.put("num", num);
        for (final Map.Entry<String, Object> e : ext.entrySet()) {
            Object g = attempt(new Callable<Object>() { public Object call() {
                BejsonParse.Extracted x = BejsonParse.extractData(Json.asMap(Json.deepCopy(e.getValue())));
                return Json.arr(x.projectName, x.files); } });
            t.eq("extract " + e.getKey(), ex.get(e.getKey()), J(g));
        }
        Map<String, Object> es = Json.asMap(exp.get("save"));
        final String base = work + "/parse_out";
        final List<Map<String, Object>> files = new ArrayList<Map<String, Object>>();
        files.add(mapOf("name", "a.txt", "content", "h\u00e9llo"));
        StringBuilder xs = new StringBuilder(); for (int i = 0; i < 2000; i++) xs.append('x');
        files.add(mapOf("name", "sub/b.txt", "content", xs.toString()));
        files.add(mapOf("name", "c.txt", "content", ""));
        Map<String, Object> cfg1 = mapOf("output_path", base);
        Map<String, Object> cfg2 = mapOf("output_path", base, "overwrite_enabled", Boolean.TRUE);
        Object sr = BejsonParse.saveFiles("Proj", files, cfg1);
        t.eq("save new", es.get("new"), mapOf("r", J(sr), "tree", normTree(tree(base))));
        sr = BejsonParse.saveFiles("Proj", files.subList(0, 1), cfg2);
        t.eq("save over1", es.get("over1"), mapOf("r", J(sr), "tree", normTree(tree(base))));
        sr = BejsonParse.saveFiles("Proj", files.subList(0, 2), cfg2);
        t.eq("save over2", es.get("over2"), mapOf("r", J(sr), "tree", normTree(tree(base))));
        final Map<String, Object> cfg3 = cfg2;
        t.eq("save escape", es.get("escape"), J(attempt(new Callable<Object>() { public Object call() {
            return BejsonParse.saveFiles("Proj", Arrays.asList(mapOf("name", "../../evil.txt", "content", "x")), cfg3); } })));
        t.eq("save escape_exists", es.get("escape_exists"), new File(out + "/evil.txt").exists());
        t.finish();
    }

    private static Object normTree(Object tr) {
        Map<String, Object> o = Json.obj();
        for (Map.Entry<String, Object> e : Json.asMap(tr).entrySet()) o.put(norm(e.getKey()), e.getValue());
        return o;
    }

    private static Map<String, Object> tab(List<String> fields, List<Object> rows) {
        Map<String, Object> m = Json.obj();
        List<Object> f = new ArrayList<Object>();
        for (String n : fields) { Map<String, Object> x = Json.obj(); x.put("name", n); x.put("type", "string"); f.add(x); }
        m.put("Fields", f); m.put("Values", rows);
        return m;
    }
}
