/*
 * File Name:       ParityC.java
 * Relative Path:   src/test/java/com/boehnenelton/bejson/core/ParityC.java
 * Description:     Parity driver C: nested-104 scan/mutate/query/cache vs. dev/parity/parity_py_c.py.
 * VERSION:         1.0.0
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   698eb237-924a-464b-b92d-612536f7a15e
 */
package com.boehnenelton.bejson.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class ParityC {
    private static Map<String, Object> cell(BejsonCoreNesting.NestedCell c) {
        Map<String, Object> m = Json.obj();
        m.put("row", c.row); m.put("col", c.col); m.put("field_name", c.fieldName); m.put("depth", c.depth);
        m.put("doc", c.doc); m.put("is_valid", c.isValid); m.put("errors", c.errors); m.put("warnings", c.warnings);
        List<Object> ch = new ArrayList<Object>();
        for (BejsonCoreNesting.NestedCell x : c.children) ch.add(cell(x));
        m.put("children", ch);
        return m;
    }

    private static Map<String, Object> res(BejsonCoreNesting.NestingResult r) {
        Map<String, Object> m = Json.obj();
        m.put("scanned", r.scannedCells); m.put("found", r.nestedFound); m.put("valid", r.nestedValid); m.put("invalid", r.nestedInvalid);
        m.put("depth", r.maxDepthSeen); m.put("schema_errors", r.schemaErrors);
        List<Object> cs = new ArrayList<Object>();
        for (BejsonCoreNesting.NestedCell c : r.cells) cs.add(cell(c));
        m.put("cells", cs); m.put("errors", r.errors); m.put("warnings", r.warnings);
        return m;
    }

    private static List<Object> qm(List<BejsonCoreNesting.QueryMatch> ms) {
        List<Object> out = new ArrayList<Object>();
        for (BejsonCoreNesting.QueryMatch m : ms) {
            Map<String, Object> o = Json.obj();
            o.put("path", m.path); o.put("value", m.value); o.put("fc", m.fieldChain); o.put("rc", m.rowChain); o.put("cc", m.colChain);
            out.add(o);
        }
        return out;
    }

    private static Map<String, Object> exc(RuntimeException e) {
        Map<String, Object> m = Json.obj();
        m.put("EXC", e instanceof IllegalArgumentException ? "ValueError" : e.getClass().getSimpleName());
        m.put("msg", e.getMessage());
        return m;
    }

    private static BejsonCoreNesting.Mutation mut(final String name) {
        return new BejsonCoreNesting.Mutation() {
            public void apply(Map<String, Object> d) {
                List<Object> f = Json.asList(d.get("Fields"));
                List<Object> v = Json.asList(d.get("Values"));
                if (name.equals("add_field")) { Map<String, Object> x = Json.obj(); x.put("name", "note"); x.put("type", "string"); f.add(x); }
                else if (name.equals("remove_field")) f.remove(f.size() - 1);
                else if (name.equals("edit_value")) Json.asList(v.get(0)).set(0, "Z");
                else if (name.equals("bad_creator")) d.put("Format_Creator", "Someone");
                else if (name.equals("add_row")) v.add(Json.arr("n", 9L));
                else if (name.equals("short_row")) v.add(Json.arr("n"));
                else if (name.equals("add_bad_type")) { Map<String, Object> x = Json.obj(); x.put("name", "q"); x.put("type", "bogus"); f.add(x); }
            }
        };
    }

    public static void main(String[] args) {
        TestKit t = new TestKit("ParityC");
        Map<String, Object> in = Json.asMap(Json.loads(PyFs.readText(args[0] + "/c_inputs.json")));
        Map<String, Object> ex = Json.asMap(Json.loads(PyFs.readText(args[0] + "/c_expected.json")));
        Map<String, Object> docs = Json.asMap(in.get("docs"));
        List<Object> queries = Json.asList(in.get("queries"));
        BejsonCoreNesting.cacheClear();
        Map<String, Object> exNo = Json.asMap(ex.get("scan_nocache"));
        for (Map.Entry<String, Object> e : docs.entrySet()) {
            Object got;
            try { got = res(BejsonCoreNesting.scan(Json.deepCopy(e.getValue()), false)); } catch (RuntimeException x) { got = exc(x); }
            t.eq("nocache " + e.getKey(), exNo.get(e.getKey()), got);
        }
        BejsonCoreNesting.cacheClear();
        Map<String, Object> exS = Json.asMap(ex.get("scan"));
        Map<String, Object> exSum = Json.asMap(ex.get("summary"));
        Map<String, Object> exFl = Json.asMap(ex.get("flatten"));
        Map<String, Object> exCol = Json.asMap(ex.get("column"));
        Map<String, Object> exFm = Json.asMap(ex.get("fieldmap"));
        List<Object> exStats = Json.asList(ex.get("cache_stats_sequence"));
        int si = 0;
        for (Map.Entry<String, Object> e : docs.entrySet()) {
            String k = e.getKey();
            BejsonCoreNesting.NestingResult r;
            try { r = BejsonCoreNesting.scan(Json.deepCopy(e.getValue())); } catch (RuntimeException x) { t.eq("scan " + k, exS.get(k), exc(x)); continue; }
            t.eq("scan " + k, exS.get(k), res(r));
            t.eq("summary " + k, exSum.get(k), BejsonCoreNesting.summary(r));
            List<Object> fl = new ArrayList<Object>();
            for (BejsonCoreNesting.NestedCell c : BejsonCoreNesting.flatten(r)) fl.add(Json.arr(c.row, c.col, c.depth));
            t.eq("flatten " + k, exFl.get(k), fl);
            List<Object> col = new ArrayList<Object>();
            for (BejsonCoreNesting.NestedCell c : BejsonCoreNesting.getColumn(r, 1)) col.add(Json.arr(c.row, c.col));
            t.eq("column " + k, exCol.get(k), col);
            if (!r.cells.isEmpty()) {
                Map<String, Object> fm = Json.obj();
                for (Map.Entry<String, Integer> x : BejsonCoreNesting.getFieldMap(r.cells.get(0)).entrySet()) fm.put(x.getKey(), x.getValue());
                t.eq("fieldmap " + k, exFm.get(k), fm);
            }
            Object es = Json.asList(exStats.get(si++)).get(1);
            t.eq("cache_stats " + k, es, BejsonCoreNesting.cacheStats());
        }
        t.eq("cache_clear", ex.get("cache_clear_returns"), BejsonCoreNesting.cacheClear());
        t.eq("cache_stats_after_clear", ex.get("cache_stats_after_clear"), BejsonCoreNesting.cacheStats());
        Map<String, Object> exQ = Json.asMap(ex.get("query"));
        Map<String, Object> exQs = Json.asMap(ex.get("qsummary"));
        Map<String, Object> exP = Json.asMap(ex.get("path"));
        for (String k : new String[] {"basic", "two_rows", "deep", "schema_mismatch", "not_json", "104db_outer", "empty"}) {
            for (int qi = 0; qi < queries.size(); qi++) {
                List<Object> q = Json.asList(queries.get(qi));
                String key = k + "|" + qi;
                Object got;
                List<BejsonCoreNesting.QueryMatch> ms = null;
                try { ms = BejsonCoreNesting.query(Json.deepCopy(docs.get(k)), (String) q.get(0), q.get(1)); got = qm(ms); } catch (RuntimeException x) { got = exc(x); }
                t.eq("query " + key + " " + q, exQ.get(key), got);
                if (ms != null) t.eq("qsummary " + key, exQs.get(key), BejsonCoreNesting.querySummary(ms));
                Object pg;
                try { pg = BejsonCoreNesting.getPath(Json.deepCopy(docs.get(k)), (String) q.get(0), "DEF"); } catch (RuntimeException x) { pg = exc(x); }
                t.eq("path " + key, exP.get(key), pg);
            }
        }
        Map<String, Object> exM = Json.asMap(ex.get("mutate"));
        for (String k : new String[] {"basic", "two_rows", "deep", "schema_mismatch"}) {
            for (Object mn : Json.asList(in.get("mutations"))) {
                Map<String, Object> d = Json.asMap(Json.deepCopy(docs.get(k)));
                BejsonCoreNesting.cacheClear();
                Object r;
                try {
                    BejsonCoreNesting.NestingResult sr = BejsonCoreNesting.scan(d, false);
                    if (sr.cells.isEmpty()) r = "NOCELLS";
                    else { BejsonCoreNesting.mutate(d, sr.cells.get(0), mut((String) mn)); r = "OK"; }
                } catch (RuntimeException x) { r = exc(x); }
                Map<String, Object> got = Json.obj(); got.put("r", r); got.put("doc", d);
                t.eq("mutate " + k + "|" + mn, exM.get(k + "|" + mn), got);
            }
        }
        t.finish();
    }
}
