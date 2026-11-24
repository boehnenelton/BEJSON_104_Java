# File Name:       parity_py_c.py
# Relative Path:   dev/parity/parity_py_c.py
# Description:     Python-oracle script for Java parity tests (runs against the frozen Lib_PY/Core reference; frozen libs are not modified).
# VERSION:         1.0.0
# Release_Version: 300
# Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
# RELATIONAL_ID:   84b2ee00-91a0-45ba-ac3b-55f93b77ce77
"""Parity oracle C: nested-104 scan/mutate/query. Usage: python3 parity_py_c.py <libdir> <outdir>
Writes c_inputs.json and c_expected.json."""
import sys, os, json, copy, logging
lib, out = sys.argv[1], sys.argv[2]
sys.path.insert(0, lib); logging.disable(logging.CRITICAL)
import lib_bejson_Core_bejson_core_nesting as N

F = lambda *a: [{"name": n, "type": t} for n, t in a]
def d104(rt, fields, values, ver="104"):
    return {"Format": "BEJSON", "Format_Version": ver, "Format_Creator": "Elton Boehnen", "Records_Type": [rt], "Fields": fields, "Values": values}
INNER = d104("Items", F(("sku", "string"), ("qty", "integer")), [["a", 1], ["b", 2]])
INNER2 = d104("Items", F(("sku", "string"), ("qty", "integer")), [["c", 3]])
INNER_OTHER = d104("Items", F(("code", "string")), [["z"]])
LEAF = d104("Tags", F(("t", "string")), [["x"], ["y"]])
MID = d104("Details", F(("k", "string"), ("tags", "string")), [["k1", json.dumps(LEAF)], ["k2", None]])
def outer(items, extra=None):
    return d104("Order", F(("id", "string"), ("items", "string")), [["o1", items], ["o2", extra]])
docs = {}
docs["basic"] = outer(json.dumps(INNER))
docs["two_rows"] = outer(json.dumps(INNER), json.dumps(INNER2))
docs["schema_mismatch"] = outer(json.dumps(INNER), json.dumps(INNER_OTHER))
docs["deep"] = d104("Root", F(("id", "string"), ("details", "string")), [["r1", json.dumps(MID)]])
docs["not_json"] = outer("hello world")
docs["bad_json"] = outer("{ not json")
docs["json_not_bejson"] = outer(json.dumps({"a": 1}))
docs["bad_creator"] = outer(json.dumps(dict(INNER, Format_Creator="Someone")))
docs["dup_fields"] = outer(json.dumps(dict(INNER, Fields=F(("a", "string"), ("a", "string")), Values=[["x", "y"]])))
docs["bad_rt"] = outer(json.dumps(dict(INNER, Records_Type=["Wrong"])))
docs["nested_104a"] = outer(json.dumps(dict(INNER, Format_Version="104a")))
docs["nested_104db"] = outer(json.dumps(dict(INNER, Format_Version="104db")))
docs["row_mismatch"] = outer(json.dumps(dict(INNER, Values=[["a"]])))
docs["empty"] = d104("Empty", F(("a", "string")), [])
docs["no_strings"] = d104("N", F(("a", "integer")), [[1], [2]])
docs["list_cell"] = d104("L", F(("a", "array")), [[[1, 2]]])
docs["obj_cell"] = d104("O", F(("a", "object")), [[INNER]])
docs["104db_outer"] = {"Format": "BEJSON", "Format_Version": "104db", "Format_Creator": "Elton Boehnen", "Records_Type": ["A", "B"],
    "Fields": [{"name": "Record_Type_Parent", "type": "string"}, {"name": "x", "type": "string", "Record_Type_Parent": "A"}], "Values": [["A", json.dumps(INNER)]]}
cur = INNER
for i in range(19): cur = d104("Items", F(("n", "string")), [[json.dumps(cur)]])
docs["too_deep"] = d104("Top", F(("n", "string")), [[json.dumps(cur)]])
docs["not_dict"] = [1, 2]

def cell(c):
    return {"row": c.row, "col": c.col, "field_name": c.field_name, "depth": c.depth, "doc": copy.deepcopy(c.doc), "is_valid": c.is_valid,
            "errors": c.errors, "warnings": c.warnings, "children": [cell(x) for x in c.children]}
def res(r):
    return {"scanned": r.scanned_cells, "found": r.nested_found, "valid": r.nested_valid, "invalid": r.nested_invalid,
            "depth": r.max_depth_seen, "schema_errors": r.schema_errors, "cells": [cell(c) for c in r.cells], "errors": r.errors, "warnings": r.warnings}
