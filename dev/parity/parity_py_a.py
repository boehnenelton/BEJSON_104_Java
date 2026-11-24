# File Name:       parity_py_a.py
# Relative Path:   dev/parity/parity_py_a.py
# Description:     Python-oracle script for Java parity tests (runs against the frozen Lib_PY/Core reference; frozen libs are not modified).
# VERSION:         1.0.0
# Release_Version: 300
# Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
# RELATIONAL_ID:   a057a3fd-7b41-460c-893a-6447e71bcce8
"""Parity oracle A: validator, core, schema. Usage: python3 parity_py_a.py <libdir> <outdir>
Writes <outdir>/a_inputs.json (corpus for Java) and <outdir>/a_expected.json (Python reference results)."""
import sys, os, json, copy
lib, out = sys.argv[1], sys.argv[2]
sys.path.insert(0, lib)
os.makedirs(out, exist_ok=True)
import lib_bejson_Core_bejson_validator as V
import lib_bejson_Core_bejson_core as C
import lib_bejson_Core_bejson_schema as S

F = lambda *a: [{"name": n, "type": t} for n, t in a]
base104 = {"Format":"BEJSON","Format_Version":"104","Format_Creator":"Elton Boehnen","Records_Type":["T"],"Fields":F(("id","integer"),("name","string"),("score","number"),("ok","boolean")),"Values":[[1,"a",1.5,True],[2,"b",2,False],[3,None,None,None]]}
base104a = dict(base104, Format_Version="104a", Custom_Header="x")
base104db = {"Format":"BEJSON","Format_Version":"104db","Format_Creator":"Elton Boehnen","Records_Type":["A","B"],"Fields":[{"name":"Record_Type_Parent","type":"string"},{"name":"x","type":"string","Record_Type_Parent":"A"},{"name":"y","type":"integer","Record_Type_Parent":"B"}],"Values":[["A","hi",None],["B",None,5]]}
def mod(d, **kw):
    d = copy.deepcopy(d); d.update(kw); return d
def drop(d, k):
    d = copy.deepcopy(d); d.pop(k); return d
cases = {}
cases["ok104"] = base104; cases["ok104a"] = base104a; cases["ok104db"] = base104db
for k in ["Format","Format_Version","Format_Creator","Records_Type","Fields","Values"]:
    cases["missing_"+k] = drop(base104, k)
