/*
 * File Name:       BejsonCoreException.java
 * Relative Path:   src/main/java/com/boehnenelton/bejson/core/BejsonCoreException.java
 * Description:     Port of BEJSONCoreError: raised when a BEJSON core operation fails. code is an optional numeric error code (null when absent).
 * VERSION:         1.0.0
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   cb071132-e383-4f96-8142-0fc000ce4990
 */
package com.boehnenelton.bejson.core;

public class BejsonCoreException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    /** Optional numeric error code (BejsonErrors.E_*), or null. */
    public final Integer code;

    public BejsonCoreException(String message) { this(message, null); }

    public BejsonCoreException(String message, Integer code) {
        super(message);
        this.code = code;
    }
}