def att(fn):
    try: return fn()
    except Exception as e: return {"EXC": type(e).__name__, "msg": str(e)}
def qm(ms): return [{"path": m.path, "value": m.value, "fc": m.field_chain, "rc": m.row_chain, "cc": m.col_chain} for m in ms]

MUT = {
  "add_field": lambda d: d["Fields"].append({"name": "note", "type": "string"}),
  "remove_field": lambda d: d["Fields"].pop(),
  "edit_value": lambda d: d["Values"][0].__setitem__(0, "Z"),
  "noop": lambda d: None,
  "bad_creator": lambda d: d.__setitem__("Format_Creator", "Someone"),
  "add_row": lambda d: d["Values"].append(["n", 9]),
  "short_row": lambda d: d["Values"].append(["n"]),
  "add_bad_type": lambda d: d["Fields"].append({"name": "q", "type": "bogus"}),
}
queries = [("items", None), ("items", "x"), ("items[*].sku", None), ("items[*].sku", "a"), ("items[0].qty", None), ("id", None), ("id", "o1"),
           ("details[*].tags[*].t", None), ("details[0].tags[1].t", "y"), ("nope", None), ("bad..path", None), ("", None), ("items[", None),
           ("items[*]", None), ("items[5].sku", None), ("items.sku", None), ("[0]", None), ("items[-1].sku", None), ("items[x].sku", None), ("a.b.c.d", None)]
exp = {"scan": {}, "scan_nocache": {}, "summary": {}, "fieldmap": {}, "flatten": {}, "column": {}, "query": {}, "path": {}, "qsummary": {}, "mutate": {}}
N.bejson_nesting_cache_clear()
for k, d in docs.items():
    exp["scan_nocache"][k] = att(lambda: res(N.bejson_nesting_scan(copy.deepcopy(d), use_cache=False)))
N.bejson_nesting_cache_clear()
stats = []
for k, d in docs.items():
    r = att(lambda: N.bejson_nesting_scan(copy.deepcopy(d)))
    if isinstance(r, dict): exp["scan"][k] = r; continue
    exp["scan"][k] = res(r)
    exp["summary"][k] = N.bejson_nesting_summary(r)
    exp["flatten"][k] = [[c.row, c.col, c.depth] for c in N.bejson_nesting_flatten(r)]
    exp["column"][k] = [[c.row, c.col] for c in N.bejson_nesting_get_column(r, 1)]
    if r.cells: exp["fieldmap"][k] = N.bejson_nesting_get_field_map(r.cells[0])
    stats.append([k, N.bejson_nesting_cache_stats()])
exp["cache_stats_sequence"] = stats
exp["cache_clear_returns"] = N.bejson_nesting_cache_clear()
exp["cache_stats_after_clear"] = N.bejson_nesting_cache_stats()
for k in ["basic", "two_rows", "deep", "schema_mismatch", "not_json", "104db_outer", "empty"]:
    for qi, (p, mv) in enumerate(queries):
        key = "%s|%d" % (k, qi)
        ms = att(lambda: N.bejson_nesting_query(copy.deepcopy(docs[k]), p, mv))
        exp["query"][key] = ms if isinstance(ms, dict) else qm(ms)
        if not isinstance(ms, dict): exp["qsummary"][key] = N.bejson_nesting_query_summary(ms)
        exp["path"][key] = att(lambda: N.bejson_nesting_get_path(copy.deepcopy(docs[k]), p, "DEF"))
for k in ["basic", "two_rows", "deep", "schema_mismatch"]:
    for mn, fn in MUT.items():
        d = copy.deepcopy(docs[k]); N.bejson_nesting_cache_clear()
        def go():
            r = N.bejson_nesting_scan(d, use_cache=False)
            if not r.cells: return "NOCELLS"
            N.bejson_nesting_mutate(d, r.cells[0], fn)
            return "OK"
        exp["mutate"]["%s|%s" % (k, mn)] = {"r": att(go), "doc": d}
exp["cache_misc"] = {}
json.dump({"docs": docs, "queries": queries, "mutations": list(MUT)}, open(os.path.join(out, "c_inputs.json"), "w"))
json.dump(exp, open(os.path.join(out, "c_expected.json"), "w"), sort_keys=True)
print("oracle C ok:", len(docs), "docs")
