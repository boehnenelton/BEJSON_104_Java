#!/usr/bin/env bash
# File Name:       run_parity.sh
# Relative Path:   dev/parity/run_parity.sh
# Description:     Builds the Java core, generates the Python-oracle fixtures from the frozen Lib_PY/Core reference, and runs every Java parity driver.
# VERSION:         1.0.0
# Release_Version: 300
# Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
# RELATIONAL_ID:   ab9f3c25-8555-4d15-88bc-bd80dee8b216
# Usage:           dev/parity/run_parity.sh <path-to-libraries_104/Lib_PY/Core>
set -euo pipefail
LIB_PY_CORE="${1:?usage: run_parity.sh <path to libraries_104/Lib_PY/Core>}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="$(mktemp -d)"
export LC_ALL=C.UTF-8   # JVM file-name encoding must be UTF-8 (always true on Android)
cd "$ROOT"
mkdir -p build/classes build/test
javac --release 8 -Xlint:all,-options,-serial -d build/classes $(find src/main -name '*.java')
javac --release 8 -Xlint:-options -cp build/classes -d build/test $(find src/test -name '*.java')
python3 "$HERE/parity_py_json.py" "$OUT" >/dev/null
for o in a b c d e f g; do python3 "$HERE/parity_py_$o.py" "$LIB_PY_CORE" "$OUT" >/dev/null 2>&1; done
fail=0
java -cp build/classes:build/test com.boehnenelton.bejson.core.HardeningTests 2>&1 | grep -E "FAIL|passed" || fail=1
for t in ParityJson ParityA ParityB ParityC ParityD ParityE ParityF ParityG; do
  java -cp build/classes:build/test "com.boehnenelton.bejson.core.$t" "$OUT" 2>&1 | grep -E "FAIL|DIFF|passed" || fail=1
done
rm -rf "$OUT"
exit $fail
