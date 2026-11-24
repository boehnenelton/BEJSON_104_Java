/*
 * File Name:       ParityE.java
 * Relative Path:   src/test/java/com/boehnenelton/bejson/core/ParityE.java
 * Description:     Parity driver E: path guard and env path resolution vs. dev/parity/parity_py_e.py.
 * VERSION:         1.0.0
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   8c08e8e6-8a7f-42bb-a6d6-86dba5ae2bf0
 */
package com.boehnenelton.bejson.core;

import java.util.List;
import java.util.Map;

public final class ParityE {
    private static Object att(java.util.concurrent.Callable<Object> fn) {
        try { return fn.call(); }
        catch (Exception e) {
            Map<String, Object> m = Json.obj();
            String n = e.getClass().getSimpleName();
            if (e instanceof BejsonCoreException || e instanceof BejsonOSError) n = "BEJSONCoreError";
            else if (e instanceof IllegalArgumentException) n = "ValueError";
            m.put("EXC", n); m.put("msg", e.getMessage());
            return m;
        }
    }

    public static void main(String[] args) {
        TestKit t = new TestKit("ParityE");
        Map<String, Object> in = Json.asMap(Json.loads(PyFs.readText(args[0] + "/e_inputs.json")));
        Map<String, Object> ex = Json.asMap(Json.loads(PyFs.readText(args[0] + "/e_expected.json")));
        BejsonEnv.setenv("HOME", "/home/tester");
        BejsonEnv.setenv("PG_TEST_VAR", "/opt/pg");
        BejsonEnv.unsetenv("PG_UNSET_VAR");
        List<Object> joins = Json.asList(in.get("joins"));
        for (int i = 0; i < joins.size(); i++) {
            final List<Object> j = Json.asList(joins.get(i));
            final String[] rest = new String[j.size() - 1];
            for (int k = 1; k < j.size(); k++) rest[k - 1] = (String) j.get(k);
            t.eq("join " + i + " " + j, Json.asList(ex.get("join")).get(i), att(new java.util.concurrent.Callable<Object>() {
                public Object call() { return BejsonPathGuard.safeJoin((String) j.get(0), rest); } }));
        }
        List<Object> paths = Json.asList(in.get("paths"));
        for (int i = 0; i < paths.size(); i++) {
            final String p = (String) paths.get(i);
            t.eq("storage " + i + " [" + p + "]", Json.asList(ex.get("storage")).get(i), att(new java.util.concurrent.Callable<Object>() { public Object call() { return BejsonPathGuard.resolveStoragePath(p); } }));
            t.eq("resolve " + i + " [" + p + "]", Json.asList(ex.get("resolve")).get(i), att(new java.util.concurrent.Callable<Object>() { public Object call() { return BejsonEnv.resolvePath(p); } }));
        }
        String[][] ev = {{"PG_TEST_VAR", "/d"}, {"PG_UNSET_VAR", "/d"}, {"PG_UNSET_VAR", "~/q"}, {"PG_UNSET_VAR", "$PG_TEST_VAR/q"}};
        for (int i = 0; i < ev.length; i++) t.eq("envpath " + i, Json.asList(ex.get("envpath")).get(i), BejsonEnv.getEnvPath(ev[i][0], ev[i][1]));
        List<Object> esc = Json.asList(in.get("esc"));
        for (int i = 0; i < esc.size(); i++) t.eq("esc " + i + " [" + esc.get(i) + "]", Json.asList(ex.get("esc")).get(i), Boolean.valueOf(BejsonPathGuard.mfdbEscapesRoot((String) esc.get(i))));
        t.finish();
    }
}
