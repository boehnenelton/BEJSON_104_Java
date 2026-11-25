# bejson-core-java

Pure-Java (Java 8 language level, zero third-party dependencies) port of the BEJSON / MFDB **Core family**,
behaviourally matched against the frozen `libraries_104/Lib_PY/Core` reference. Android-safe: no `android.*`
imports, no `javax.*`, no `java.awt`, logging through `java.util.logging`.
**Requires Android API 26+** (or core-library desugaring below that): file I/O uses `java.nio.file` (`Files.readAllBytes`,
`Files.copy`/`move`, `ATOMIC_MOVE`, symlink checks) and `java.util.Base64`.

Author: Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton

## Modules (package `com.boehnenelton.bejson.core`)

| Java class | Python / JS / TS / Bash counterpart |
|---|---|
| `Json`, `PyFs`, `PyOps` | Python `json` / `os` / builtin semantics the libraries depend on (support layer) |
| `BejsonErrors`, `BejsonCoreException`, `BejsonValidationException`, `BejsonOSError` | `bejson_errors` |
| `BejsonCore`, `ResilientPidLock` | `bejson_core` (field map cache, atomic write, PID lock) |
| `BejsonValidator`, `BejsonSchema`, `BejsonParse` | `bejson_validator`, `bejson_schema`, `bejson_parse` |
| `BejsonEnv`, `BejsonPathGuard` | `bejson_env`, `bejson_path_guard` |
| `BejsonCoreNesting` | `bejson_core_nesting` |
| `BejsonChunking`, `Mfdb132Archive` | `bejson_chunking` (Chunked-104a, MFDB-132, `MFDB132Archive`) |
| `MfdbCore`, `MfdbArchive` | `mfdb_core` (+ `MFDBArchive`) |
| `MfdbValidator` | `mfdb_validator` |

Out of scope (Python-only extras): md_to_html, provider, static_backend, state_management, list_validator, 104db_parser, cli/.

## Use

```java
Map<String, Object> doc = BejsonCore.loadFile(path);                       // null if missing/invalid
BejsonValidator.ValidationResult r = BejsonValidator.validateBejson(doc);  // Map, JSON text, or (obj, true) for a file path
if (!r.valid) for (String e : r.errors) { /* r.errors / r.warnings are public fields, no getters */ }
Map<String, Integer> fm = BejsonCore.getFieldMap(doc);                     // field name -> column index
List<Map<String, Object>> entities = new ArrayList<>();                    // each: {"name": "...", "fields": [...]}
String manifest = MfdbCore.createDatabase(dir, "Shop", entities);          // path of 104a.mfdb.bejson
```

Android: copy the sources or the jar into a module; no permissions are required beyond whatever storage you pass in.
Pass app-private directories (e.g. `context.getFilesDir()`); the library never prompts for paths.

## Verification

`dev/parity/run_parity.sh <path-to-Lib_PY/Core>` regenerates fixtures with the Python reference and runs
six drivers (JSON, validator/core/schema, MFDB flows, nesting, chunking/MFDB-132/validator/archives/parse, path guard/env):
12,688 checks, all passing. Run it with a UTF-8 locale (the script sets `LC_ALL=C.UTF-8`).
See `docs/technical_overview.md` for the documented deviations.