cases["bad_format"] = mod(base104, Format="JSON")
cases["bad_creator"] = mod(base104, Format_Creator="Someone")
cases["bad_version"] = mod(base104, Format_Version="105")
cases["version_num"] = mod(base104, Format_Version=104)
cases["rt_not_list"] = mod(base104, Records_Type="T")
cases["rt_two_104"] = mod(base104, Records_Type=["A","B"])
cases["rt_one_104db"] = mod(base104db, Records_Type=["A"])
cases["rt_zero_104a"] = mod(base104a, Records_Type=[])
cases["db_first_field_wrong"] = mod(base104db, Fields=[{"name":"x","type":"string","Record_Type_Parent":"A"}]+base104db["Fields"][1:])
cases["db_bad_rtp"] = mod(base104db, Values=[["Z","hi",None]])
cases["db_empty_row_ok"] = mod(base104db, Values=[[],["A","hi",None]])
cases["db_field_missing_rtp"] = mod(base104db, Fields=[base104db["Fields"][0], {"name":"x","type":"string"}, base104db["Fields"][2]])
cases["field_no_name"] = mod(base104, Fields=[{"type":"string"}])
cases["field_no_type"] = mod(base104, Fields=[{"name":"a"}])
cases["field_bad_type"] = mod(base104, Fields=F(("a","String")), Values=[])
cases["field_bad_type_float"] = mod(base104, Fields=F(("a","float")), Values=[])
cases["a104_array"] = mod(base104a, Fields=F(("a","array")), Values=[])
cases["a104_object"] = mod(base104a, Fields=F(("a","object")), Values=[])
cases["ok104_array_object"] = mod(base104, Fields=F(("a","array"),("b","object")), Values=[[[1],{"k":1}],[None,None]])
cases["dup_names"] = mod(base104, Fields=F(("a","string"),("a","string")), Values=[["x","y"]])
cases["row_short"] = mod(base104, Values=[[1,"a",1.5]])
cases["row_long"] = mod(base104, Values=[[1,"a",1.5,True,9]])
cases["t_string"] = mod(base104, Values=[[1,5,1.5,True]])
cases["t_integer_str"] = mod(base104, Values=[["1","a",1.5,True]])
cases["t_integer_float"] = mod(base104, Values=[[1.5,"a",1.5,True]])
cases["t_integer_bool"] = mod(base104, Values=[[True,"a",1.5,True]])
cases["t_number_bool"] = mod(base104, Values=[[1,"a",True,True]])
cases["t_number_str"] = mod(base104, Values=[[1,"a","x",True]])
cases["t_boolean_int"] = mod(base104, Values=[[1,"a",1.5,1]])
cases["t_array"] = mod(base104, Fields=F(("a","array")), Values=[["x"]])
cases["t_object"] = mod(base104, Fields=F(("a","object")), Values=[[[1]]])
cases["custom_104"] = mod(base104, Extra="x")
cases["custom_104db"] = mod(base104db, Extra="x")
cases["custom_104a_ok"] = mod(base104a, Extra="x", Another=[1])
cases["underscore_key"] = mod(base104a, _hidden=1)
cases["parent_hierarchy_ok"] = mod(base104, Parent_Hierarchy="../104a.mfdb.bejson")
cases["empty_values"] = mod(base104, Values=[])
cases["empty_fields_values"] = mod(base104, Fields=[], Values=[])
cases["rows_not_lists"] = mod(base104, Values=["x"])
cases["fields_not_list"] = mod(base104, Fields="x")
cases["fields_entries_not_dict"] = mod(base104, Fields=["x"])
cases["unicode"] = mod(base104, Values=[[1,"é😀",1.5,True]])
strings = {k: json.dumps(v) for k, v in cases.items()}
strings["str_with_cache"] = json.dumps(mod(base104, _bejson_field_map={"id":0,"name":1,"score":2,"ok":3}))
strings["str_invalid"] = "{"
strings["str_list"] = "[1,2]"
strings["str_num"] = "5"
strings["str_empty"] = ""
dicts = dict(cases)
dicts["dict_cache_ok"] = mod(base104, _bejson_field_map={"id":0,"name":1,"score":2,"ok":3})
dicts["dict_cache_stale"] = mod(base104, _bejson_field_map={"id":1,"name":0,"score":2,"ok":3})
dicts["dict_cache_short"] = mod(base104, _bejson_field_map={"id":0})
dicts["dict_nesting_cache"] = mod(base104a, _nesting_field_map={})
dicts["dict_other_underscore"] = mod(base104a, _other=1)
dicts["dict_cache_notdict"] = mod(base104, _bejson_field_map=[1])

def res(r): return {"valid": r.valid, "errors": r.errors, "warnings": r.warnings}
exp = {"string": {}, "dict": {}, "report": {}, "validate_string": {}}
for k, s in strings.items(): exp["string"][k] = res(V.validate_bejson(s))
for k, d in dicts.items(): exp["dict"][k] = res(V.validate_bejson(copy.deepcopy(d)))
exp["report"]["ok"] = V.bejson_validator_get_report(strings["ok104"])
exp["report"]["bad"] = V.bejson_validator_get_report(strings["row_short"])
for k in ["ok104", "row_short", "str_invalid"]:
    try: exp["validate_string"][k] = V.bejson_validator_validate_string(strings[k])
    except V.BEJSONValidationError as e: exp["validate_string"][k] = ["EXC", e.code, str(e)]

