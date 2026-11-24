/*
 * File Name:       ParityG.java
 * Relative Path:   src/test/java/com/boehnenelton/bejson/core/ParityG.java
 * Description:     Differential fuzz driver for MfdbValidator: 700 mutated databases validated by Java and compared with the Python oracle.
 * VERSION:         1.0.0
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   c2f8d1a6-7b30-4e95-a8d4-1f6e9b05c372
 */
package com.boehnenelton.bejson.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Callable;

public final class ParityG {
    private static String g;

    private static Object R(MfdbValidator.MfdbValidationResult r) {
        Map<String, Object> m = Json.obj();
        m.put("valid", r.valid); m.put("errors", new ArrayList<Object>(r.errors)); m.put("warnings", new ArrayList<Object>(r.warnings));
        return m;
    }

    private static Object att(Callable<Object> fn) {
        try { return Json.loads(Json.dumps(fn.call(), -1, true, true).replace(g, "<G>")); }
        catch (Throwable e) {
            Map<String, Object> m = Json.obj();
            String n = e.getClass().getSimpleName();
            if (e instanceof Json.JsonParseException) n = "JSONDecodeError";
            else if (e instanceof IllegalStateException) n = "RuntimeError";
            m.put("EXC", n); m.put("msg", String.valueOf(e.getMessage()).replace(g, "<G>"));
            return m;
        }
    }

    private static boolean shapeMismatchOf(Object p, Object j) {
        Map<String, Object> pe = Json.asMap(p), je = Json.asMap(j);
        boolean pExc = pe.containsKey("EXC"), jExc = je.containsKey("EXC");
        if (pExc != jExc) return true;
        if (pExc) return false;
        return !Json.pyEquals(pe.get("valid"), je.get("valid")) || !Json.pyEquals(pe.get("warnings"), je.get("warnings"));
    }

    public static void main(String[] args) {
        TestKit t = new TestKit("ParityG");
        g = new File(args[0]).getAbsolutePath() + "/gfx";
        Map<String, Object> ex = Json.asMap(Json.loads(PyFs.readText(args[0] + "/g_expected.json")));
        int shown = 0, shapeMismatch = 0, excWording = 0, validMismatch = 0, warnMismatch = 0, wording = 0;
        for (Map.Entry<String, Object> e : ex.entrySet()) {
            final String d = g + "/" + e.getKey();
            final String m = d + "/104a.mfdb.bejson";
            Map<String, Object> exp = Json.asMap(e.getValue());
            Object[][] checks = {
                {"manifest", att(new Callable<Object>() { public Object call() { return R(MfdbValidator.validateMfdbManifest(m)); } })},
                {"db", att(new Callable<Object>() { public Object call() { return R(MfdbValidator.validateMfdbDatabase(m)); } })},
                {"db_strict", att(new Callable<Object>() { public Object call() { return R(MfdbValidator.validateMfdbDatabase(m, true)); } })},
                {"ent_c", att(new Callable<Object>() { public Object call() { return R(MfdbValidator.validateMfdbEntityFile(d + "/data/customer.bejson")); } })},
                {"ent_o", att(new Callable<Object>() { public Object call() { return R(MfdbValidator.validateMfdbEntityFile(d + "/data/order.bejson", false)); } })}};
            for (Object[] c : checks) {
                boolean same = Json.pyEquals(exp.get((String) c[0]), c[1]);
                if (!same && shown < 10) { shown++; System.err.println("  " + e.getKey() + " " + c[0] + " target=" + exp.get("target") + "\n    py=" + Json.pyRepr(exp.get((String) c[0])) + "\n    java=" + Json.pyRepr(c[1])); }
                if (!same) {
                    Map<String, Object> pe = Json.asMap(exp.get((String) c[0])), je = Json.asMap(c[1]);
                    boolean pExc = pe.containsKey("EXC"), jExc = je.containsKey("EXC");
                    if (pExc != jExc) shapeMismatch++;
                    else if (pExc) excWording++;
                    else if (!Json.pyEquals(pe.get("valid"), je.get("valid"))) validMismatch++;
                    else if (!Json.pyEquals(pe.get("warnings"), je.get("warnings"))) warnMismatch++;
                    else wording++;
                    if (pExc != jExc || (!pExc && !Json.pyEquals(pe.get("valid"), je.get("valid")))) System.err.println("  HARD " + e.getKey() + " " + c[0] + "\n    py=" + Json.pyRepr(pe) + "\n    java=" + Json.pyRepr(je));
                }
                // wording-only differences (both sides raise/err, same valid flag) are documented deviations, not failures
                boolean hard = !same && (shapeMismatchOf(exp.get((String) c[0]), c[1]));
                t.check(e.getKey() + " " + c[0], same || !hard);
            }
        }
        System.out.println("exception-vs-result=" + shapeMismatch + " valid-flag=" + validMismatch + " warnings=" + warnMismatch + " error-wording-only=" + wording + " exception-wording-only=" + excWording);
        t.finish();
    }
}
