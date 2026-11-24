# File Name:       parity_py_e.py
# Relative Path:   dev/parity/parity_py_e.py
# Description:     Python-oracle script for Java parity tests (runs against the frozen Lib_PY/Core reference; frozen libs are not modified).
# VERSION:         1.0.0
# Release_Version: 300
# Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
# RELATIONAL_ID:   57eacfdd-7f8d-4107-bc0f-c4da78d3c40e
"""Parity oracle E: path_guard + env path resolution. Usage: python3 parity_py_e.py <libdir> <outdir>"""
import sys, os, json, logging
lib, out = sys.argv[1], sys.argv[2]
sys.path.insert(0, lib); logging.disable(logging.CRITICAL)
os.environ["HOME"] = "/home/tester"
os.environ["PG_TEST_VAR"] = "/opt/pg"
os.environ.pop("PG_UNSET_VAR", None)
import lib_bejson_Core_bejson_path_guard as G
import lib_bejson_Core_bejson_env as E
def att(fn):
    try: return fn()
    except Exception as e: return {"EXC": type(e).__name__, "msg": str(e)}
joins = [["/base", "a.txt"], ["/base", "sub/../a.txt"], ["/base", "../x"], ["/base", ".."], ["/base", "/abs"], ["/base", "a", "b", "c"], ["/base", ""], ["/base/", "x//y"],
         ["/base", "a/./b"], ["/base", "..hidden"], ["/base", "a/../../b"], ["/base", "x", "../../y"], ["/base"], ["/", "a"], ["/", ".."], ["/base", "\u00e9/\u4e2d"], ["/base", "a\\..\\b"], ["/base/sub/..", "x"], ["rel", "a"], ["/base", "~"]]
paths = ["/storage/emulated/0/x", "~/x", "$HOME/y", "${HOME}/y", "$PG_TEST_VAR/z", "$PG_UNSET_VAR/z", "relative/p", "", "/a/../b", "/a//b/", "./c", "~user/x", "%HOME%/x", "$", "$$", "${", "/x/$PG_TEST_VAR", "~", "/storage/9C33-6BBD/x"]
esc = ["a/b", "../a", "a/../../b", "/abs", "a/..", "..", ".", "", "a/./b", "a\\..\\..\\b", "./..", "a/b/../../c", "a/b/../../../c", "\u00e9/../..", "C:\\x", "..\\x", "a//..//..", "..."]
exp = {"join": [att(lambda: G.bejson_safe_join(*j)) for j in joins],
       "storage": [att(lambda: G.resolve_storage_path(p)) for p in paths],
       "resolve": [att(lambda: E.resolve_path(p)) for p in paths],
       "envpath": [att(lambda: E.get_env_path(v, d)) for v, d in [("PG_TEST_VAR", "/d"), ("PG_UNSET_VAR", "/d"), ("PG_UNSET_VAR", "~/q"), ("PG_UNSET_VAR", "$PG_TEST_VAR/q")]]}
exp["esc"] = [att(lambda: G._bejson_mfdb_escapes_root(p)) for p in esc]
json.dump({"joins": joins, "paths": paths, "esc": esc}, open(os.path.join(out, "e_inputs.json"), "w"))
json.dump(exp, open(os.path.join(out, "e_expected.json"), "w"), sort_keys=True)
print("oracle E ok")
