/*
 * File Name:       BejsonChunking.java
 * Relative Path:   src/main/java/com/boehnenelton/bejson/core/BejsonChunking.java
 * Description:     Port of lib_bejson_Core_bejson_chunking.py 1.9.5: Chunked-104a project packaging (directory &lt;-&gt; one
 *                  104a document), MFDB-132 package create/validate/unpack, and the MFDB multi-schema detection +
 *                  unchunk dispatch layer. Session mount/commit/resurrect/unmount live in Mfdb132Archive.
 * Format_Scope:    Chunked-104a and MFDB-132 packaging (NOT 104db)
 * Deviations:      (1) Directory walk order is deterministic (files of a directory sorted, then sub-directories sorted);
 *                  Python uses filesystem order. (2) CHUNKED_104_FIELDS is handed out as a fresh copy per document
 *                  (Python aliases one module-level list). (3) Non-string field names are coerced with Python str().
 * VERSION:         1.0.0 (reference 1.9.5)
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   f9de5ace-c494-41f7-b8f3-909f2563e85d
 */
package com.boehnenelton.bejson.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TimeZone;
import java.util.logging.Logger;

public final class BejsonChunking {
    private BejsonChunking() { }

    public static final String VERSION = "1.9.5";
    private static final Logger LOG = Logger.getLogger("lib_bejson_Core_bejson_chunking");

    // ── Defaults (must match CLI_Chunker.py exactly) ─────────────────────────
    public static final List<String> DEFAULT_EXTENSIONS = Collections.unmodifiableList(Arrays.asList(
            ".py", ".js", ".ts", ".html", ".css", ".md", ".json", ".sh", ".txt", ".bejson", ".tsx", ".jsx"));
    public static final List<String> DEFAULT_EXCLUDES = Collections.unmodifiableList(Arrays.asList(
            ".git", "__pycache__", "node_modules", "lib", "output", ".mfdb_lock", "dist", "build"));

    public static final String MFDB_MANIFEST_FILENAME = "104a.mfdb.bejson";
    /** Fixed packaging-format identifier of the MFDB-132 spec itself; never bumped. */
    public static final String MFDB_CHUNK_SCHEMA_VERSION = "1.32";
    public static final String LOCK_FILE_132 = ".mfdb132_lock";

    public static final String MFDB_SCHEMA_MANIFEST = "mfdb_manifest";
    public static final String MFDB_SCHEMA_ENTITY = "mfdb_entity";
    public static final String MFDB_SCHEMA_ENTITY_LEGACY = "mfdb_entity_legacy";
    public static final String MFDB_SCHEMA_CHUNKED_104A = "chunked_104a";
    public static final String MFDB_SCHEMA_UNKNOWN = "unknown";

    private static final String[][] CHUNKED_104_FIELD_DEFS = {
        {"File_Name", "string"}, {"File_Extension", "string"}, {"File_Content", "string"},
        {"File_Version", "string"}, {"File_Hash", "string"}, {"Relative_Path", "string"},
        {"Is_Binary", "boolean"}, {"Is_Mounted", "boolean"},
    };

    /** A fresh copy of the Chunked-104a Fields list (8 fields). */
    public static List<Object> chunked104Fields() {
        List<Object> fields = new ArrayList<Object>();
        for (String[] d : CHUNKED_104_FIELD_DEFS) {
            Map<String, Object> f = new LinkedHashMap<String, Object>();
            f.put("name", d[0]);
            f.put("type", d[1]);
            fields.add(f);
        }
        return fields;
    }

    private static final Set<String> MFDB_ENTITY_FIELD_NAMES = new HashSet<String>(Arrays.asList(
            "version", "File_Name", "File_Extension", "Relative_Path",
            "File_Content", "File_Hash", "Is_Binary", "Is_Mounted"));
    private static final Set<String> MFDB_ENTITY_LEGACY_FIELD_NAMES = new HashSet<String>(Arrays.asList(
            "version", "file_path", "file_name", "content", "is_binary", "is_base64"));
    private static final Set<String> CHUNKED_104A_FIELD_NAMES = new HashSet<String>();

    static {
        for (String[] d : CHUNKED_104_FIELD_DEFS) CHUNKED_104A_FIELD_NAMES.add(d[0]);
    }

    // ── Python-compat helpers (package-private, shared with Mfdb132Archive) ──

