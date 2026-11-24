/*
 * File Name:       BejsonEnv.java
 * Relative Path:   src/main/java/com/boehnenelton/bejson/core/BejsonEnv.java
 * Description:     Port of lib_bejson_Core_bejson_env.py 2.3.1: environment sourcing and path placeholder resolution.
 *                  The JVM cannot mutate the real process environment, so Python's os.environ writes go to a
 *                  process-wide overlay (setenv/getenv). An Android host can seed it with setenv("HOME", filesDir) and
 *                  setenv("BEJSON_STORAGE_ROOT", ...) before calling the library.
 * Deviation:       .py env files are read with a restricted literal parser (NAME = literal, os.environ["NAME"] = literal);
 *                  Python syntax errors elsewhere in such a file do not abort the load (Python's ast.parse would).
 * VERSION:         1.0.0 (reference 2.3.1)
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   2837ef0c-fcc7-4002-b768-64bccb3b2df7
 */
package com.boehnenelton.bejson.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BejsonEnv {
    private BejsonEnv() { }

    public static final String VERSION = "2.3.1";
    private static final Logger LOG = Logger.getLogger("lib_bejson_Core_bejson_env");
    private static final Map<String, String> OVERLAY = new ConcurrentHashMap<String, String>();

    // Canonical environment-store locations (policy section 7 / reference parity). Kept as data, not logic.
    private static final String STORE_ROOT = "/storage/emulated/0";

    /** os.environ.get(name): overlay first, then the real environment; null when unset. */
    public static String getenv(String name) {
        String v = OVERLAY.get(name);
        if (v != null) return v;
        try {
            return System.getenv(name);
        } catch (SecurityException e) {
            return null;
        }
    }

    /** os.environ.get(name, default). */
    public static String getenv(String name, String dflt) {
        String v = getenv(name);
        return v == null ? dflt : v;
    }

    /** os.environ[name] = value (process-wide overlay). */
    public static void setenv(String name, String value) {
        OVERLAY.put(name, value);
    }

    /** Removes an overlay value (real environment is untouched). */
    public static void unsetenv(String name) {
        OVERLAY.remove(name);
    }

    /** Clears the overlay and the source_env_fill report cache (test support). */
    public static void resetForTests() {
        OVERLAY.clear();
        synchronized (FILL_LOCK) { FILL_REPORTS.clear(); }
    }

    private static String home() {
        String h = getenv("HOME");
        if (h != null) return h;
        return System.getProperty("user.home", "");
    }

    // ------------------------------------------------------------ literal .py env files

    /** Applies top-level NAME = literal and os.environ["NAME"] = literal lines; returns the applied count. */
    static int applyLiteralPyEnv(String source) {
        int applied = 0;
        for (String raw : source.split("\r\n|\r|\n", -1)) {
            if (raw.isEmpty() || raw.charAt(0) == ' ' || raw.charAt(0) == '\t' || raw.charAt(0) == '#') continue;
            String line = raw;
            String name = null;
            int pos = 0;
            if (line.startsWith("os.environ[")) {
                int q = 11;
                if (q < line.length() && (line.charAt(q) == '"' || line.charAt(q) == '\'')) {
                    char quote = line.charAt(q);
                    int end = line.indexOf(quote, q + 1);
                    if (end > 0 && end + 1 < line.length() && line.charAt(end + 1) == ']') {
                        name = line.substring(q + 1, end);
                        pos = end + 2;
                    }
                }
            } else {
                int i = 0;
                if (i < line.length() && (Character.isLetter(line.charAt(i)) || line.charAt(i) == '_')) {
                    while (i < line.length() && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '_')) i++;
                    name = line.substring(0, i);
                    pos = i;
                }
            }
            if (name == null || name.isEmpty()) continue;
            while (pos < line.length() && (line.charAt(pos) == ' ' || line.charAt(pos) == '\t')) pos++;
            if (pos >= line.length() || line.charAt(pos) != '=' || (pos + 1 < line.length() && line.charAt(pos + 1) == '=')) continue;
            pos++;
            String rhs = line.substring(pos).trim();
            String val = parseLiteral(rhs);
            if (val == null) continue;
            String stripped = PyOps.strip(val);
            if (!stripped.isEmpty()) {
                setenv(name, stripped);
                applied++;
            }
        }
        return applied;
    }

    /** Parses one str/int/float/bool literal (optionally followed by a comment); null when it is anything else. */
    private static String parseLiteral(String rhs) {
        if (rhs.isEmpty()) return null;
        char c = rhs.charAt(0);
        if (c == '"' || c == '\'') {
            StringBuilder sb = new StringBuilder();
            int i = 1;
            while (i < rhs.length()) {
                char ch = rhs.charAt(i);
                if (ch == '\\' && i + 1 < rhs.length()) {
                    char e = rhs.charAt(i + 1);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case '\\': sb.append('\\'); break;
                        case '\'': sb.append('\''); break;
                        case '"': sb.append('"'); break;
                        default: sb.append('\\').append(e);
                    }
                    i += 2;
                    continue;
                }
                if (ch == c) {
                    String rest = rhs.substring(i + 1).trim();
                    if (rest.isEmpty() || rest.charAt(0) == '#' || rest.charAt(0) == ';') return sb.toString();
                    return null;
                }
                sb.append(ch);
                i++;
            }
            return null;
        }
        String tok = rhs;
        int hash = tok.indexOf('#');
        if (hash >= 0) tok = tok.substring(0, hash).trim();
        if (tok.equals("True") || tok.equals("False")) return tok;
        try {
            if (tok.matches("[-+]?\\d+(_\\d+)*")) return Json.pyStr(Json.loads(tok.replace("_", "").replace("+", "")));
            if (tok.matches("[-+]?(\\d+\\.\\d*|\\.\\d+|\\d+)([eE][-+]?\\d+)?")) {
                return Json.floatRepr(Double.parseDouble(tok));
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private static boolean loadEnvFile(String p) {
        try {
            if (p.endsWith(".json")) {
                Object data = Json.loads(PyFs.readText(p));
                Map<String, Object> m = Json.asMap(data);
                if (m == null) return false;
                Object vals = m.containsKey("Values") ? m.get("Values") : new ArrayList<Object>();
                List<Object> rows = Json.asList(vals);
                if (rows == null) return false;
                for (Object row : rows) {
                    List<Object> r = Json.asList(row);
                    if (r != null && r.size() >= 2) {
                        String name = PyOps.strip(Json.pyStr(r.get(0)));
                        String val = PyOps.strip(Json.pyStr(r.get(1)));
                        if (!name.isEmpty() && !val.isEmpty()) setenv(name, val);
                    }
                }
            } else {
                applyLiteralPyEnv(PyFs.readText(p));
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Mandatory environment sourcing; returns true if any env file was applied. */
    public static boolean sourceEnv(String overridePath) {
        String envPath = (overridePath != null && !overridePath.isEmpty()) ? overridePath : getenv("ENV_FILE_PATH");
        if (envPath != null && !envPath.isEmpty()) {
            if (PyFs.exists(envPath)) return loadEnvFile(envPath);
        }
        String[] secure = {STORE_ROOT + "/.env/secure/secureenv_file.py", STORE_ROOT + "/.env/secure/secureenv_file.json"};
        String[] user = {STORE_ROOT + "/.env/user/paths.py", STORE_ROOT + "/.env/user/paths.json"};
        boolean loadedAny = false;
        for (String p : secure) {
            if (PyFs.exists(p) && loadEnvFile(p)) { loadedAny = true; break; }
        }
        for (String p : user) {
            if (PyFs.exists(p) && loadEnvFile(p)) { loadedAny = true; break; }
        }
        if (loadedAny) return true;
        String[] fallback = {STORE_ROOT + "/env_file.py", STORE_ROOT + "/env_file.json", home() + "/env_file.py"};
        for (String p : fallback) {
            if (PyFs.exists(p) && loadEnvFile(p)) return true;
        }
        return false;
    }

    public static boolean sourceEnv() { return sourceEnv(null); }

    // ------------------------------------------------------------ path resolution

    /** Replaces placeholders and known absolute Android paths with the configured roots; normalizes the result. */
    public static String resolvePath(String pathStr) {
        if (pathStr == null || pathStr.isEmpty()) return pathStr;
        String home = home();
        String storageRoot = getenv("BEJSON_STORAGE_ROOT", home);
        String adminRoot = getenv("ADMIN_ROOT", PyFs.join(storageRoot, "Admin"));
        String libRoot = getenv("BEJSON_LIB_ROOT");
        if (libRoot == null || libRoot.isEmpty()) {
            String candAdmin = PyFs.join(adminRoot, "libraries");
            String candHome = PyFs.join(home, "libraries");
            libRoot = PyFs.exists(candAdmin) ? candAdmin : candHome;
        }
        List<String[]> mappings = new ArrayList<String[]>();
        mappings.add(new String[] {"{BEJSON_LIB_ROOT}", libRoot});
        mappings.add(new String[] {"{ADMIN_ROOT}", adminRoot});
        mappings.add(new String[] {"{INTERNAL_STORAGE}", storageRoot});
        mappings.add(new String[] {"{HOME}", home});
        String sr = getenv("BEJSON_STORAGE_ROOT");
        if (sr != null && !sr.isEmpty()) {
            mappings.add(new String[] {STORE_ROOT, storageRoot});
            mappings.add(new String[] {"/data/data/com.termux/files/home", home});
        }
        // sorted by key length descending, stable
        Collections.sort(mappings, new java.util.Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return Integer.compare(b[0].length(), a[0].length());
            }
        });
        String resolved = pathStr;
        for (String[] m : mappings) {
            if (m[1] != null && !m[1].isEmpty()) resolved = resolved.replace(m[0], m[1]);
        }
        resolved = PyFs.expanduser(resolved);
        resolved = PyFs.expandvars(resolved);
        return PyFs.normpath(resolved);
    }

    /** Reads an environment variable (or the default) and resolves it through resolvePath. */
    public static String getEnvPath(String envVar, String dflt) {
        return resolvePath(getenv(envVar, dflt));
    }

    // ------------------------------------------------------------ source_env_fill

    private static final Object FILL_LOCK = new Object();
    private static final Map<Boolean, Map<String, Object>> FILL_REPORTS = new java.util.HashMap<Boolean, Map<String, Object>>();
    private static final long FILL_MAX_BYTES = 2L * 1024 * 1024;

    private static Map<String, String> fillPairs(String p) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        try {
            java.io.File f = new java.io.File(p);
            if (!f.isFile() || f.length() > FILL_MAX_BYTES) return out;
            String text = PyFs.readText(p);
            if (p.endsWith(".json")) {
                Object data = Json.loads(text);
                Map<String, Object> m = Json.asMap(data);
                if (m != null && m.containsKey("Values")) {
                    List<Object> rows = Json.asList(m.get("Values"));
                    if (rows == null) return new LinkedHashMap<String, String>();
                    for (Object row : rows) {
                        List<Object> r = Json.asList(row);
                        if (r != null && r.size() >= 2) {
                            String k = PyOps.strip(Json.pyStr(r.get(0)));
                            String v = PyOps.strip(Json.pyStr(r.get(1)));
                            if (!k.isEmpty() && !v.isEmpty() && r.get(1) != null) out.put(k, v);
                        }
                    }
                } else if (m != null) {
                    for (Map.Entry<String, Object> e : m.entrySet()) {
                        Object v = e.getValue();
                        if ((v instanceof String || Json.isNumber(v)) && !PyOps.strip(Json.pyStr(v)).isEmpty()) {
                            out.put(PyOps.strip(e.getKey()), PyOps.strip(Json.pyStr(v)));
                        }
                    }
                }
            } else if (!p.endsWith(".py")) {
                for (String line0 : text.split("\r\n|\r|\n", -1)) {
                    String line = PyOps.strip(line0);
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.toLowerCase(java.util.Locale.ROOT).startsWith("export ")) line = PyOps.strip(line.substring(7));
                    int eq = line.indexOf('=');
                    if (eq >= 0) {
                        String k = PyOps.strip(line.substring(0, eq));
                        String v = PyOps.strip(line.substring(eq + 1));
                        if (!k.isEmpty() && !PyOps.strip(v, "\"'").isEmpty()) out.put(k, PyOps.strip(v, "\"'"));
                    }
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "source_env_fill: could not read {0}: {1}",
                    new Object[] {PyFs.basename(p), e.getClass().getSimpleName()});
            return new LinkedHashMap<String, String>();
        }
        Map<String, String> filtered = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : out.entrySet()) {
            String k = e.getKey();
            if (!k.isEmpty() && PyOps.isAlnum(k.replace("_", "a")) && !Character.isDigit(k.charAt(0))) {
                filtered.put(k, e.getValue());
            }
        }
        return filtered;
    }

    /** Non-overwriting env fill from the secure store; returns {found, files, loaded, kept_existing, names}. */
    public static Map<String, Object> sourceEnvFill(boolean includePaths, boolean force) {
        Boolean cacheKey = Boolean.valueOf(includePaths);
        synchronized (FILL_LOCK) {
            if (FILL_REPORTS.containsKey(cacheKey) && !force) {
                return new LinkedHashMap<String, Object>(FILL_REPORTS.get(cacheKey));
            }
        }
        String h = PyFs.expanduser("~");
        String[] secure = {getenv("SECUREENV_FILE"), STORE_ROOT + "/.env/secure/secureenv_file.json",
            h + "/.env/secure/secureenv_file.json"};
        String[] paths = {getenv("BEJSON_PATHS_FILE"), STORE_ROOT + "/.env/user/paths.json",
            h + "/.env/user/paths.json"};
        List<String[]> tiers = new ArrayList<String[]>();
        tiers.add(secure);
        if (includePaths) tiers.add(paths);

        long loaded = 0;
        long kept = 0;
        boolean found = false;
        List<Object> files = new ArrayList<Object>();
        TreeSet<String> names = new TreeSet<String>();
        for (String[] tier : tiers) {
            for (String cand : tier) {
                if (cand == null || cand.isEmpty()) continue;
                String p = PyFs.expanduser(cand);
                if (!PyFs.isFile(p)) continue;
                Map<String, String> pairs = fillPairs(p);
                found = true;
                files.add(PyFs.basename(p));
                for (Map.Entry<String, String> e : pairs.entrySet()) {
                    String cur = getenv(e.getKey(), "");
                    if (!PyOps.strip(cur).isEmpty()) {
                        kept++;
                    } else {
                        setenv(e.getKey(), e.getValue());
                        loaded++;
                    }
                    names.add(e.getKey());
                }
                break;
            }
        }
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("found", Boolean.valueOf(found));
        report.put("files", files);
        report.put("loaded", Long.valueOf(loaded));
        report.put("kept_existing", Long.valueOf(kept));
        report.put("names", new ArrayList<Object>(names));
        LOG.log(Level.INFO, "source_env_fill: found={0} loaded={1} kept_existing={2}",
                new Object[] {found, loaded, kept});
        synchronized (FILL_LOCK) { FILL_REPORTS.put(cacheKey, report); }
        return new LinkedHashMap<String, Object>(report);
    }
}
