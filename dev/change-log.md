# Change Log — bejson-core-java
## V1.0.4-PKG104 — 2026-11-25
- Docs only: README states the API 26+ requirement accurately (nio.file I/O and Base64, not just ATOMIC_MOVE); usage example matches real signatures. No code change.
## V1.0.3-PKG103 — 2026-11-25
- Fix (external audit #4): BejsonParse project name "." / ".." / all-dots is replaced by My_Project; overwrite target resolved via BejsonPathGuard.safeJoin (no parent-dir escape).
- Fix (#2): MfdbCore.updateManifestRecordCount takes a ResilientPidLock on the manifest (order entity -> manifest) so concurrent writers on different entities cannot lose record_count updates.
- Fix (#6): BejsonCore.sortByField uses one stable sort with swapped operands for reverse (same result as Python, half the work, no negation overflow).
- Add (#11): BejsonCore.RELEASE_VERSION = 300.
- Tests: HardeningTests 23 checks.
## V1.0.2-PKG102 — 2026-11-24
- Hardening: copytree, MFDB archive commit and chunk walk no longer follow symlinks (cycle / outside-file exfiltration).
- Hardening: entity `file_path` that escapes the MFDB root is rejected in getEntityPath ([E_MFDB_PATH_ESCAPE]); deviation from Python, which does not check.
- Hardening: entry-count limit now enforced in MfdbArchive.mount; in-memory zip reads capped at 64 MiB (OOM on Android heaps).
- Fix: MFDB `.mfdb_lock` written atomically; commit temp file created beside destination with ATOMIC_MOVE; storage-root remap requires a path boundary; ResilientPidLock failure cleanup no longer deletes a lock re-created by another process.
- Tests: HardeningTests 21 checks (link, boundary).

## V1.0.1-PKG101 — 2026-11-23
- Hardening: JSON depth cap (Json.MAX_DEPTH=256) on parse/write/deepCopy with Python-style RecursionError wording; no StackOverflowError on deep input.
- Hardening: zip-bomb limits (2 GiB total extracted, 100000 entries) via BejsonPathGuard.copyBounded in safeExtractZip, MfdbArchive mount/resurrect, MfdbValidator zip read, MfdbCore readAll.
- Fix: BejsonValidator and MfdbValidator.rowsAsDicts now follow Python duck-typing on malformed documents (non-iterable/unhashable/non-subscriptable fields, rows, names) — validator fuzz 8000/8000, MFDB fuzz 3500/3500 hard-parity.
- Fix: BejsonCoreNesting cache accessors synchronized (thread safety).
- Tests: HardeningTests (16), ParityF (validator fuzz), ParityG (MFDB fuzz); total checks 12,688 + 16. ParityG wording-only diffs (10) reported, not failed.


## V1.0.0-PKG100 — 2026-11-22
- Initial pure-Java port of the Core family (21 classes), Gradle java-library, parity harness vs frozen Python reference (1172 checks).
- Fixed during verification: U+007F escaping in `Json`; validator field-map cache equality (Python `==` semantics); malformed-input error text for string `Fields`/`Values` rows and non-container documents.
