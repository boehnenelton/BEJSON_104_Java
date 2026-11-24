# BEJSON 104 Java (Core Reference Library)

Pure-Java (Java 8 language level, zero third-party dependencies) port of the BEJSON / MFDB **Core family**, behaviourally matched against the frozen `libraries_104/Lib_PY/Core` reference implementation.

Designed for headless servers, CLI tools, and Android applications. Android-safe: zero `android.*` imports, no `javax.*`, no `java.awt`, logging through `java.util.logging`, file I/O through `java.io` and `java.nio.file` (`ATOMIC_MOVE` on API 26+).

Author: **Elton Boehnen** · [boehnenelton2024@gmail.com](mailto:boehnenelton2024@gmail.com) · [boehnenelton2024.pages.dev](https://boehnenelton2024.pages.dev) · [github.com/boehnenelton](https://github.com/boehnenelton)

---

## Table of Contents

- [Architectural Invariants](#architectural-invariants)
- [Modules & Component Mapping](#modules--component-mapping)
- [Installation & Dependency Setup](#installation--dependency-setup)
- [Core Usage Examples](#core-usage-examples)
  - [Loading and Validating BEJSON Documents](#loading-and-validating-bejson-documents)
  - [O(1) Dynamic Field Map Caching](#o1-dynamic-field-map-caching)
  - [Multi-File Database (MFDB) Management](#multi-file-database-mfdb-management)
  - [Nested Document Coordination](#nested-document-coordination)
- [Verification & Parity Harness](#verification--parity-harness)
- [License & Attribution](#license--attribution)

---

## Architectural Invariants

1. **Zero External Dependencies**: Pure Java Standard Library implementation. Does not depend on Jackson, Gson, or external JSON parsers.
2. **Deterministic O(1) Field Map Caching**: Never access tabular values by hardcoded positional indexes; column lookups resolve dynamically through field maps.
3. **Atomic File Persistence**: Updates write through temporary PID-locked files before executing atomic rename switches to guarantee crash safety.
4. **Android Native Compatibility**: Standard Java SE 8 runtime baseline that runs seamlessly across Android devices without runtime permission requirements beyond target directory storage.

---

## Modules & Component Mapping

All classes are located in package `com.boehnenelton.bejson.core`:

| Java Class | Python Reference Counterpart | Functional Scope |
|---|---|---|
| `Json`, `PyFs`, `PyOps` | `json` / `os` / builtins | Pure-Java JSON serialization, UTF-8 streaming, filesystem abstractions |
| `BejsonErrors`, `BejsonCoreException`, `BejsonValidationException`, `BejsonOSError` | `bejson_errors` | Standardized error taxonomy and hierarchy |
| `BejsonCore`, `ResilientPidLock` | `bejson_core` | Field map cache, atomic file persistence, PID locking |
| `BejsonValidator`, `BejsonSchema`, `BejsonParse` | `bejson_validator`, `bejson_schema`, `bejson_parse` | Canonical envelope verification, schema enforcement, record parsing |
| `BejsonEnv`, `BejsonPathGuard` | `bejson_env`, `bejson_path_guard` | `ENV:<VAR>` dynamic pointer resolution and strict path boundary traversal guards |
| `BejsonCoreNesting` | `bejson_core_nesting` | Deep recursive tabular document nesting (up to depth 16) |
| `BejsonChunking`, `Mfdb132Archive` | `bejson_chunking` | Chunked-104a text containers and MFDB-132 compressed archives |
| `MfdbCore`, `MfdbArchive` | `mfdb_core` | Multi-File Database manifest initialization, table linking, and database queries |
| `MfdbValidator` | `mfdb_validator` | Multi-File Database structural integrity, foreign key cascades, and table references |

---

## Installation & Dependency Setup

### Gradle (Multi-Project or Module)

Add the library to your `settings.gradle`:

```groovy
include ':bejson-104-java'
project(':bejson-104-java').projectDir = file('path/to/bejson-104-java')
```

And in your application `build.gradle`:

```groovy
dependencies {
    implementation project(':bejson-104-java')
}
```

### Standalone JAR

Build the release JAR artifact with Gradle:

```bash
gradle jar
```

The compiled library will be output to `build/libs/bejson-core-java-1.0.0.jar`.

---

## Core Usage Examples

### Loading and Validating BEJSON Documents

```java
import com.boehnenelton.bejson.core.BejsonCore;
import com.boehnenelton.bejson.core.BejsonValidator;
import java.util.Map;

// Load file safely through BejsonCore
Map<String, Object> document = BejsonCore.loadFile("/path/to/manifest.104a.bejson");

// Validate document against BEJSON structural specification
BejsonValidator.ValidationResult result = BejsonValidator.validateBejson(document);
if (result.isValid()) {
    System.out.println("BEJSON document is valid.");
} else {
    System.err.println("Validation errors: " + result.getErrors());
}
```

### O(1) Dynamic Field Map Caching

```java
import com.boehnenelton.bejson.core.BejsonCore;
import java.util.List;
import java.util.Map;

// Generate field map cache from schema definition
Map<String, Integer> fieldMap = BejsonCore.buildFieldMap(document);

List<List<Object>> values = (List<List<Object>>) document.get("Values");
for (List<Object> row : values) {
    String recordId = (String) row.get(fieldMap.get("record_id"));
    String status = (String) row.get(fieldMap.get("status"));
    System.out.println("ID: " + recordId + " | Status: " + status);
}
```

### Multi-File Database (MFDB) Management

```java
import com.boehnenelton.bejson.core.MfdbCore;
import com.boehnenelton.bejson.core.MfdbValidator;
import java.util.Arrays;
import java.util.List;

List<String> entities = Arrays.asList("Inventory", "Orders", "Customers");

// Initialize new MFDB database manifest and entity tables
String manifestPath = MfdbCore.createDatabase("/data/db", "CommerceStore", entities);

// Validate relational database integrity
boolean isValidDb = MfdbValidator.validateDatabase(manifestPath);
System.out.println("MFDB Integrity: " + isValidDb);
```

### Nested Document Coordination

```java
import com.boehnenelton.bejson.core.BejsonCoreNesting;
import java.util.Map;

// Extract nested child tables from parent coordinates
Map<String, Object> childTable = BejsonCoreNesting.extractNestedTable(document, "Values", rowIndex, colIndex);
```

---

## Verification & Parity Harness

The repository includes a comprehensive Python-oracle parity harness (`dev/parity/run_parity.sh`) comparing every Java engine calculation against the frozen Python reference (`libraries_104/Lib_PY/Core`):

```bash
dev/parity/run_parity.sh /path/to/libraries_104/Lib_PY/Core
```

**Verification Results**:
- **12,688 checks executed across 6 test drivers**
- **0 errors / 100% parity passing**
- Test drivers:
  - JSON serialization & number representation parity
  - Schema, core mutations, and field map validator parity
  - Multi-File Database (MFDB) full-lifecycle flow parity
  - Recursive nested table parity (depths 1 through 16)
  - Chunking-104a, MFDB-132 container packing/unpacking, and archive parity
  - PathGuard boundary traversal prevention and environment pointer resolution parity

---

## License & Attribution

All files and library components are released under the **PolyForm Noncommercial License 1.0.0** (`PolyForm-Noncommercial-1.0.0`).

- **Author**: Elton Boehnen
- **Email**: [boehnenelton2024@gmail.com](mailto:boehnenelton2024@gmail.com)
- **Web**: [https://boehnenelton2024.pages.dev](https://boehnenelton2024.pages.dev)
- **GitHub**: [https://github.com/boehnenelton](https://github.com/boehnenelton)
