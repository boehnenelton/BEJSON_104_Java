/*
 * File Name:       TestKit.java
 * Relative Path:   src/test/java/com/boehnenelton/bejson/core/TestKit.java
 * Description:     Dependency-free test harness (no JUnit): named checks, deep JSON equality, temp dirs, exit code.
 *                  Test classes are plain mains; run them via the Gradle 'parityTests' task or java -cp.
 * VERSION:         1.0.0
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   0d1b24ff-0098-4cd0-9f63-d907d1c81478
 */
package com.boehnenelton.bejson.core;

import java.io.File;
import java.nio.file.Files;

public final class TestKit {
    private int passed;
    private int failed;
    private final String suite;

    public TestKit(String suite) { this.suite = suite; }

    public void check(String name, boolean cond) {
        if (cond) { passed++; } else { failed++; System.err.println("FAIL [" + suite + "] " + name); }
    }

    public void eq(String name, Object expected, Object actual) {
        boolean ok = Json.pyEquals(expected, actual) || (expected != null && expected.equals(actual));
        if (!ok) System.err.println("  expected=" + Json.pyRepr(expected) + " actual=" + Json.pyRepr(actual));
        check(name, ok);
    }

    public void raises(String name, Runnable r) {
        try { r.run(); check(name + " (expected exception)", false); } catch (RuntimeException e) { check(name, true); }
    }

    public static File tempDir(String prefix) {
        try { return Files.createTempDirectory(prefix).toFile(); } catch (java.io.IOException e) { throw new RuntimeException(e); }
    }

    /** Prints a summary line and exits non-zero on failure. */
    public void finish() {
        System.out.println(suite + ": " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    public int failures() { return failed; }
}
