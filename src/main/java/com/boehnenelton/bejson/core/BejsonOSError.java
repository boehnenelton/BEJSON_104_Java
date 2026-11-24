/*
 * File Name:       BejsonOSError.java
 * Relative Path:   src/main/java/com/boehnenelton/bejson/core/BejsonOSError.java
 * Description:     Port of Python's OSError family (FileNotFoundError, PermissionError, FileExistsError, ...). Unchecked so Python's 'except Exception' maps to catch (RuntimeException).
 * VERSION:         1.0.0
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   9014b456-9b2d-4dd6-a4c4-cb0cce89cf98
 */
package com.boehnenelton.bejson.core;

public class BejsonOSError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Mirrors the Python subclass that would have been raised. */
    public enum Kind { GENERIC, FILE_NOT_FOUND, FILE_EXISTS, PERMISSION, NOT_A_DIRECTORY, IS_A_DIRECTORY }

    public final int errno;
    public final Kind kind;

    public BejsonOSError(int errno, String message) { this(errno, message, Kind.GENERIC, null); }

    public BejsonOSError(int errno, String message, Kind kind, Throwable cause) {
        super(message, cause);
        this.errno = errno;
        this.kind = kind;
    }

    public static BejsonOSError notFound(String path) {
        return new BejsonOSError(2, "No such file or directory: '" + path + "'", Kind.FILE_NOT_FOUND, null);
    }

    public static BejsonOSError exists(String path) {
        return new BejsonOSError(17, "File exists: '" + path + "'", Kind.FILE_EXISTS, null);
    }

    public static BejsonOSError wrap(java.io.IOException e, String path) {
        if (e instanceof java.io.FileNotFoundException || e instanceof java.nio.file.NoSuchFileException) {
            return new BejsonOSError(2, "No such file or directory: '" + path + "'", Kind.FILE_NOT_FOUND, e);
        }
        if (e instanceof java.nio.file.AccessDeniedException) {
            return new BejsonOSError(13, "Permission denied: '" + path + "'", Kind.PERMISSION, e);
        }
        if (e instanceof java.nio.file.FileAlreadyExistsException) {
            return new BejsonOSError(17, "File exists: '" + path + "'", Kind.FILE_EXISTS, e);
        }
        return new BejsonOSError(5, String.valueOf(e.getMessage()) + ": '" + path + "'", Kind.GENERIC, e);
    }
}
