# Security Notes

- No secrets in code or config; env store paths are constants for resolution only (`BejsonEnv`, `BejsonPathGuard`).
- Zip-slip guarded (`safeExtractZip`, mount paths); Chunked-104 unchunk rejects `..` relative paths and verifies SHA-256 hashes.
- `.py` env files parsed with a restricted literal parser (never executed).
- `MfdbCore.federationPollDropzone` trusts files in the drop directory only as JSON data.

## V1.0.1-PKG101
- Deep-nesting DoS: parse/write/deepCopy capped at depth 256 (Python allows ~9998; documented deviation).
- Zip bombs / entry floods: 2 GiB extracted-bytes and 100000-entry caps; configurable via BejsonPathGuard.setMaxExtractBytes.
- Zip-slip and symlink guards covered by HardeningTests.
- Nesting cache is a process-global map; accessors now synchronized.
- Residual: error wording for some type-confusion inputs differs from CPython (10 cases, both sides reject); OS-error wording approximated.

## V1.0.2-PKG102 (independent review findings)
Fixed: symlink following, manifest path escape, mount entry cap, 64 MiB in-memory read cap, atomic lock/commit, storage prefix boundary, lock-cleanup race.
Accepted / Python-parity, documented (not changed): unlocked manifest record_count read-modify-write across entities (#5); createEntityFile overwrites existing entity (#8); mount/unmount wipe target dir and sticky-path skips owner check (#2,#3); `expandvars` on archive entry names (#11); shared-storage env overlay may override *_ROOT (#7, host must treat shared storage as untrusted); temp files are mode 0600 (#9); no home dir on Android requires host setenv (#13).

## V1.0.3-PKG103 — external audit triage (Gemini report, 12 items + 3 minor)
Fixed: #2 manifest lock, #4 dot-name traversal, #6 sort, #11 constant.
Python-parity, kept on purpose (verified in frozen Lib_PY/Core): #3 O(1) field-map freshness guard (documented trade-off), #5 atomic_write does not expand placeholders, #7 "expected number, got bool" wording, #8 sort_keys on mixed keys raises in Python too, #9 distill_logs takes the entity file path (Python signature), minor updateField/checkFieldMapCache errors.
Documented, not changed: #1 requires Android API 26+ (java.nio.file, java.util.Base64; use desugaring below that); #10 same-process leaked locks are not reclaimable and /proc/<pid> may be hidden for other UIDs under Android hidepid (locks are not designed for cross-app use); #12 README examples quoted in the report are not in this package's README.
