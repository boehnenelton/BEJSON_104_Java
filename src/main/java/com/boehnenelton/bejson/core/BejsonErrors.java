/*
 * File Name:       BejsonErrors.java
 * Relative Path:   src/main/java/com/boehnenelton/bejson/core/BejsonErrors.java
 * Description:     Unified numeric error-code registry (port of lib_bejson_Core_bejson_errors.py 2.5.0). Codes, names and values identical to the Python reference.
 * VERSION:         1.0.0
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   af5208f4-ca4d-4203-b28a-98ac62cf37af
 */
package com.boehnenelton.bejson.core;

public final class BejsonErrors {
    private BejsonErrors() { }

    // ---------------------------------------------------------------------------
    // BEJSON Validation (1-16)
    // ---------------------------------------------------------------------------
    public static final int E_INVALID_JSON = 1;
    public static final int E_MISSING_MANDATORY_KEY = 2;
    public static final int E_INVALID_FORMAT = 3;
    public static final int E_INVALID_VERSION = 4;
    public static final int E_INVALID_RECORDS_TYPE = 5;
    public static final int E_INVALID_FIELDS = 6;
    public static final int E_INVALID_VALUES = 7;
    public static final int E_TYPE_MISMATCH = 8;
    public static final int E_RECORD_LENGTH_MISMATCH = 9;
    public static final int E_RESERVED_KEY_COLLISION = 10;
    public static final int E_INVALID_RECORD_TYPE_PARENT = 11;
    public static final int E_NULL_VIOLATION = 12;
    public static final int E_FILE_NOT_FOUND = 13;
    public static final int E_PERMISSION_DENIED = 14;
    public static final int E_ATOMIC_WRITE_FAILED = 15;
    public static final int E_INVALID_FORMAT_CREATOR = 16;
    // BEJSON Core ops (17-29)
    // Codes 17-19: parse/serialization layer (same codes as TS)
    public static final int E_CORE_PARSE_ERROR = 17;
    public static final int E_CORE_SERIALIZATION_ERROR = 18;
    public static final int E_CORE_NULL_DOCUMENT = 19;
    public static final int E_CORE_INVALID_VERSION = 20;
    public static final int E_CORE_INVALID_OPERATION = 21;
    public static final int E_CORE_INDEX_OUT_OF_BOUNDS = 22;
    public static final int E_CORE_FIELD_NOT_FOUND = 23;
    public static final int E_CORE_TYPE_CONVERSION_FAILED = 24;
    public static final int E_CORE_BACKUP_FAILED = 25;
    public static final int E_CORE_WRITE_FAILED = 26;
    public static final int E_CORE_QUERY_FAILED = 27;
    public static final int E_CORE_ENCRYPTION_FAILED = 28;
    public static final int E_CORE_DECRYPTION_FAILED = 29;
    // Aliases — map TS BEJSON_CORE_CODES aliases to canonical codes above
    public static final int E_CORE_UNSUPPORTED_OPERATION = E_CORE_INVALID_OPERATION; // 21
    public static final int E_CORE_WRITE_TYPE_MISMATCH = E_TYPE_MISMATCH; // 8
    public static final int E_CORE_WRITE_LENGTH_MISMATCH = E_RECORD_LENGTH_MISMATCH; // 9
    // ---------------------------------------------------------------------------
    // MFDB Validation (30-42)
    // ---------------------------------------------------------------------------
    public static final int E_MFDB_NOT_MANIFEST = 30;
    public static final int E_MFDB_NOT_ENTITY_FILE = 31;
    public static final int E_MFDB_MANIFEST_RECORDS_TYPE = 32;
    public static final int E_MFDB_ENTITY_NOT_FOUND = 33;
    public static final int E_MFDB_ENTITY_NAME_MISMATCH = 34;
    public static final int E_MFDB_DUPLICATE_ENTRY = 35;
    public static final int E_MFDB_NO_PARENT_HIERARCHY = 36;
    public static final int E_MFDB_MANIFEST_NOT_FOUND = 37;
    public static final int E_MFDB_BIDIRECTIONAL_FAIL = 38;
    public static final int E_MFDB_FK_UNRESOLVED = 39;
    public static final int E_MFDB_MISSING_REQUIRED_FIELD = 40;
    public static final int E_MFDB_NULL_REQUIRED = 41;
    public static final int E_MFDB_INVALID_ARCHIVE = 42;
    // ---------------------------------------------------------------------------
    // MFDB Core ops (50-72)
    // Codes 57-60 match TS MFDB_CORE_CODES
    // ---------------------------------------------------------------------------
    public static final int E_MFDB_CORE_MANIFEST_NOT_FOUND = 50;
    public static final int E_MFDB_CORE_ENTITY_NOT_FOUND = 51;
    public static final int E_MFDB_CORE_WRITE_FAILED = 52;
    public static final int E_MFDB_CORE_LOCK_FAILED = 53;
    public static final int E_MFDB_CORE_INVALID_OPERATION = 54;
    public static final int E_MFDB_CORE_INDEX_OUT_OF_BOUNDS = 55;
    public static final int E_MFDB_CORE_JOIN_FAILED = 56;
    public static final int E_MFDB_CORE_DUPLICATE_ENTITY_NAME = 57;
    public static final int E_MFDB_CORE_RECORD_COUNT_SYNC_FAILED = 58;
    public static final int E_MFDB_CORE_NULL_MANIFEST = 59;
    public static final int E_MFDB_CORE_ENTITY_NOT_IN_MANIFEST = 60;
    public static final int E_MFDB_CORE_ARCHIVE_ERROR = 70;
    public static final int E_MFDB_CORE_MOUNT_CONFLICT = 71;
    public static final int E_MFDB_CORE_CREATE_FAILED = 72;
    // ---------------------------------------------------------------------------
    // Core nesting (130-159)
    // ---------------------------------------------------------------------------
    public static final int E_NESTING_INVALID_CELL = 130; // Cell address out of bounds for mutation
    public static final int E_NESTING_NOT_BEJSON = 131; // Doc argument is not a dict
    public static final int E_NESTING_DEPTH_EXCEEDED = 132; // Recursion hit NESTING_MAX_DEPTH
    public static final int E_NESTING_CACHE_MISS = 133; // Address not found in NestMap (informational)
    public static final int E_NESTING_SCHEMA_MISMATCH = 134; // Column schema uniformity violation
    public static final int E_NESTING_CIRCULAR_REF = 135; // Same fingerprint seen twice in walk path
    public static final int E_NESTING_VALIDATION_FAILED = 136; // Nested doc fails 104 validation rules
    public static final int E_NESTING_FIELD_MAP_FAILED = 137; // Could not build field map for nested doc
    public static final int E_NESTING_QUERY_INVALID_PATH = 138; // Path string failed grammar parse
    public static final int E_NESTING_QUERY_EMPTY_PATH = 139; // Path was empty / whitespace only
}
