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
