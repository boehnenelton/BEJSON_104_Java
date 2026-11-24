/*
 * File Name:       ParityA.java
 * Relative Path:   src/test/java/com/boehnenelton/bejson/core/ParityA.java
 * Description:     Parity driver A: validator, core ops, atomic write, schema vs. the Python oracle (dev/parity/parity_py_a.py).
 * VERSION:         1.0.0
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   5d62050b-6ec8-4314-8625-586414c4da67
 */
package com.boehnenelton.bejson.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class ParityA {
    private static Map<String, Object> resMap(BejsonValidator.ValidationResult r) {
        Map<String, Object> m = Json.obj();
        m.put("valid", Boolean.valueOf(r.valid));
        m.put("errors", new ArrayList<Object>(r.errors));
        m.put("warnings", new ArrayList<Object>(r.warnings));
        return m;
    }

    private static List<Object> fields(Object... nt) {
        List<Object> l = new ArrayList<Object>();
        for (int i = 0; i < nt.length; i += 2) { Map<String, Object> f = Json.obj(); f.put("name", nt[i]); f.put("type", nt[i + 1]); l.add(f); }
        return l;
    }

    private static List<Object> row(Object... v) { return new ArrayList<Object>(Arrays.asList(v)); }

    private static Object exc(Runnable r) {
        try { r.run(); return "NO ERROR"; }
        catch (BejsonValidationException e) { return Json.arr("EXC", Long.valueOf(e.code), e.getMessage()); }
    }

    public static void main(String[] args) {
        final String dir = args[0];
        TestKit t = new TestKit("ParityA");
        Map<String, Object> in = Json.asMap(Json.loads(PyFs.readText(dir + "/a_inputs.json")));
        Map<String, Object> ex = Json.asMap(Json.loads(PyFs.readText(dir + "/a_expected.json")));

        Map<String, Object> strs = Json.asMap(in.get("strings"));
        Map<String, Object> exs = Json.asMap(ex.get("string"));
        for (Map.Entry<String, Object> e : strs.entrySet())
            t.eq("string:" + e.getKey(), exs.get(e.getKey()), resMap(BejsonValidator.validateBejson(e.getValue())));
        Map<String, Object> dicts = Json.asMap(in.get("dicts"));
        Map<String, Object> exd = Json.asMap(ex.get("dict"));
        for (Map.Entry<String, Object> e : dicts.entrySet())
            t.eq("dict:" + e.getKey(), exd.get(e.getKey()), resMap(BejsonValidator.validateBejson(Json.deepCopy(e.getValue()))));

        Map<String, Object> rep = Json.asMap(ex.get("report"));
        t.eq("report ok", rep.get("ok"), BejsonValidator.getReport(strs.get("ok104"), false));
        t.eq("report bad", rep.get("bad"), BejsonValidator.getReport(strs.get("row_short"), false));
        Map<String, Object> vs = Json.asMap(ex.get("validate_string"));
        for (final String k : new String[] {"ok104", "row_short", "str_invalid"}) {
            final String s = (String) strs.get(k);
            Object got;
            try { got = Boolean.valueOf(BejsonValidator.validateString(s)); }
            catch (BejsonValidationException e) { got = Json.arr("EXC", Long.valueOf(e.code), e.getMessage()); }
            t.eq("validate_string " + k, vs.get(k), got);
        }

        // ---- core
        Map<String, Object> c = Json.asMap(ex.get("core"));
        Map<String, Object> d = BejsonCore.create104("T", fields("id", "integer", "name", "string"), Json.asList(Json.arr(row(1L, "a"), row(2L, "b"))));
        t.eq("create104", c.get("create104"), d);
        t.eq("create104a", c.get("create104a"), BejsonCore.create104a("T", fields("id", "integer"), Json.asList(Json.arr(row(1L))), customHeaders()));
        Map<String, Object> dbIn = Json.asMap(dicts.get("ok104db"));
        t.eq("create104db", c.get("create104db"), BejsonCore.create104db(Json.asList(Json.arr("A", "B")), Json.asList(dbIn.get("Fields")), Json.asList(dbIn.get("Values"))));
        t.eq("field_map", c.get("field_map"), new java.util.LinkedHashMap<String, Object>(toObj(BejsonCore.getFieldMap(d))));
        t.eq("field_index", c.get("field_index"), Json.arr(Long.valueOf(BejsonCore.getFieldIndex(d, "id")), Long.valueOf(BejsonCore.getFieldIndex(d, "name")), Long.valueOf(BejsonCore.getFieldIndex(d, "zzz"))));
        t.eq("fresh_after_get", c.get("fresh_after_get"), Boolean.valueOf(BejsonCore.fieldMapIsFresh(d.get("_bejson_field_map"), d.get("Fields"))));
        Map<String, Object> d2 = Json.asMap(Json.deepCopy(d));
        d2.remove("_bejson_field_map");
        d2.put("_bejson_field_map", plainMap("id", 0L, "name", 1L));
        t.eq("forged_rebuilt", c.get("forged_rebuilt"), toObj(BejsonCore.getFieldMap(d2)));
        t.check("forged_is_engine_map", d2.get("_bejson_field_map") instanceof BejsonCore.FieldMap);
        Map<String, Object> d3 = BejsonCore.create104("T", fields("a", "string", "b", "string", "c", "string"), new ArrayList<Object>());
        BejsonCore.getFieldMap(d3);
        Json.asList(d3.get("Fields")).addAll(fields("d", "string"));
        t.eq("stale_after_append", c.get("stale_after_append"), toObj(BejsonCore.getFieldMap(d3)));
        Map<String, Object> emp = Json.obj(); emp.put("Fields", new ArrayList<Object>());
        t.eq("empty_fields_map", c.get("empty_fields_map"), toObj(BejsonCore.getFieldMap(emp)));
        t.eq("add_ok", c.get("add_ok"), Boolean.valueOf(BejsonCore.addRecord(d, row(3L, "c"))));
        t.eq("add_bad", c.get("add_bad"), Boolean.valueOf(BejsonCore.addRecord(d, row(3L))));
        t.eq("count", c.get("count"), Long.valueOf(BejsonCore.getRecordCount(d)));
        t.eq("update_ok", c.get("update_ok"), Boolean.valueOf(BejsonCore.updateField(d, 0, "name", "A")));
        t.eq("update_nofield", c.get("update_nofield"), Boolean.valueOf(BejsonCore.updateField(d, 0, "zz", 1L)));
        t.eq("update_badrow", c.get("update_badrow"), Boolean.valueOf(BejsonCore.updateField(d, 9, "name", 1L)));
        t.eq("filter", c.get("filter"), BejsonCore.filterRows(d, "name", "b"));
        t.eq("filter_nofield", c.get("filter_nofield"), BejsonCore.filterRows(d, "q", 1L));
        Map<String, Object> dd = BejsonCore.create104("T", fields("v", "integer"), Json.asList(Json.arr(row(1L), row(Boolean.TRUE), row(1.0), row(2L))));
        t.eq("filter_pyeq", c.get("filter_pyeq"), BejsonCore.filterRows(dd, "v", 1L));
        t.eq("remove_ok", c.get("remove_ok"), Boolean.valueOf(BejsonCore.removeRecord(d, 0)));
        t.eq("remove_bad", c.get("remove_bad"), Boolean.valueOf(BejsonCore.removeRecord(d, 10)));
        Map<String, Object> ds = BejsonCore.create104("T", fields("n", "string", "k", "integer"),
            Json.asList(Json.arr(row("b", 2L), row(null, 1L), row("a", 2L), row("c", 1L))));
        BejsonCore.sortByField(ds, "k");
        t.eq("sort_k", c.get("sort_k"), Json.deepCopy(ds.get("Values")));
        BejsonCore.sortByField(ds, "k", true);
        t.eq("sort_k_rev", c.get("sort_k_rev"), Json.deepCopy(ds.get("Values")));
        BejsonCore.sortByField(ds, "n");
        t.eq("sort_n", c.get("sort_n"), Json.deepCopy(ds.get("Values")));
        BejsonCore.sortByField(ds, "nope");
        Map<String, Object> bad = Json.obj(); bad.put("Format", 1L);
        t.eq("is_valid", c.get("is_valid"), Json.arr(Boolean.valueOf(BejsonCore.isValid(Json.asMap(dicts.get("ok104")))), Boolean.valueOf(BejsonCore.isValid(bad))));
        t.eq("version", c.get("version"), Json.arr(BejsonCore.getVersion(Json.asMap(dicts.get("ok104a"))), BejsonCore.getVersion(Json.obj())));
        t.eq("stats", c.get("stats"), BejsonCore.getStats(Json.asMap(dicts.get("ok104"))));

        // ---- files
        File td = TestKit.tempDir("parityA");
        String p = td.getPath() + "/sub/x.bejson";
        Map<String, Object> doc = Json.asMap(Json.deepCopy(dicts.get("ok104")));
        Json.asList(doc.get("Values")).add(row(4L, "é😀\u007f", 0.1, Boolean.TRUE));
        BejsonCore.getFieldMap(doc);
        doc.put("_other", 5L);
        t.eq("atomic_write_ok", ex.get("atomic_write_ok"), Boolean.valueOf(BejsonCore.atomicWrite(p, doc)));
        t.eq("atomic_write_bytes", ex.get("atomic_write_bytes"), PyFs.readText(p));
        t.eq("atomic_write_listing", ex.get("atomic_write_listing"), Json.asList(PyFs.listdir(new File(p).getParent())));
        Map<String, Object> unser = Json.obj(); unser.put("a", new java.util.HashSet<Object>(Arrays.asList(1, 2)));
        t.eq("atomic_write_unserializable", ex.get("atomic_write_unserializable"), Boolean.valueOf(safeWrite(td.getPath() + "/y.bejson", unser)));
        t.eq("atomic_write_leftovers", ex.get("atomic_write_leftovers"), Json.asList(PyFs.listdir(td.getPath())));
        String w = td.getPath() + "/cache.bejson";
        Map<String, Object> wd = Json.asMap(Json.deepCopy(dicts.get("ok104"))); wd.put("_bejson_field_map", plainMap("id", 0L));
        writeRaw(w, Json.dumps(wd));
        Map<String, Object> ld = BejsonCore.loadFile(w);
        t.check("load_strips_cache", ld != null && !ld.containsKey("_bejson_field_map"));
        t.check("load_missing", BejsonCore.loadFile(td.getPath() + "/nope.json") == null);
        writeRaw(td.getPath() + "/bad.json", "{");
        t.check("load_bad", BejsonCore.loadFile(td.getPath() + "/bad.json") == null);
        Map<String, Object> one = Json.obj(); one.put("a", 1L);
        t.eq("load_string_ok", Json.asList(ex.get("load_string")).get(0), BejsonCore.loadString("{\"a\":1}"));
        t.check("load_string_bad", BejsonCore.loadString("{") == null);
        t.eq("validate_file_ok", ex.get("validate_file_ok"), Boolean.valueOf(BejsonValidator.validateBejson(p, true).valid));
        BejsonValidator.ValidationResult mr = BejsonValidator.validateBejson(td.getPath() + "/none.json", true);
        Map<String, Object> exm = Json.asMap(ex.get("validate_file_missing"));
        t.eq("validate_file_missing valid", exm.get("valid"), Boolean.valueOf(mr.valid));
        t.eq("validate_file_missing msg", Json.asList(exm.get("errors")).get(0).toString().replaceAll("^File not found: .*/", "File not found: "),
            mr.errors.get(0).replaceAll("^File not found: .*/", "File not found: "));

        // ---- schema
        Map<String, Object> sc = Json.asMap(Json.asMap(ex.get("schema")));
        Map<String, Object> o4a = Json.asMap(dicts.get("ok104a"));
        Map<String, Object> o104 = Json.asMap(dicts.get("ok104"));
        t.eq("schema extract", sc.get("extract"), BejsonSchema.extract(o4a));
        t.eq("schema ok", sc.get("validate_ok"), Boolean.valueOf(BejsonSchema.validateAgainst(o104, Json.asList(o104.get("Fields")))));
        t.eq("schema bad", sc.get("validate_bad"), Boolean.valueOf(BejsonSchema.validateAgainst(o104, fields("id", "integer"))));
        t.eq("schema type", sc.get("validate_type"), Boolean.valueOf(BejsonSchema.validateAgainst(o104, fields("id", "string", "name", "string", "score", "number", "ok", "boolean"))));
        Map<String, Object> consts = Json.asMap(sc.get("consts"));
        t.eq("const LIST_MANAGER", consts.get("SCHEMA_LIST_MANAGER_v100"), BejsonSchema.SCHEMA_LIST_MANAGER_v100);
        t.eq("const MFDB_ENTITY", consts.get("SCHEMA_MFDB_ENTITY_v5"), BejsonSchema.SCHEMA_MFDB_ENTITY_v5);
        t.eq("const MFDB_MANIFEST", consts.get("SCHEMA_MFDB_MANIFEST_v5"), BejsonSchema.SCHEMA_MFDB_MANIFEST_v5);
        t.eq("const MODEL_REGISTRY", consts.get("SCHEMA_MODEL_REGISTRY_GEMINI"), BejsonSchema.SCHEMA_MODEL_REGISTRY_GEMINI);
        t.eq("const PROJECT", consts.get("SCHEMA_PROJECT_v140"), BejsonSchema.SCHEMA_PROJECT_v140);
        PyFs.rmtree(td.getPath());
        t.finish();
    }

    private static void writeRaw(String path, String text) {
        try { java.nio.file.Files.write(new File(path).toPath(), text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
    }

    private static boolean safeWrite(String path, Map<String, Object> m) {
        try { return BejsonCore.atomicWrite(path, m); } catch (RuntimeException e) { return false; }
    }

    private static Map<String, Object> customHeaders() {
        Map<String, Object> m = Json.obj(); m.put("Custom_Key", "v"); m.put("Other", 2L); return m;
    }

    private static Map<String, Object> plainMap(Object... kv) {
        Map<String, Object> m = Json.obj();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> toObj(Map<String, Integer> fm) {
        Map<String, Object> m = Json.obj();
        for (Map.Entry<String, Integer> e : fm.entrySet()) m.put(e.getKey(), Long.valueOf(e.getValue()));
        return m;
    }
}
