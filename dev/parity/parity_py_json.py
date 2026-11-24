# File Name:       parity_py_json.py
# Relative Path:   dev/parity/parity_py_json.py
# Description:     Python-oracle script for Java parity tests (runs against the frozen Lib_PY/Core reference; frozen libs are not modified).
# VERSION:         1.0.0
# Release_Version: 300
# Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
# RELATIONAL_ID:   2d88c36f-0968-4582-a168-09ca86657b4f
"""Parity oracle part 1: JSON layer. Usage: python3 parity_py_json.py <outdir>
Writes <outdir>/json_inputs.json and <outdir>/json_expected.json (frozen Python reference behaviour)."""
import json, sys, os
out = sys.argv[1]
os.makedirs(out, exist_ok=True)
valid = [
 '{"a":1,"b":[1,2.5,1e10,1E-7,-0,-0.0,123456789012345678901234567890,true,false,null,"\\u00e9\\ud83d\\ude00\\n\\t\\u0001\\u007f"]}',
 '[]', '{}', '"x"', '1.0', '1e400', '[NaN, Infinity, -Infinity]', '[1e16,1e15,0.0001,0.00001,123456789.123456789,5e-324,1.7976931348623157e308,0.30000000000000004,100.0,1e22,1.5e300,2.5e-10]',
 ' [1 , 2 ] ', '{"a":{"b":{"c":[]}},"d":{}}', '"\\ud800"', '{"k":1,"k":2}', '[0.1,0.2,0.5,1e-5,12345678901234567890.0,9007199254740993]',
 '{"\\u00e9":"é","emoji":"😀","nul":"\\u0000"}', '-0', '[1E2,1e+2,1E-2]', '9223372036854775807', '9223372036854775808', '-9223372036854775808', '-9223372036854775809',
 '[3.141592653589793,2.718281828459045,1e-7,1.0e-6,0.000123]',
]
invalid = ['', '{', '[1,]', '{"a":1,}', '{"a" 1}', '[1 2]', '"abc', '"\\x"', '{"a":01}', 'nul', '{1:2}', '[1] x', '"a\nb"', 'tru', '-', '1.', '.5', '{"a":}', '[', '{"a"', '"\\u12"', '   ', '[1,2', '{"a":1 "b":2}', 'NaN1', '+1']
exp = {"valid": [], "invalid": []}
for t in valid:
    v = json.loads(t)
    exp["valid"].append({"indent2": json.dumps(v, indent=2), "compact": json.dumps(v), "noascii": json.dumps(v, indent=2, ensure_ascii=False), "sorted": json.dumps(v, sort_keys=True), "str": str(v), "repr": repr(v)})
for t in invalid:
    try:
        json.loads(t); exp["invalid"].append("NO ERROR")
    except Exception as e:
        exp["invalid"].append(str(e))
json.dump({"valid": valid, "invalid": invalid}, open(os.path.join(out, "json_inputs.json"), "w"))
json.dump(exp, open(os.path.join(out, "json_expected.json"), "w"))
print("json oracle:", len(valid), "valid,", len(invalid), "invalid")
