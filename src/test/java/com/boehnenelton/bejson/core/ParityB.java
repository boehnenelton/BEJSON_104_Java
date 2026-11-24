/*
 * File Name:       ParityB.java
 * Relative Path:   src/test/java/com/boehnenelton/bejson/core/ParityB.java
 * Description:     Parity driver B: MFDB scenario (create/CRUD/join/sync/verify/heal/debug/federation) vs. dev/parity/parity_py_b.py.
 * VERSION:         1.0.0
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   6d01e35c-ce27-4537-80aa-43aea7059514
 */
package com.boehnenelton.bejson.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

public final class ParityB {
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern TS = Pattern.compile("\\d{8}T\\d{6}Z|\\d{4}-\\d\\d-\\d\\d[T ][0-9:.]+(?:Z|[+-]\\d\\d:\\d\\d)?");
    private static final Pattern DUR = Pattern.compile("\"(duration_ms|elapsed_ms|duration)\": [0-9.eE+-]+");
    private static final Pattern PID = Pattern.compile("\"(pid|timestamp)\": [0-9.]+");
    private static String root;
    private static TestKit t;
    private static Map<String, Object> exp;

    private static String norm(String text) {
        text = text.replace(root, "<ROOT>");
        text = UUID.matcher(text).replaceAll("<UUID>");
        text = TS.matcher(text).replaceAll("<TS>");
        text = DUR.matcher(text).replaceAll("\"$1\": 0");
        text = PID.matcher(text).replaceAll("\"$1\": 0");
        return text;
    }

    private static Map<String, Object> snap(String d) {
        Map<String, Object> r = new TreeMap<String, Object>();
        walk(new File(d), new File(d), r);
        return r;
    }

