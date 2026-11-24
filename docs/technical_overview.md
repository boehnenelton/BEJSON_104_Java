# Technical Overview — bejson-core-java

Author: Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
Document Relational ID: 1074e71c-718d-4063-9519-25479edb90ec

## Design
- **Support layer:** `Json` reproduces Python `json` behaviour used by the libraries: error messages (`Expecting value: line 1 column 1 (char 0)` etc.),
  `indent=2` with `ensure_ascii` escaping (including U+007F), float `repr`, arbitrary-size ints, NaN/Infinity, `==`/`str`/`repr` semantics.
  Files are therefore byte-identical to what the Python library writes.
- **Field Map Cache:** `BejsonCore.getFieldMap` builds name→index once, stored under `_bejson_field_map`, freshness-guarded; forged/stale caches are rebuilt
  and never persisted (`atomicWrite` strips it).
- **Locking:** `ResilientPidLock` = `mkdir` lock dir + `lock_meta.json {pid,timestamp,token}`, stale-lock takeover, try-with-resources.
- **Path safety:** `BejsonPathGuard.safeJoin`, `mfdbEscapesRoot`, `safeExtractZip` (zip-slip), `MfdbArchive`/`Mfdb132Archive` extraction guarded.
- **Android:** PID via `ProcessHandle` reflection → `android.os.Process` reflection → `/proc/self`; no compile-time android dependency.

## Documented deviations from the Python reference
1. `BejsonEnv`: process environment is read-only in Java, so `setenv` writes to an in-process overlay consulted by `getenv`; `.py` env files are read with a restricted literal parser (no code execution).
2. `BejsonChunking.createChunked104`: directory walk order is deterministic (files sorted, then sub-directories sorted); Python uses filesystem order. Content is identical; parity tests compare rows ordered by `Relative_Path`.
3. Zip entries written by `MfdbArchive.commit` are sorted (deterministic archives).
4. `BejsonParse.defaultOut()` resolves `user.dir/output`.
5. No directory `fsync` after atomic rename (not available in `java.nio` on Android).
6. Python-internal exception texts (`[Errno 2] ...`, `object of type 'str' has no len()`) are reproduced where the libraries surface them in results; other OS-level wording differs.
7. Parity drivers must run under a UTF-8 file-name locale (JVM `sun.jnu.encoding`); Android is always UTF-8.

## Parity coverage (12,688 checks)
JSON layer 152 · validator/core/schema 167 · MFDB create/CRUD/join/sync/verify/heal/debug/drift/federation 132 · nesting 542 · chunking/MFDB-132/MFDB validator/archives/parse 99 · path guard/env 80.
Not literally ported: the four Python `unittest` files; their behaviours are exercised by the oracle drivers instead.

## V1.0.1 hardening notes
- `Json.MAX_DEPTH = 256` (CPython ~9998): deeper documents raise a RecursionError-style exception instead of overflowing the stack.
- Extraction limits: 2 GiB total / 100000 entries (`BejsonPathGuard.setMaxExtractBytes`).
- Validator and MFDB fuzz (ParityF/ParityG) compare against the frozen Python oracle; 10 error-wording-only diffs are reported, not failed.
