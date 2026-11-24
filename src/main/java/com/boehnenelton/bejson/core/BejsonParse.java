/*
 * File Name:       BejsonParse.java
 * Relative Path:   src/main/java/com/boehnenelton/bejson/core/BejsonParse.java
 * Description:     Port of lib_bejson_Core_bejson_parse.py: structured-parser helpers - tolerant JSON extraction,
 *                  project/file extraction from a BEJSON document, and traversal-safe saving of the files (with a
 *                  build report and update zip).
 * Deviation:       DEFAULT_OUT is resolved against the JVM working directory ("output" under user.dir) instead of the
 *                  Python module directory, because a Java library has no script path. Pass cfg "output_path" to override.
 * VERSION:         1.0.0
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   b3273d61-58f8-472b-ac9b-055a48c60e3e
 */
package com.boehnenelton.bejson.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class BejsonParse {
    private BejsonParse() { }

    private static final Logger LOG = Logger.getLogger("lib_bejson_Core_bejson_parse");

    /** Default output directory (see class Deviation note). */
    public static String defaultOut() {
        return PyFs.join(System.getProperty("user.dir", "."), "output");
    }

    /** parse_json: json.loads, or raw_decode from the first '{' when the text has surrounding prose. */
    public static Object parseJson(String text) {
        try {
            return Json.loads(text);
        } catch (Json.JsonParseException first) {
            int start = text.indexOf('{');
            if (start != -1) {
                try {
                    return Json.rawDecode(text, start)[0];
                } catch (Json.JsonParseException ignored) {
                    // fall through to the original error
                }
            }
            throw first;
        }
    }

    /** Result of extractData: project name plus the {name, content} file list. */
    public static final class Extracted {
        public final String projectName;
        public final List<Map<String, Object>> files;

        Extracted(String projectName, List<Map<String, Object>> files) {
            this.projectName = projectName;
            this.files = files;
        }
    }

    private static String getVal(List<Object> row, Map<String, Integer> fMap, String key) {
        Integer idx = fMap.get(key);
        if (idx != null && idx.intValue() < row.size()) {
            Object v = row.get(idx.intValue());
            if (v != null) return PyOps.strip(Json.pyStr(v));
        }
        return null;
    }

    /** extract_data. */
    public static Extracted extractData(Map<String, Object> data) {
        List<Object> fields = data.containsKey("Fields") ? Json.asList(data.get("Fields")) : new ArrayList<Object>();
        List<Object> values = data.containsKey("Values") ? Json.asList(data.get("Values")) : new ArrayList<Object>();
        if (!Json.truthy(values)) return new Extracted("My_Project", new ArrayList<Map<String, Object>>());

        Map<String, Integer> fMap = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < fields.size(); i++) {
            Map<String, Object> f = Json.asMap(fields.get(i));
            if (f == null || !f.containsKey("name")) throw new IllegalStateException("KeyError: 'name'");
            String name = PyOps.lower(Json.pyStr(f.get("name")));
            StringBuilder key = new StringBuilder();
            for (int k = 0; k < name.length(); ) {
                int cp = name.codePointAt(k);
                k += Character.charCount(cp);
                if (PyOps.isAlnumChar(cp)) key.appendCodePoint(cp);
            }
            fMap.put(key.toString(), Integer.valueOf(i));
        }

        String projectName = "My_Project";
        for (Object rowObj : values) {
            List<Object> row = Json.asList(rowObj);
            for (String key : new String[] {"projectname", "zipfilename", "containername"}) {
                String v = getVal(row, fMap, key);
                if (v != null && !v.isEmpty()) { projectName = v; break; }
            }
            if (!projectName.equals("My_Project")) break;
        }
        String invalid = "<>:\"/\\|?*";
        for (int i = 0; i < invalid.length(); i++) {
            projectName = projectName.replace(invalid.charAt(i), '_');
        }

        List<Map<String, Object>> files = new ArrayList<Map<String, Object>>();
        for (Object rowObj : values) {
            List<Object> row = Json.asList(rowObj);
            for (int i = 1; i <= 50; i++) {
                String fname = getVal(row, fMap, "file" + i + "name");
                String fcont = getVal(row, fMap, "file" + i + "content");
                if (fname != null && !fname.isEmpty() && fcont != null && !fcont.isEmpty()) {
                    Map<String, Object> f = new LinkedHashMap<String, Object>();
                    f.put("name", fname);
                    f.put("content", fcont);
                    files.add(f);
                }
            }
        }
        return new Extracted(projectName, files);
    }

    private static void atomicWriteText(String filePath, String content) {
        PyFs.atomicWriteBytes(filePath, content.getBytes(StandardCharsets.UTF_8), ".parse_", ".tmp");
    }

    private static Map<String, Object> result(boolean success, String message) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("success", Boolean.valueOf(success));
        m.put("message", message);
        return m;
    }

    /** save_files: writes the files (and report + update zip) under the configured output directory. */
    public static Map<String, Object> saveFiles(String proj, List<Map<String, Object>> files, Map<String, Object> cfg) {
        Object op = cfg.get("output_path");
        String baseDir = Json.truthy(op) ? Json.pyStr(op) : defaultOut();
        if (!PyFs.isDir(baseDir)) {
            try {
                PyFs.makedirs(baseDir);
            } catch (RuntimeException e) {
                return result(false, "Cannot create output dir: " + e.getMessage());
            }
        }
        boolean overwrite = Json.truthy(cfg.get("overwrite_enabled"));
        String target;
        if (overwrite) {
            target = PyFs.join(baseDir, proj);
            String bak = PyFs.join(baseDir, proj + "_BACKUP");
            if (PyFs.exists(target)) {
                if (PyFs.exists(bak)) PyFs.rmtree(bak);
                try {
                    PyFs.copytree(target, bak);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "[Parse] Backup warning: {0}", e.getMessage());
                }
            }
        } else {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
            target = PyFs.join(baseDir, ts + "_" + proj);
        }
        try {
            target = PyFs.abspath(target);
            PyFs.makedirs(target);
            for (Map<String, Object> f : files) {
                String fpath = BejsonPathGuard.safeJoin(target, Json.pyStr(f.get("name")));
                atomicWriteText(fpath, Json.pyStr(f.get("content")));
            }
            String tsNow = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
            String modeStr = overwrite ? "Merge/Update (overwrite)" : "Timestamped (new folder)";
            StringBuilder sb = new StringBuilder();
            String eq = rep('=', 52);
            String dash = rep('-', 52);
            sb.append(eq).append('\n');
            sb.append("  STRUCTURED PARSER — BUILD REPORT").append('\n');
            sb.append(eq).append('\n');
            sb.append("Project    : ").append(proj).append('\n');
            sb.append("Generated  : ").append(tsNow).append('\n');
            sb.append("Mode       : ").append(modeStr).append('\n');
            sb.append("Output Dir : ").append(target).append('\n');
            sb.append("Files      : ").append(files.size()).append('\n');
            sb.append(dash).append('\n');
            sb.append("FILE LIST").append('\n');
            sb.append(dash).append('\n');
            for (int idx = 0; idx < files.size(); idx++) {
                Map<String, Object> f = files.get(idx);
                int sizeB = Json.pyStr(f.get("content")).getBytes(StandardCharsets.UTF_8).length;
                String sizeS;
                if (sizeB >= 1024) {
                    double kb = new BigDecimal(sizeB / 1024.0).setScale(1, RoundingMode.HALF_EVEN).doubleValue();
                    sizeS = Json.floatRepr(kb) + " KB";
                } else {
                    sizeS = sizeB + " B";
                }
                String n = Integer.toString(idx + 1);
                if (n.length() < 2) n = "0" + n;
                sb.append("  [").append(n).append("] ").append(Json.pyStr(f.get("name"))).append("  (").append(sizeS).append(")\n");
            }
            sb.append(dash).append('\n');
            sb.append("Zip        : ").append(proj).append("_update.zip").append('\n');
            sb.append(eq).append('\n');
            String reportText = sb.toString();

            atomicWriteText(PyFs.join(target, "_REPORT.txt"), reportText);

            String zipPath = PyFs.join(target, proj + "_update.zip");
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath));
            try {
                for (Map<String, Object> f : files) {
                    zos.putNextEntry(new ZipEntry(Json.pyStr(f.get("name"))));
                    zos.write(Json.pyStr(f.get("content")).getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
                zos.putNextEntry(new ZipEntry("_REPORT.txt"));
                zos.write(reportText.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            } finally {
                zos.close();
            }
            Map<String, Object> ok = result(true, "Saved " + files.size() + " file(s)");
            ok.put("path", target);
            ok.put("file_count", Long.valueOf(files.size()));
            return ok;
        } catch (IOException e) {
            return result(false, String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
            return result(false, String.valueOf(e.getMessage()));
        }
    }

    private static String rep(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }
}