    private static void walk(File base, File f, Map<String, Object> out) {
        File[] kids = f.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) { walk(base, k, out); continue; }
            String rel = base.toURI().relativize(k.toURI()).getPath();
            if (k.getName().equals("lock_meta.json") || rel.contains(".lock")) continue;
            String txt = PyFs.readText(k.getPath());
            if (k.getName().startsWith("meta-")) {
                Map<String, Object> dd = Json.asMap(Json.loads(txt));
                Map<String, Integer> fm = new java.util.HashMap<String, Integer>();
                List<Object> fl = Json.asList(dd.get("Fields"));
                for (int i = 0; i < fl.size(); i++) fm.put((String) Json.asMap(fl.get(i)).get("name"), i);
                for (Object rw : Json.asList(dd.get("Values"))) {
                    for (String c : new String[] {"pid", "duration_ms"}) if (fm.containsKey(c)) Json.asList(rw).set(fm.get(c), 0L);
                }
                txt = Json.dumps(dd, 2, true);
            }
            out.put(norm(rel), norm(txt));
        }
    }

    private static void rec(String name, Object val, String dir) {
        Map<String, Object> e = Json.asMap(exp.get(name));
        if (e == null) { t.check("missing expected " + name, false); return; }
        Object got = Json.loads(norm(Json.dumps(val, -1, true, true)));
        t.eq(name + " result", e.get("r"), got);
        if (e.get("files") != null) {
            Object gf = dir == null ? null : Json.loads(Json.dumps(snap(dir), -1, true, true));
            Map<String, Object> ef = Json.asMap(e.get("files"));
            Map<String, Object> gm = gf == null ? null : Json.asMap(gf);
            if (gm == null) { t.check(name + " files (no dir)", false); return; }
            for (String k : ef.keySet()) {
                if (!Json.pyEquals(ef.get(k), gm.get(k)) && ef.get(k) instanceof String && gm.get(k) instanceof String) {
                    String[] a = ((String) ef.get(k)).split("\\n"), b = ((String) gm.get(k)).split("\\n");
                    for (int i = 0; i < Math.max(a.length, b.length); i++) {
                        String x = i < a.length ? a[i] : "<none>", y = i < b.length ? b[i] : "<none>";
                        if (!x.equals(y)) { System.err.println("  DIFF " + k + " line " + i + ": py=" + x + " | java=" + y); break; }
                    }
                }
                t.check(name + " file " + k, Json.pyEquals(ef.get(k), gm.get(k)));
            }
            for (String k : gm.keySet()) if (!ef.containsKey(k)) t.check(name + " unexpected file " + k, false);
        }
    }

    private static Object attempt(Callable<Object> fn) {
        try { return fn.call(); }
        catch (Exception e) {
            String n = e.getClass().getSimpleName();
            Object code = null;
            if (e instanceof BejsonCoreException) { n = "BEJSONCoreError"; code = ((BejsonCoreException) e).code; }
            else if (e instanceof MfdbCoreException) { n = "MFDBCoreError"; code = Long.valueOf(((MfdbCoreException) e).code); }
            Map<String, Object> m = Json.obj();
            m.put("EXC", n); m.put("msg", e.getMessage());
            m.put("code", code instanceof Integer ? (Object) Long.valueOf((Integer) code) : code);
            return m;
        }
    }

    private static List<Object> F(Object... nt) {
        List<Object> l = new ArrayList<Object>();
        for (int i = 0; i < nt.length; i += 2) { Map<String, Object> f = Json.obj(); f.put("name", nt[i]); f.put("type", nt[i + 1]); l.add(f); }
        return l;
    }

    private static List<Object> row(Object... v) { return new ArrayList<Object>(Arrays.asList(v)); }

    private static void write(String path, String text) {
        try { Files.write(new File(path).toPath(), text.getBytes(StandardCharsets.UTF_8)); } catch (java.io.IOException e) { throw new RuntimeException(e); }
    }

    private static Map<String, Object> entity(String name, List<Object> fields, String desc, String pk) {
        Map<String, Object> m = Json.obj();
        m.put("name", name); m.put("fields", fields);
        if (desc != null) m.put("description", desc);
        if (pk != null) m.put("primary_key", pk);
        return m;
    }

    private static List<Object> strip(List<Map<String, Object>> log) {
        List<Object> out = new ArrayList<Object>();
        for (Map<String, Object> e : log) {
            Map<String, Object> c = new java.util.LinkedHashMap<String, Object>(e);
            c.remove("timestamp"); c.remove("duration_ms");
            out.add(c);
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        t = new TestKit("ParityB");
        exp = Json.asMap(Json.loads(PyFs.readText(args[0] + "/b_expected.json")));
        File rootDir = TestKit.tempDir("parityB");
        root = rootDir.getPath();
        final String DB = root + "/db";
        List<Map<String, Object>> ents = new ArrayList<Map<String, Object>>();
        ents.add(entity("Customer", F("id", "integer", "name", "string", "email", "string"), "customers", "id"));
        ents.add(entity("Order", F("oid", "integer", "cust_id", "integer", "total", "number"), null, null));
        final String mp = MfdbCore.createDatabase(DB, "Shop", ents, "demo", "1.0.0", "Elton Boehnen", "1.31", "Master", false, 500, false);
        rec("create_database", PyFs.relpath(mp, root), DB);
        MfdbCore.addEntityRecord(mp, "Customer", row(1L, "Ann", "a@x"));
        MfdbCore.addEntityRecord(mp, "Customer", row(2L, "Bob", null));
        MfdbCore.addEntityRecord(mp, "Order", row(10L, 1L, 5.5));
        MfdbCore.addEntityRecord(mp, "Order", row(11L, 1L, 7L));
        MfdbCore.addEntityRecord(mp, "Order", row(12L, 3L, 1L));
        rec("populated", null, DB);
        rec("validate_db", Json.arr(MfdbValidator.validateDatabase(mp) ? "bool" : "false"), null);
        rec("discover", MfdbCore.discover(mp), null);
        rec("discover_entity", MfdbCore.discover(DB + "/data/customer.bejson"), null);
        rec("load_manifest", MfdbCore.loadManifest(mp), null);
        rec("load_entity", MfdbCore.loadEntity(mp, "Customer"), null);
        rec("entity_doc", MfdbCore.getEntityDoc(mp, "Order"), null);
        rec("stats", MfdbCore.getStats(mp), null);
        rec("query", MfdbCore.queryEntity(mp, "Order", new MfdbCore.RowPredicate() {
            public boolean test(Map<String, Object> r) { return Json.pyEquals(r.get("cust_id"), 1L); } }), null);
        Map<String, Object> idx = Json.obj();
        for (Map.Entry<Object, Map<String, Object>> e : MfdbCore.buildIndex(mp, "Customer", "id").entrySet()) idx.put(Json.pyStr(e.getKey()), e.getValue());
        rec("index", idx, null);
        rec("join", MfdbCore.join(mp, "Order", "Customer", "cust_id", "id"), null);
        rec("load_unknown", attempt(new Callable<Object>() { public Object call() { return MfdbCore.loadEntity(mp, "Nope"); } }), null);
        rec("add", MfdbCore.addEntityRecord(mp, "Customer", row(3L, "Cy", "c@x")), DB);
        rec("add_bad_len", attempt(new Callable<Object>() { public Object call() { return MfdbCore.addEntityRecord(mp, "Customer", row(4L)); } }), DB);
        rec("add_nosync", MfdbCore.addEntityRecord(mp, "Customer", row(4L, "Di", null), false), DB);
        rec("sync_one", MfdbCore.syncManifestCount(mp, "Customer"), DB);
        rec("update", MfdbCore.updateEntityRecord(mp, "Customer", 0, "name", "Anne"), DB);
        rec("update_nofield", attempt(new Callable<Object>() { public Object call() { return MfdbCore.updateEntityRecord(mp, "Customer", 0, "zzz", 1L); } }), DB);
        rec("update_badidx", attempt(new Callable<Object>() { public Object call() { return MfdbCore.updateEntityRecord(mp, "Customer", 99, "name", "x"); } }), DB);
        Map<String, Object> ups = Json.obj(); ups.put("name", "Rob"); ups.put("email", "r@x");
        rec("update_bulk", MfdbCore.updateEntityRecordBulk(mp, "Customer", 1, ups), DB);
        rec("remove", MfdbCore.removeEntityRecord(mp, "Customer", 2), DB);
        rec("remove_badidx", attempt(new Callable<Object>() { public Object call() { return MfdbCore.removeEntityRecord(mp, "Customer", 50); } }), DB);
        rec("create_entity", MfdbCore.createEntityFile(mp, "Product", F("sku", "string", "price", "number"), "prods", "sku", "1.0", ""), DB);
        rec("add_product", MfdbCore.addEntityRecord(mp, "Product", row("S1", 9.99)), DB);
        rec("sync_all", MfdbCore.syncAllCounts(mp), DB);
        rec("deep_verify_clean", MfdbCore.deepVerify(mp), null);
        Map<String, Object> doc = Json.asMap(Json.loads(PyFs.readText(mp)));
        for (Object r : Json.asList(doc.get("Values"))) { List<Object> rr = Json.asList(r); if ("Order".equals(rr.get(0))) rr.set(3, 99L); }
        write(mp, Json.dumps(doc, 2, true));
        rec("deep_verify_tampered", MfdbCore.deepVerify(mp), null);
        rec("self_heal", MfdbCore.selfHeal(mp), DB);
        rec("deep_verify_after", MfdbCore.deepVerify(mp), null);
        new File(DB + "/data/product.bejson").delete();
        rec("deep_verify_missing", MfdbCore.deepVerify(mp), null);
        rec("self_heal_missing", attempt(new Callable<Object>() { public Object call() { return MfdbCore.selfHeal(mp); } }), DB);
        rec("resolve_path", Json.arr(MfdbCore.resolvePath("data/x.bejson"), MfdbCore.resolvePath("/abs/x")), null);
        rec("slave", PyFs.relpath(MfdbCore.createConnectedSlaveEntity(mp), root), DB);
        String dbg = MfdbCore.enableDebug(mp, 5, true);
        rec("enable_debug", dbg.startsWith(root) ? PyFs.relpath(dbg, root) : "meta", DB);
        MfdbCore.loadEntity(mp, "Customer");
        MfdbCore.addEntityRecord(mp, "Customer", row(7L, "Eve", null));
        attempt(new Callable<Object>() { public Object call() { return MfdbCore.addEntityRecord(mp, "Customer", row(1L)); } });
        attempt(new Callable<Object>() { public Object call() { return MfdbCore.updateEntityRecord(mp, "Customer", 0, "zzz", 1L); } });
        rec("debug_log", strip(MfdbCore.getDebugLog(mp)), null);
        rec("failed_ops", strip(MfdbCore.getFailedOps(mp)), null);
        Map<String, Object> ds = MfdbCore.debugSummary(mp);
        ds.put("top_3_slowest", Long.valueOf(Json.asList(ds.get("top_3_slowest")).size())); // durations are non-deterministic
        rec("debug_summary", ds, null);
        rec("clear_log", MfdbCore.clearDebugLog(mp), DB);
        for (int i = 0; i < 8; i++) MfdbCore.addEntityRecord(mp, "Customer", row(Long.valueOf(100 + i), "n" + i, null));
        rec("rowcap", MfdbCore.getDebugLog(mp).size(), null);
        String cp = DB + "/data/customer.bejson";
        Map<String, Object> cd = Json.asMap(Json.loads(PyFs.readText(cp)));
        Json.asList(cd.get("Fields")).addAll(F("age", "integer"));
        for (Object r : Json.asList(cd.get("Values"))) Json.asList(r).add(null);
        write(cp, Json.dumps(cd, 2, true));
        rec("drift", MfdbCore.detectSchemaDrift(mp), null);
        MfdbCore.disableDebug(mp);
        rec("disable_debug", null, DB);
        rec("drift_after_disable", attempt(new Callable<Object>() { public Object call() { return MfdbCore.detectSchemaDrift(mp); } }), null);
        String disp = root + "/drop";
        new File(disp).mkdirs();
        Map<String, Object> cfg = Json.obj(); cfg.put("a", 1L); cfg.put("b", Json.arr(1L, 2L));
        rec("push_config", MfdbCore.federationPushConfig(cfg, disp + "/cfg.json"), disp);
        write(disp + "/m1.bejson", "{\"hello\": \"world\"}");
        write(disp + "/bad.bejson", "{");
        final List<Object> got = new ArrayList<Object>();
        rec("poll", MfdbCore.federationPollDropzone(disp, new MfdbCore.DropzoneCallback() {
            public void accept(String p, Object d) { got.add(Json.arr(PyFs.basename(p), d)); } }, 0.01, 0.05), disp);
        rec("poll_cb", got, null);
        String master = root + "/master";
        new File(master).mkdirs();
        rec("distill", MfdbCore.federationDistillLogs(mp, "data/customer.bejson", master, 3), master);
        rec("distill_nothing", MfdbCore.federationDistillLogs(mp, "data/customer.bejson", master, 500), null);
        rec("distill_after_entity", null, DB);
        PyFs.rmtree(root);
        t.finish();
    }
}