# ---- core ops (fresh doc each)
def core_ops():
    r = {}
    d = C.bejson_core_create_104("T", F(("id","integer"),("name","string")), [[1,"a"],[2,"b"]])
    r["create104"] = copy.deepcopy(d)
    r["create104a"] = C.bejson_core_create_104a("T", F(("id","integer")), [[1]], Custom_Key="v", Other=2)
    r["create104db"] = C.bejson_core_create_104db(["A","B"], base104db["Fields"], base104db["Values"])
    fm = C.bejson_core_get_field_map(d); r["field_map"] = dict(fm); r["field_map_type"] = type(fm).__name__
    r["field_index"] = [C.bejson_core_get_field_index(d,"id"), C.bejson_core_get_field_index(d,"name"), C.bejson_core_get_field_index(d,"zzz")]
    r["fresh_after_get"] = C.bejson_core_field_map_is_fresh(d["_bejson_field_map"], d["Fields"])
    d2 = copy.deepcopy(d); d2.pop("_bejson_field_map", None)
    d2["_bejson_field_map"] = {"id":0,"name":1}
    r["forged_rebuilt"] = dict(C.bejson_core_get_field_map(d2))
    r["forged_is_engine_map"] = type(d2["_bejson_field_map"]).__name__
    d3 = C.bejson_core_create_104("T", F(("a","string"),("b","string"),("c","string")), [])
    C.bejson_core_get_field_map(d3); d3["Fields"].append({"name":"d","type":"string"})
    r["stale_after_append"] = dict(C.bejson_core_get_field_map(d3))
    r["empty_fields_map"] = C.bejson_core_get_field_map({"Fields": []})
    r["add_ok"] = C.bejson_core_add_record(d, [3,"c"]); r["add_bad"] = C.bejson_core_add_record(d, [3])
    r["count"] = C.bejson_core_get_record_count(d)
    r["update_ok"] = C.bejson_core_update_field(d, 0, "name", "A"); r["update_nofield"] = C.bejson_core_update_field(d, 0, "zz", 1); r["update_badrow"] = C.bejson_core_update_field(d, 9, "name", 1)
    r["filter"] = C.bejson_core_filter_rows(d, "name", "b"); r["filter_nofield"] = C.bejson_core_filter_rows(d, "q", 1)
    dd = C.bejson_core_create_104("T", F(("v","integer")), [[1],[True],[1.0],[2]])
    r["filter_pyeq"] = C.bejson_core_filter_rows(dd, "v", 1)
    r["remove_ok"] = C.bejson_core_remove_record(d, 0); r["remove_bad"] = C.bejson_core_remove_record(d, 10)
    ds = C.bejson_core_create_104("T", F(("n","string"),("k","integer")), [["b",2],[None,1],["a",2],["c",1]])
    C.bejson_core_sort_by_field(ds, "k"); r["sort_k"] = copy.deepcopy(ds["Values"])
    C.bejson_core_sort_by_field(ds, "k", reverse=True); r["sort_k_rev"] = copy.deepcopy(ds["Values"])
    C.bejson_core_sort_by_field(ds, "n"); r["sort_n"] = copy.deepcopy(ds["Values"])
    C.bejson_core_sort_by_field(ds, "nope"); 
    r["is_valid"] = [C.bejson_core_is_valid(base104), C.bejson_core_is_valid({"Format":1})]
    r["version"] = [C.bejson_core_get_version(base104a), C.bejson_core_get_version({})]
    r["stats"] = C.bejson_core_get_stats(base104)
    return r
exp["core"] = core_ops()
# ---- file ops
import tempfile
td = tempfile.mkdtemp()
p = os.path.join(td, "sub", "x.bejson")
doc = copy.deepcopy(base104); doc["Values"].append([4, "é😀\u007f", 0.1, True]); C.bejson_core_get_field_map(doc); doc["_other"] = 5
exp["atomic_write_ok"] = C.bejson_core_atomic_write(p, doc)
exp["atomic_write_bytes"] = open(p, "rb").read().decode("utf-8")
exp["atomic_write_listing"] = sorted(os.listdir(os.path.dirname(p)))
bad = {"a": {1, 2}}
exp["atomic_write_unserializable"] = C.bejson_core_atomic_write(os.path.join(td, "y.bejson"), bad)
exp["atomic_write_leftovers"] = sorted(os.listdir(td))
w = os.path.join(td, "cache.bejson"); open(w, "w").write(json.dumps(mod(base104, _bejson_field_map={"id": 0})))
ld = C.bejson_core_load_file(w); exp["load_strips_cache"] = "_bejson_field_map" not in ld
exp["load_missing"] = C.bejson_core_load_file(os.path.join(td, "nope.json"))
open(os.path.join(td, "bad.json"), "w").write("{")
exp["load_bad"] = C.bejson_core_load_file(os.path.join(td, "bad.json"))
exp["load_string"] = [C.bejson_core_load_string('{"a":1}'), C.bejson_core_load_string("{")]
exp["validate_file_ok"] = V.validate_bejson(p, is_file=True).valid
exp["validate_file_missing"] = res(V.validate_bejson(os.path.join(td, "none.json"), is_file=True))
# ---- schema
exp["schema"] = {"extract": S.bejson_schema_extract(base104a), "validate_ok": S.bejson_schema_validate_against(base104, base104["Fields"]),
                 "validate_bad": S.bejson_schema_validate_against(base104, F(("id","integer"))), "validate_type": S.bejson_schema_validate_against(base104, F(("id","string"),("name","string"),("score","number"),("ok","boolean"))),
                 "consts": {n: getattr(S, n) for n in dir(S) if n.startswith("SCHEMA_")}}
json.dump({"strings": strings, "dicts": dicts}, open(os.path.join(out, "a_inputs.json"), "w"))
json.dump(exp, open(os.path.join(out, "a_expected.json"), "w"), sort_keys=True)
print("oracle A:", len(strings), "string cases,", len(dicts), "dict cases")
