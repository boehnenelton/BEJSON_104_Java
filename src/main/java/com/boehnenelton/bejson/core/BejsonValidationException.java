/*
 * File Name:       BejsonValidationException.java
 * Relative Path:   src/main/java/com/boehnenelton/bejson/core/BejsonValidationException.java
 * Description:     Port of BEJSONValidationError: validation failure carrying the numeric E_* code.
 * VERSION:         1.0.0
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   f697bb3b-1d94-4b1c-a94f-cf7fd6a6a6d6
 */
package com.boehnenelton.bejson.core;

public class BejsonValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public final int code;

    public BejsonValidationException(String message, int code) {
        super(message);
        this.code = code;
    }
}
