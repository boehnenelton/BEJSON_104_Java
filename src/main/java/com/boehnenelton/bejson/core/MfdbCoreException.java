/*
 * File Name:       MfdbCoreException.java
 * Relative Path:   src/main/java/com/boehnenelton/bejson/core/MfdbCoreException.java
 * Description:     Port of MFDBCoreError: raised when an MFDB core operation fails; code is the numeric E_MFDB_* value.
 * VERSION:         1.0.0
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   d92508f4-aa29-4fb5-9739-aff3a82518ad
 */
package com.boehnenelton.bejson.core;

public class MfdbCoreException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public final int code;

    public MfdbCoreException(String message, int code) {
        super(message);
        this.code = code;
    }
}
