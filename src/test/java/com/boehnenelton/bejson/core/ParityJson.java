/*
 * File Name:       ParityJson.java
 * Relative Path:   src/test/java/com/boehnenelton/bejson/core/ParityJson.java
 * Description:     Compares the Java Json layer with the frozen Python reference output (dev/parity/parity_py_json.py).
 * VERSION:         1.0.0
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   8efdcd1d-9420-4839-8608-2af1c34370b4
 */
package com.boehnenelton.bejson.core;

import java.util.List;
import java.util.Map;

public final class ParityJson {
    public static void main(String[] args) {
        String dir = args[0];
        TestKit t = new TestKit("ParityJson");
        Map<String, Object> in = Json.asMap(Json.loads(PyFs.readText(dir + "/json_inputs.json")));
        Map<String, Object> ex = Json.asMap(Json.loads(PyFs.readText(dir + "/json_expected.json")));
        List<Object> valid = Json.asList(in.get("valid"));
        List<Object> exv = Json.asList(ex.get("valid"));
        for (int i = 0; i < valid.size(); i++) {
            String txt = (String) valid.get(i);
            Map<String, Object> e = Json.asMap(exv.get(i));
            Object v = Json.loads(txt);
            t.eq("v" + i + " indent2", e.get("indent2"), Json.dumps(v, 2, true));
            t.eq("v" + i + " compact", e.get("compact"), Json.dumps(v));
            t.eq("v" + i + " noascii", e.get("noascii"), Json.dumps(v, 2, false));
            t.eq("v" + i + " sorted", e.get("sorted"), Json.dumps(v, -1, true, true));
            t.eq("v" + i + " str", e.get("str"), Json.pyStr(v));
            t.eq("v" + i + " repr", e.get("repr"), Json.pyRepr(v));
        }
        List<Object> invalid = Json.asList(in.get("invalid"));
        List<Object> exi = Json.asList(ex.get("invalid"));
        for (int i = 0; i < invalid.size(); i++) {
            String msg;
            try { Json.loads((String) invalid.get(i)); msg = "NO ERROR"; } catch (Json.JsonParseException e) { msg = e.getMessage(); }
            t.eq("i" + i + " [" + invalid.get(i).toString().replace("\n", "\\n") + "]", exi.get(i), msg);
        }
        t.finish();
    }
}
