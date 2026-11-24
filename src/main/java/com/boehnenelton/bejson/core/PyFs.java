/*
 * File Name:       PyFs.java
 * Relative Path:   src/main/java/com/boehnenelton/bejson/core/PyFs.java
 * Description:     Filesystem helpers that reproduce the Python os / os.path / shutil / pathlib behaviours the Core
 *                  modules rely on (normpath, expanduser, expandvars, rmtree, copytree, atomic text write, SHA-256).
 *                  Java 8 / Android API 26+ safe (java.io + java.nio.file only).
 * VERSION:         1.0.0
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   9cc0974b-f12e-49f1-8ec8-5e97d6f607d8
 */
package com.boehnenelton.bejson.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PyFs {

    private PyFs() { }

    // ------------------------------------------------------------ path string helpers

    /** posixpath.normpath. */
    public static String normpath(String path) {
        if (path == null || path.isEmpty()) return ".";
        boolean initialSlash = path.startsWith("/");
        int initialSlashes = 0;
        if (initialSlash) {
            initialSlashes = 1;
            if (path.startsWith("//") && !path.startsWith("///")) initialSlashes = 2;
        }
        List<String> out = new ArrayList<String>();
        for (String comp : path.split("/", -1)) {
            if (comp.isEmpty() || comp.equals(".")) continue;
            if (!comp.equals("..") || (!initialSlash && out.isEmpty())
                    || (!out.isEmpty() && out.get(out.size() - 1).equals(".."))) {
                out.add(comp);
            } else if (!out.isEmpty()) {
                out.remove(out.size() - 1);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < initialSlashes; i++) sb.append('/');
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(out.get(i));
        }
        String r = sb.toString();
        return r.isEmpty() ? "." : r;
    }

    /** os.path.abspath (relative paths resolve against user.dir). */
    public static String abspath(String path) {
        String p = path;
        if (!p.startsWith("/")) {
            String cwd = System.getProperty("user.dir", ".");
            p = cwd + "/" + p;
        }
        return normpath(p);
    }

    /** os.path.expanduser: only "~" and "~/..." (HOME, then user.home). */
    public static String expanduser(String path) {
        if (path == null || !path.startsWith("~")) return path;
        int slash = path.indexOf('/');
        String user = slash < 0 ? path.substring(1) : path.substring(1, slash);
        if (!user.isEmpty()) return path;
        String home = BejsonEnv.getenv("HOME");
        if (home == null || home.isEmpty()) home = System.getProperty("user.home", "");
        if (home.isEmpty()) return path;
        String h = home.endsWith("/") ? home.substring(0, home.length() - 1) : home;
        String rest = slash < 0 ? "" : path.substring(slash);
        String r = h + rest;
        return r.isEmpty() ? "/" : r;
    }

    /** os.path.expandvars: $VAR and ${VAR}; unknown variables are left untouched. */
    public static String expandvars(String path) {
        if (path == null || path.indexOf('$') < 0) return path;
        StringBuilder sb = new StringBuilder();
        int n = path.length();
        int i = 0;
        while (i < n) {
            char c = path.charAt(i);
            if (c != '$') { sb.append(c); i++; continue; }
            if (i + 1 < n && path.charAt(i + 1) == '{') {
                int close = path.indexOf('}', i + 2);
                if (close > 0) {
                    String name = path.substring(i + 2, close);
                    String v = BejsonEnv.getenv(name);
                    if (v != null) { sb.append(v); } else { sb.append(path, i, close + 1); }
                    i = close + 1;
                    continue;
                }
                sb.append(c); i++;
                continue;
            }
            int j = i + 1;
            while (j < n && (Character.isLetterOrDigit(path.charAt(j)) || path.charAt(j) == '_')
                    && path.charAt(j) < 128) j++;
            if (j == i + 1) { sb.append(c); i++; continue; }
            String name = path.substring(i + 1, j);
            String v = BejsonEnv.getenv(name);
            if (v != null) sb.append(v); else sb.append(path, i, j);
            i = j;
        }
        return sb.toString();
    }

    /** os.path.join for two or more segments (an absolute segment resets the path). */
    public static String join(String first, String... rest) {
        String path = first;
        for (String p : rest) {
            if (p.startsWith("/")) path = p;
            else if (path.isEmpty() || path.endsWith("/")) path = path + p;
            else path = path + "/" + p;
        }
        return path;
    }

    public static String dirname(String path) {
        int i = path.lastIndexOf('/') + 1;
        String head = path.substring(0, i);
        boolean allSlashes = !head.isEmpty();
        for (int k = 0; k < head.length(); k++) {
            if (head.charAt(k) != '/') { allSlashes = false; break; }
        }
        if (!head.isEmpty() && !allSlashes) {
            int e = head.length();
            while (e > 0 && head.charAt(e - 1) == '/') e--;
            head = head.substring(0, e);
        }
        return head;
    }

    public static String basename(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** os.path.relpath(path, start). */
    public static String relpath(String path, String start) {
        List<String> sl = new ArrayList<String>();
        for (String x : abspath(start).split("/")) if (!x.isEmpty()) sl.add(x);
        List<String> pl = new ArrayList<String>();
        for (String x : abspath(path).split("/")) if (!x.isEmpty()) pl.add(x);
        int i = 0;
        while (i < sl.size() && i < pl.size() && sl.get(i).equals(pl.get(i))) i++;
        List<String> rel = new ArrayList<String>();
        for (int k = 0; k < sl.size() - i; k++) rel.add("..");
        rel.addAll(pl.subList(i, pl.size()));
        if (rel.isEmpty()) return ".";
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < rel.size(); k++) {
            if (k > 0) sb.append('/');
            sb.append(rel.get(k));
        }
        return sb.toString();
    }

    /** Path.resolve() / os.path.realpath (non-strict). */
    public static String realpath(String path) {
        try {
            return new File(abspath(path)).getCanonicalPath();
        } catch (IOException e) {
            return abspath(path);
        }
    }

    // ------------------------------------------------------------ queries

    public static boolean exists(String path) { return new File(path).exists(); }

    public static boolean isFile(String path) { return new File(path).isFile(); }

    public static boolean isDir(String path) { return new File(path).isDirectory(); }

    // ------------------------------------------------------------ read / write

    public static byte[] readBytes(String path) {
        File f = new File(path);
        if (f.isDirectory()) throw new BejsonOSError(21, "Is a directory: '" + path + "'", BejsonOSError.Kind.IS_A_DIRECTORY, null);
        try {
            return Files.readAllBytes(f.toPath());
        } catch (IOException e) {
            throw BejsonOSError.wrap(e, path);
        }
    }

    /** Path.read_text(encoding="utf-8") / open(...).read(): strict decode, throws on invalid UTF-8. */
    public static String readText(String path) {
        return decodeUtf8Strict(readBytes(path), path);
    }

    public static String decodeUtf8Strict(byte[] data, String label) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            throw new BejsonOSError(0, "'utf-8' codec can't decode bytes in " + label, BejsonOSError.Kind.GENERIC, e);
        }
    }

    public static void writeBytes(String path, byte[] data) {
        try {
            FileOutputStream out = new FileOutputStream(path);
            try { out.write(data); } finally { out.close(); }
        } catch (IOException e) {
            throw BejsonOSError.wrap(e, path);
        }
    }

    public static void writeText(String path, String text) {
        writeBytes(path, text.getBytes(StandardCharsets.UTF_8));
    }

    /** os.makedirs(path, exist_ok=True). */
    public static void makedirs(String path) {
        File f = new File(path);
        if (f.isDirectory()) return;
        if (!f.mkdirs() && !f.isDirectory()) {
            throw new BejsonOSError(13, "Cannot create directory: '" + path + "'", BejsonOSError.Kind.PERMISSION, null);
        }
    }

    /**
     * tempfile.mkstemp(dir) + write + fsync + os.replace: writes data to a fresh sibling temp file, syncs it, then
     * atomically renames it over target. The parent directory is created first. Returns on success, throws BejsonOSError.
     */
    public static void atomicWriteBytes(String target, byte[] data, String tmpPrefix, String tmpSuffix) {
        String dir = dirname(abspath(target));
        makedirs(dir);
        File tmp = null;
        try {
            tmp = File.createTempFile(tmpPrefix, tmpSuffix, new File(dir));
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(data);
                out.flush();
                out.getFD().sync();
            } finally {
                out.close();
            }
            replace(tmp, new File(target));
            tmp = null;
        } catch (IOException e) {
            throw BejsonOSError.wrap(e, target);
        } finally {
            if (tmp != null && tmp.exists()) tmp.delete();
        }
    }

    /** os.replace(src, dst). */
    public static void replace(File src, File dst) throws IOException {
        try {
            Files.move(src.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** os.rename(src, dst) for files or directories; throws BejsonOSError when it fails. */
    public static void rename(String src, String dst) {
        File s = new File(src);
        if (!s.exists()) throw BejsonOSError.notFound(src);
        if (!s.renameTo(new File(dst))) {
            throw new BejsonOSError(1, "Cannot rename '" + src + "' to '" + dst + "'");
        }
    }

    /** shutil.rmtree(path, ignore_errors=true) - also deletes a plain file or symlink. */
    public static void rmtree(String path) {
        rmtree(new File(path));
    }

    public static void rmtree(File f) {
        try {
            if (Files.isSymbolicLink(f.toPath())) { f.delete(); return; }
        } catch (RuntimeException ignored) {
            // fall through
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) rmtree(k);
        }
        f.delete();
    }

    /** shutil.copytree(src, dst) (dst must not exist; parents are created). */
    public static void copytree(String src, String dst) {
        File s = new File(src);
        if (!s.isDirectory()) throw BejsonOSError.notFound(src);
        File d = new File(dst);
        if (d.exists()) throw BejsonOSError.exists(dst);
        copyInto(s, d);
    }

    private static void copyInto(File s, File d) {
        if (Files.isSymbolicLink(s.toPath())) return; // never follow links (cycles / escapes / exfiltration)
        if (s.isDirectory()) {
            if (!d.mkdirs() && !d.isDirectory()) throw new BejsonOSError(13, "Cannot create directory: '" + d + "'");
            File[] kids = s.listFiles();
            if (kids != null) for (File k : kids) copyInto(k, new File(d, k.getName()));
        } else {
            try {
                File parent = d.getParentFile();
                if (parent != null) parent.mkdirs();
                Files.copy(s.toPath(), d.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw BejsonOSError.wrap(e, s.getPath());
            }
        }
    }

    /** shutil.copy2-lite: copies one file, creating parents. */
    public static void copyFile(String src, String dst) {
        try {
            File parent = new File(dst).getParentFile();
            if (parent != null) parent.mkdirs();
            Files.copy(new File(src).toPath(), new File(dst).toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw BejsonOSError.wrap(e, src);
        }
    }

    /** os.listdir (names only, sorted for determinism). */
    public static List<String> listdir(String path) {
        File[] kids = new File(path).listFiles();
        if (kids == null) throw BejsonOSError.notFound(path);
        List<String> names = new ArrayList<String>();
        for (File k : kids) names.add(k.getName());
        java.util.Collections.sort(names);
        return names;
    }

    // ------------------------------------------------------------ hashing / ids

    private static final char[] HEXCHARS = "0123456789abcdef".toCharArray();

    public static String hex(byte[] b) {
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[i * 2] = HEXCHARS[(b[i] >> 4) & 0xf];
            out[i * 2 + 1] = HEXCHARS[b[i] & 0xf];
        }
        return new String(out);
    }

    public static String sha256Hex(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** SHA-256 of a file read in 64 KiB blocks. */
    public static String sha256File(String path) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = new FileInputStream(path);
            try {
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            } finally {
                in.close();
            }
            return hex(md.digest());
        } catch (IOException e) {
            throw BejsonOSError.wrap(e, path);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** uuid.uuid4().hex */
    public static String uuidHex() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