    /** time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()). */
    public static String getTimestamp() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    /** str(Path(p)): collapses empty and "." segments, keeps ".."; "//" kept only when exactly two leading slashes. */
    static String pathStr(String p) {
        int lead = 0;
        while (lead < p.length() && p.charAt(lead) == '/') lead++;
        StringBuilder sb = new StringBuilder();
        if (lead == 2) sb.append("//"); else if (lead > 0) sb.append('/');
        boolean first = true;
        for (String part : p.split("/", -1)) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (!first) sb.append('/');
            sb.append(part);
            first = false;
        }
        String r = sb.toString();
        return r.isEmpty() ? "." : r;
    }

    /** str(Path(p).absolute()): cwd-prefixed, no ".." resolution. */
    static String pathAbsolute(String p) {
        String n = pathStr(p);
        if (n.startsWith("/")) return n;
        String cwd = System.getProperty("user.dir", ".");
        return pathStr(cwd + "/" + n);
    }

    /** str(Path(a) / b): an absolute b replaces a. */
    static String pathJoin(String a, String b) {
        if (b.startsWith("/")) return pathStr(b);
        return pathStr(a + "/" + b);
    }

    /** pathlib.PurePath.suffix. */
    static String suffixOf(String name) {
        int i = name.lastIndexOf('.');
        if (i > 0 && i < name.length() - 1) return name.substring(i);
        return "";
    }

    /** UTF-8 strict encode (Python str.encode / write_text raise on lone surrogates). */
    static byte[] encodeUtf8Strict(String s) {
        try {
            CharsetEncoder enc = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer bb = enc.encode(CharBuffer.wrap(s));
            byte[] out = new byte[bb.remaining()];
            bb.get(out);
            return out;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("'utf-8' codec can't encode character: surrogates not allowed", e);
        }
    }

    /** base64.b64decode(str) with binascii non-strict semantics (invalid characters are discarded). */
    static byte[] b64decode(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7f) throw new IllegalArgumentException("string argument should contain only ASCII characters");
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int leftchar = 0;
        int quadPos = 0;
        int pads = 0;
        boolean done = false;
        for (int i = 0; i < s.length() && !done; i++) {
            char c = s.charAt(i);
            if (c == '=') {
                if (quadPos >= 2 && quadPos + (++pads) >= 4) { done = true; }
                continue;
            }
            int v = b64Value(c);
            if (v < 0) continue;
            pads = 0;
            switch (quadPos) {
                case 0: quadPos = 1; leftchar = v; break;
                case 1: quadPos = 2; out.write((leftchar << 2) | (v >> 4)); leftchar = v & 0x0f; break;
                case 2: quadPos = 3; out.write((leftchar << 4) | (v >> 2)); leftchar = v & 0x03; break;
                default: quadPos = 0; out.write((leftchar << 6) | v); leftchar = 0; break;
            }
        }
        if (!done && quadPos != 0) {
            if (quadPos == 1) {
                throw new IllegalArgumentException("Invalid base64-encoded string: number of data characters cannot be 1 more than a multiple of 4");
            }
            throw new IllegalArgumentException("Incorrect padding");
        }
        return out.toByteArray();
    }

    private static int b64Value(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '+') return 62;
        if (c == '/') return 63;
        return -1;
    }

    /** Python bool(x) for a JSON value. */
    private static boolean truthy(Object o) { return Json.truthy(o); }

    private static int fieldIndex(Map<String, Integer> fm, String name) {
        Integer i = fm.get(name);
        if (i == null) throw new NoSuchElementException("KeyError: '" + name + "'");
        return i.intValue();
    }

    /** {f["name"]: i} over a Fields list (KeyError if an entry has no name). */
    private static Map<String, Integer> nameMap(List<Object> fields) {
        Map<String, Integer> fm = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < fields.size(); i++) {
            Map<String, Object> f = Json.asMap(fields.get(i));
            if (f == null || !f.containsKey("name")) throw new NoSuchElementException("KeyError: 'name'");
            Object n = f.get("name");
            fm.put(n instanceof String ? (String) n : Json.pyStr(n), Integer.valueOf(i));
        }
        return fm;
    }

    private static List<Object> fieldsOrDefault(Map<String, Object> doc) {
        if (!doc.containsKey("Fields")) return chunked104Fields();
        List<Object> f = Json.asList(doc.get("Fields"));
        if (f == null) throw new IllegalArgumentException("'" + PyOps.typeName(doc.get("Fields")) + "' object is not iterable");
        return f;
    }

    private static List<Object> row(Object o) {
        List<Object> r = Json.asList(o);
        if (r == null) throw new IllegalArgumentException("row is not a list");
        return r;
    }

    private static String asStr(Object o, String what) {
        if (o instanceof String) return (String) o;
        throw new IllegalArgumentException(what + " must be str, not " + PyOps.typeName(o));
    }

    private static void writeFileContent(String target, boolean binary, Object content) {
        String parent = PyFs.dirname(target);
        PyFs.makedirs(parent);
        if (binary) {
            PyFs.writeBytes(target, b64decode(asStr(content, "File_Content")));
        } else {
            PyFs.writeBytes(target, encodeUtf8Strict(asStr(content, "File_Content")));
        }
    }

    // ── Public primitives ────────────────────────────────────────────────────

    /** True if the file should be stored base64 (same rule as the CLI chunker: first 1024 chars must decode as UTF-8). */
    public static boolean isBinary(String filePath) {
        final int chunk = 8192;
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        InputStream in = null;
        try {
            in = new FileInputStream(filePath);
            byte[] buf = new byte[chunk];
            ByteBuffer carry = ByteBuffer.allocate(chunk + 8);
            CharBuffer out = CharBuffer.allocate(chunk + 8);
            int chars = 0;
            while (true) {
                int r = in.read(buf);
                boolean eof = r <= 0;
                if (!eof) carry.put(buf, 0, r);
                carry.flip();
                out.clear();
                CoderResult cr = dec.decode(carry, out, eof);
                if (cr.isError()) return true;
                if (eof) {
                    cr = dec.flush(out);
                    if (cr.isError()) return true;
                    if (carry.hasRemaining()) return true;
                }
                out.flip();
                chars += out.remaining();
                carry.compact();
                if (chars >= 1024 || eof) return false;
            }
        } catch (IOException e) {
            return true;
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) { /* ignore */ }
            }
        }
    }

    /** SHA-256 hex digest of raw bytes (the File_Hash value). */
    public static String hashFileBytes(byte[] rawBytes) {
        return PyFs.sha256Hex(rawBytes);
    }

    /** Next Package_Version string from a prior chunk document ("1" when absent / non-numeric). */
    public static String bumpPackageVersion(Map<String, Object> priorDoc) {
        if (priorDoc == null || priorDoc.isEmpty()) return "1";
        Object pv = priorDoc.containsKey("Package_Version") ? priorDoc.get("Package_Version") : "0";
        BigInteger n;
        try {
            n = pyInt(pv);
        } catch (IllegalArgumentException e) {
            return "1";
        }
        return n.add(BigInteger.ONE).toString();
    }

    /** int(x) for a JSON value; IllegalArgumentException for the TypeError/ValueError cases, ArithmeticException for inf. */
    private static BigInteger pyInt(Object v) {
        if (v instanceof Boolean) return ((Boolean) v).booleanValue() ? BigInteger.ONE : BigInteger.ZERO;
        if (v instanceof BigInteger) return (BigInteger) v;
        if (v instanceof Long || v instanceof Integer) return BigInteger.valueOf(((Number) v).longValue());
        if (v instanceof Double) {
            double d = (Double) v;
            if (Double.isNaN(d)) throw new IllegalArgumentException("cannot convert float NaN to integer");
            if (Double.isInfinite(d)) throw new ArithmeticException("cannot convert float infinity to integer");
            return new BigDecimal(d).toBigInteger();
        }
        if (v instanceof String) {
            String s = PyOps.strip((String) v);
            if (s.isEmpty()) throw new IllegalArgumentException("invalid literal for int()");
            int i = 0;
            boolean neg = false;
            if (s.charAt(0) == '+' || s.charAt(0) == '-') { neg = s.charAt(0) == '-'; i = 1; }
            if (i >= s.length()) throw new IllegalArgumentException("invalid literal for int()");
            StringBuilder digits = new StringBuilder();
            boolean prevUnderscore = true;
            for (; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '_') {
                    if (prevUnderscore) throw new IllegalArgumentException("invalid literal for int()");
                    prevUnderscore = true;
                    continue;
                }
                int d = Character.digit(c, 10);
                if (d < 0) throw new IllegalArgumentException("invalid literal for int()");
                digits.append((char) ('0' + d));
                prevUnderscore = false;
            }
            if (prevUnderscore) throw new IllegalArgumentException("invalid literal for int()");
            BigInteger b = new BigInteger(digits.toString());
            return neg ? b.negate() : b;
        }
        throw new IllegalArgumentException("int() argument must be a string or a real number");
    }

    // ── Chunked-104a create / unchunk ────────────────────────────────────────

    /** Packs a directory into one Chunked-104a document (one row per file). */
    public static Map<String, Object> createChunked104(String targetDir, String version, List<String> extensions,
                                                       List<String> excludeDirs, String packageVersion) {
        String target = PyFs.realpath(targetDir);
        List<String> exts = extensions != null ? extensions : DEFAULT_EXTENSIONS;
        List<String> excl = excludeDirs != null ? excludeDirs : DEFAULT_EXCLUDES;
        List<Object> values = new ArrayList<Object>();
        walk(new File(target), target, version, exts, excl, values);

        Map<String, Object> doc = new LinkedHashMap<String, Object>();
        doc.put("Format", "BEJSON");
        doc.put("Format_Version", "104a");
        doc.put("Format_Creator", "Elton Boehnen");
        doc.put("Schema_Name", "Chunked-104a");
        doc.put("Schema_Version", "1.0.1");
        doc.put("Schema_Description", "Standard schema for chunking single projects.");
        doc.put("Chunk_Date", getTimestamp().substring(0, 10));
        doc.put("Session_Is_Mounted", Boolean.FALSE);
        doc.put("Mount_Path", "");
        doc.put("Package_Version", (packageVersion != null && !packageVersion.isEmpty()) ? packageVersion : "1");
        doc.put("Records_Type", Json.arr("Chunked"));
        doc.put("Fields", chunked104Fields());
        doc.put("Values", values);
        return doc;
    }

    public static Map<String, Object> createChunked104(String targetDir) {
        return createChunked104(targetDir, "latest", null, null, null);
    }

    public static Map<String, Object> createChunked104(String targetDir, String version) {
        return createChunked104(targetDir, version, null, null, null);
    }

    private static void walk(File dir, String root, String version, List<String> exts, List<String> excl,
                             List<Object> values) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        List<File> files = new ArrayList<File>();
        List<File> dirs = new ArrayList<File>();
        for (File k : kids) {
            if (Files.isSymbolicLink(k.toPath())) continue; // links may point outside the chunked tree
            if (k.isDirectory()) dirs.add(k); else files.add(k);
        }
        Comparator1 byName = new Comparator1();
        Collections.sort(files, byName);
        Collections.sort(dirs, byName);
        for (File f : files) {
            String name = f.getName();
            String suffix = suffixOf(name);
            if (!exts.contains(PyOps.lower(suffix))) continue;
            try {
                String path = f.getPath();
                String rel = path.substring(root.length());
                while (rel.startsWith("/")) rel = rel.substring(1);
                boolean isBin = isBinary(path);
                byte[] raw = PyFs.readBytes(path);
                String content;
                if (isBin) content = java.util.Base64.getEncoder().encodeToString(raw);
                else content = PyFs.decodeUtf8Strict(raw, path);
                List<Object> r = new ArrayList<Object>(8);
                r.add(name);
                r.add(suffix);
                r.add(content);
                r.add(version);
                r.add(hashFileBytes(raw));
                r.add(rel);
                r.add(Boolean.valueOf(isBin));
                r.add(Boolean.FALSE);
                values.add(r);
            } catch (RuntimeException e) {
                continue;
            }
        }
        for (File d : dirs) {
            if (excl.contains(d.getName())) continue;
            if (Files.isSymbolicLink(d.toPath())) continue;
            walk(d, root, version, exts, excl, values);
        }
    }

    private static final class Comparator1 implements java.util.Comparator<File> {
        @Override
        public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
    }

    /** Restores the files of a Chunked-104a document to outputDir; returns the number of files written. */
    public static int unchunkChunked104(Map<String, Object> doc, String outputDir) {
        Map<String, Integer> fm = nameMap(fieldsOrDefault(doc));
        String outRoot = PyFs.realpath(outputDir);
        int count = 0;
        List<Object> values = Json.asList(doc.containsKey("Values") ? doc.get("Values") : new ArrayList<Object>());
        if (values == null) throw new IllegalArgumentException("'Values' is not iterable");
        for (Object rowObj : values) {
            List<Object> r = row(rowObj);
            Object relPath = r.get(fieldIndex(fm, "Relative_Path"));
            Object isBinary = r.get(fieldIndex(fm, "Is_Binary"));
            Object content = r.get(fieldIndex(fm, "File_Content"));
            if (!truthy(relPath) || content == null) continue;
            String target = BejsonPathGuard.safeJoin(outRoot, asStr(relPath, "Relative_Path"));
            writeFileContent(target, truthy(isBinary), content);
            count++;
        }
        return count;
    }

    // ── MFDB-132 packaging ───────────────────────────────────────────────────

    /** Chunks an MFDB directory (manifest + entity files) into one MFDB-132 Chunked-104a document. */
    public static Map<String, Object> createMfdb132Package(String mfdbRootDir, String dbName, List<String> extensions,
                                                           List<String> excludeDirs, String packageVersion,
                                                           Map<String, Object> priorPackageDoc) {
        String root = PyFs.realpath(mfdbRootDir);
        if (!PyFs.isFile(root + "/" + MFDB_MANIFEST_FILENAME)) {
            throw new IllegalArgumentException("No " + MFDB_MANIFEST_FILENAME + " found at root of " + root
                    + " — cannot package a directory that isn't a valid MFDB layout.");
        }
        String resolved = (packageVersion != null && !packageVersion.isEmpty())
                ? packageVersion : bumpPackageVersion(priorPackageDoc);
        Map<String, Object> doc = createChunked104(root, MFDB_CHUNK_SCHEMA_VERSION,
                extensions != null ? extensions : DEFAULT_EXTENSIONS, excludeDirs, resolved);
        doc.put("Schema_Name", "MFDB-132");
        doc.put("Records_Type", Json.arr("MFDB-132"));
        doc.put("MFDB_Version", MFDB_CHUNK_SCHEMA_VERSION);
        doc.put("DB_Name", dbName);
        doc.put("Package_Format", "MFDB-Chunked-104a");
        return doc;
    }

    public static Map<String, Object> createMfdb132Package(String mfdbRootDir, String dbName) {
        return createMfdb132Package(mfdbRootDir, dbName, null, null, null, null);
    }

    /** Thin wrapper over MfdbValidator.isMfdb132Package. */
    public static boolean isMfdb132Package(Map<String, Object> doc) {
        return MfdbValidator.isMfdb132Package(doc);
    }

    /** Thin wrapper over MfdbValidator.validateMfdb132Package. */
    public static Map<String, Object> validateMfdb132Package(Map<String, Object> doc) {
        return MfdbValidator.validateMfdb132Package(doc);
    }

    /** Result of unchunkMfdb132Package: (files_written, validation dict). */
    public static final class UnchunkResult {
        public final int count;
        public final Map<String, Object> validation;

        UnchunkResult(int count, Map<String, Object> validation) {
            this.count = count;
            this.validation = validation;
        }
    }

    /** Restores an MFDB-132 package to a standard MFDB directory and reports package validation. */
    @SuppressWarnings("unchecked")
    public static UnchunkResult unchunkMfdb132Package(Map<String, Object> doc, String outputDir) {
        Map<String, Object> validation = validateMfdb132Package(doc);
        int count = unchunkChunked104(doc, outputDir);
        String outRoot = PyFs.realpath(outputDir);
        boolean manifestRestored = PyFs.isFile(outRoot + "/" + MFDB_MANIFEST_FILENAME);
        if (!manifestRestored) {
            validation.put("valid", Boolean.FALSE);
            Object errs = validation.get("errors");
            if (!validation.containsKey("errors")) {
                errs = new ArrayList<Object>();
                validation.put("errors", errs);
            }
            ((List<Object>) errs).add("Manifest " + MFDB_MANIFEST_FILENAME + " was not found on disk after unchunking.");
        }
        return new UnchunkResult(count, validation);
    }

    /** Thin wrapper over MfdbValidator.detectMfdbInChunk. */
    public static Map<String, Object> detectMfdbInChunk(Map<String, Object> doc) {
        return MfdbValidator.detectMfdbInChunk(doc);
    }

    // ── MFDB rolling multi-version schema (detection + dispatch) ─────────────

    private static final Map<List<String>, Map<String, Integer>> MFDB_FIELD_MAP_CACHE =
            Collections.synchronizedMap(new java.util.HashMap<List<String>, Map<String, Integer>>());

    /** Field-name -> index map, cached per distinct Fields signature. */
    public static Map<String, Integer> mfdbGetFieldMap(Map<String, Object> doc) {
        List<Object> fields = doc.containsKey("Fields") ? Json.asList(doc.get("Fields")) : new ArrayList<Object>();
        if (fields == null) throw new IllegalArgumentException("'" + PyOps.typeName(doc.get("Fields")) + "' object is not iterable");
        Map<String, Integer> fm = nameMap(fields);
        List<String> key = new ArrayList<String>(fm.keySet());
        // keys with duplicate names collapse in fm; keep the tuple faithful to Fields
        key.clear();
        for (Object f : fields) {
            Object n = Json.asMap(f).get("name");
            key.add(n instanceof String ? (String) n : Json.pyStr(n));
        }
        Map<String, Integer> cached = MFDB_FIELD_MAP_CACHE.get(key);
        if (cached == null) {
            cached = fm;
            MFDB_FIELD_MAP_CACHE.put(key, cached);
        }
        return cached;
    }

    private static Set<String> fieldNameSet(Map<String, Object> doc) {
        List<Object> fields = doc.containsKey("Fields") ? Json.asList(doc.get("Fields")) : new ArrayList<Object>();
        if (fields == null) throw new IllegalArgumentException("'" + PyOps.typeName(doc.get("Fields")) + "' object is not iterable");
        Set<String> names = new HashSet<String>();
        for (Object f : fields) {
            Map<String, Object> m = Json.asMap(f);
            if (m == null || !m.containsKey("name")) throw new NoSuchElementException("KeyError: 'name'");
            Object n = m.get("name");
            names.add(n instanceof String ? (String) n : Json.pyStr(n));
        }
        return names;
    }

    /** Structural detection across the known chunk document shapes. */
    public static String mfdbDetectSchema(Map<String, Object> doc) {
        Object recordsType = doc.get("Records_Type");
        Set<String> names = fieldNameSet(doc);
        if (Json.pyEquals(recordsType, Json.arr("MFDB-132")) || "MFDB-132".equals(doc.get("Schema_Name"))) {
            return MFDB_SCHEMA_CHUNKED_104A;
        }
        if (Json.pyEquals(recordsType, Json.arr("Chunked")) && names.equals(CHUNKED_104A_FIELD_NAMES)) {
            return MFDB_SCHEMA_CHUNKED_104A;
        }
        if (names.equals(MFDB_ENTITY_FIELD_NAMES)) return MFDB_SCHEMA_ENTITY;
        if (names.equals(MFDB_ENTITY_LEGACY_FIELD_NAMES)) return MFDB_SCHEMA_ENTITY_LEGACY;
        if (Json.pyEquals(recordsType, Json.arr("mfdb")) && names.contains("entity_name") && names.contains("file_path")) {
            return MFDB_SCHEMA_MANIFEST;
        }
        return MFDB_SCHEMA_UNKNOWN;
    }

    public static final List<String> DEFAULT_KNOWN_MFDB_VERSIONS =
            Collections.unmodifiableList(Arrays.asList("1.31", "1.32", "1.38"));

    /** Lenient version check: warning string, or null when known / absent. */
    public static String mfdbCheckVersion(Map<String, Object> doc, List<String> knownVersions) {
        Object v = doc.get("MFDB_Version");
        if (v == null) return null;
        if (!knownVersions.contains(Json.pyStr(v))) {
            StringBuilder tup = new StringBuilder("(");
            for (int i = 0; i < knownVersions.size(); i++) {
                if (i > 0) tup.append(", ");
                tup.append(Json.pyRepr(knownVersions.get(i)));
            }
            if (knownVersions.size() == 1) tup.append(',');
            tup.append(')');
            return "MFDB_Version '" + Json.pyStr(v) + "' not in known set " + tup
                    + " -- proceeding on structural detection anyway, but this is worth a look.";
        }
        return null;
    }

    public static String mfdbCheckVersion(Map<String, Object> doc) {
        return mfdbCheckVersion(doc, DEFAULT_KNOWN_MFDB_VERSIONS);
    }

    private static Map<String, Object> result(boolean ok, String message, String schema, String warning) {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("ok", Boolean.valueOf(ok));
        r.put("message", message);
        r.put("schema", schema);
        r.put("warning", warning);
        return r;
    }

    /**
     * Single entry point for restoring any of the known chunk schemas.
     * manifestDir is the manifest's parent directory (needed for the manifest schema); version selects the release.
     */
    public static Map<String, Object> mfdbUnchunk(Map<String, Object> doc, String outputDir, String version,
                                                  String manifestDir) {
        String schema = mfdbDetectSchema(doc);
        String warning = mfdbCheckVersion(doc);
        String outRoot = PyFs.realpath(outputDir);
        boolean hasVersion = version != null && !version.isEmpty();

        if (schema.equals(MFDB_SCHEMA_CHUNKED_104A)) {
            int count = unchunkChunked104(doc, outputDir);
            Map<String, Object> r = result(true, "Restored " + count + " file(s) from Chunked-104a/MFDB-132 bundle.", schema, warning);
            r.put("out_dir", outRoot);
            r.put("file_count", Long.valueOf(count));
            return r;
        }

        if (schema.equals(MFDB_SCHEMA_MANIFEST)) {
            if (manifestDir == null || !hasVersion) {
                return result(false, "MFDB manifest requires manifest_dir and version.", schema, warning);
            }
            Map<String, Integer> fm = mfdbGetFieldMap(doc);
            List<Object> found = null;
            List<Object> values = Json.asList(doc.containsKey("Values") ? doc.get("Values") : new ArrayList<Object>());
            for (Object ro : values) {
                List<Object> r = row(ro);
                if (Json.pyEquals(r.get(fieldIndex(fm, "entity_name")), version)) { found = r; break; }
            }
            if (found == null) {
                return result(false, "Version '" + version + "' not found in manifest.", schema, warning);
            }
            String entityPath = pathJoin(manifestDir, asStr(found.get(fieldIndex(fm, "file_path")), "file_path"));
            if (!PyFs.exists(entityPath)) {
                return result(false, "Entity file missing: " + entityPath, schema, warning);
            }
            Map<String, Object> entityDoc = Json.asMap(Json.loads(PyFs.readText(entityPath)));
            if (entityDoc == null) throw new IllegalArgumentException("entity document is not an object");
            return mfdbUnchunk(entityDoc, outputDir, version, null);
        }

        if (schema.equals(MFDB_SCHEMA_ENTITY)) {
            if (!hasVersion) return result(false, "MFDB entity requires version.", schema, warning);
            Map<String, Integer> fm = mfdbGetFieldMap(doc);
            List<List<Object>> rows = new ArrayList<List<Object>>();
            List<Object> values = Json.asList(doc.containsKey("Values") ? doc.get("Values") : new ArrayList<Object>());
            for (Object ro : values) {
                List<Object> r = row(ro);
                if (Json.pyEquals(r.get(fieldIndex(fm, "version")), version)) rows.add(r);
            }
            if (rows.isEmpty()) return result(false, "No rows for version '" + version + "'.", schema, warning);
            PyFs.makedirs(outRoot);
            int count = 0;
            for (List<Object> r : rows) {
                Object rel = r.get(fieldIndex(fm, "Relative_Path"));
                if (!truthy(rel)) continue;
                String target = BejsonPathGuard.safeJoin(outRoot, asStr(rel, "Relative_Path"));
                Object content = r.get(fieldIndex(fm, "File_Content"));
                writeFileContent(target, truthy(r.get(fieldIndex(fm, "Is_Binary"))), truthy(content) ? content : "");
                count++;
            }
            Map<String, Object> res = result(true, "Restored " + count + " file(s) for version '" + version + "'.", schema, warning);
            res.put("out_dir", outRoot);
            res.put("file_count", Long.valueOf(count));
            return res;
        }

        if (schema.equals(MFDB_SCHEMA_ENTITY_LEGACY)) {
            return result(false, "Legacy MFDB entity schema -- no migration path by design. "
                    + "Re-chunk the source project with current tooling first.", schema, warning);
        }

        return result(false, "Could not identify chunk schema (structural detection failed).", MFDB_SCHEMA_UNKNOWN, warning);
    }

    public static Map<String, Object> mfdbUnchunk(Map<String, Object> doc, String outputDir) {
        return mfdbUnchunk(doc, outputDir, null, null);
    }
}
