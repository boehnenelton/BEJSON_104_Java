/*
 * File Name:       ParityF.java
 * Relative Path:   src/test/java/com/boehnenelton/bejson/core/ParityF.java
 * Description:     Differential fuzz driver: 4000 mutated documents / raw texts through Json.loads and BejsonValidator vs. the Python oracle.
 * VERSION:         1.0.0
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   a4d27e8b-6f13-4c90-8e5a-93b1c70d2f68
 */
package com.boehnenelton.bejson.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ParityF {
    public static void main(String[] args) {
        TestKit t = new TestKit("ParityF");
        List<Object> in = Json.asList(Json.loads(PyFs.readText(args[0] + "/f_inputs.json")));
        List<Object> ex = Json.asList(Json.loads(PyFs.readText(args[0] + "/f_expected.json")));
        int shown = 0, validityMismatch = 0;
        java.util.Map<String, Integer> hist = new java.util.TreeMap<String, Integer>();
        for (int i = 0; i < in.size(); i++) {
            String text = (String) in.get(i);
            Map<String, Object> e = Json.asMap(ex.get(i));
            Map<String, Object> ej = Json.asMap(e.get("json"));
            boolean ok; String msg = null;
            try { Json.loads(text); ok = true; }
            catch (Json.JsonParseException x) { ok = false; msg = x.getMessage(); }
            catch (Json.JsonRecursionError x) { ok = false; msg = x.getMessage(); }
            boolean jsonSame = ok == Boolean.TRUE.equals(ej.get("ok")) && (ok || String.valueOf(ej.get("msg")).equals(msg));
            BejsonValidator.ValidationResult r;
            try { r = BejsonValidator.validateBejson(text); }
            catch (Throwable x) { t.check("case " + i + " validator threw " + x, false); continue; }
            boolean valSame = Boolean.TRUE.equals(e.get("valid")) == r.valid
                && Json.pyEquals(e.get("errors"), new ArrayList<Object>(r.errors))
                && Json.pyEquals(e.get("warnings"), new ArrayList<Object>(r.warnings));
            if (!jsonSame && shown < 8) { shown++; System.err.println("  JSON py=" + ej + " java=" + ok + "/" + msg + " text=" + Json.pyRepr(text.length() > 120 ? text.substring(0, 120) : text)); }
            if (!valSame && shown < 8) { shown++; System.err.println("  VAL py=" + e.get("errors") + "/" + e.get("warnings") + " java=" + r.errors + "/" + r.warnings + " text=" + Json.pyRepr(text.length() > 160 ? text.substring(0, 160) : text)); }
            if (Boolean.TRUE.equals(e.get("valid")) != r.valid) { validityMismatch++; if (validityMismatch <= 6) System.err.println("  VALIDITY MISMATCH case " + i + " py=" + e.get("errors") + " java=" + r.errors + " text=" + Json.pyRepr(text.length() > 400 ? text.substring(0, 400) : text)); }
            if (!valSame) { String k = String.valueOf(e.get("errors")) + e.get("warnings") + "  =>  " + r.errors + r.warnings; Integer c = hist.get(k); hist.put(k, c == null ? 1 : c + 1); }
            t.check("case " + i + " json", jsonSame);
            t.check("case " + i + " validate", valSame);
        }
        for (Map.Entry<String, Integer> h : hist.entrySet()) System.out.println("HIST " + h.getValue() + "x " + h.getKey());
        System.out.println("validity mismatches: " + validityMismatch);
        t.finish();
    }
}
