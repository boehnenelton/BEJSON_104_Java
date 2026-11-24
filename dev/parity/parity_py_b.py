# File Name:       parity_py_b.py
# Relative Path:   dev/parity/parity_py_b.py
# Description:     Python-oracle script for Java parity tests (runs against the frozen Lib_PY/Core reference; frozen libs are not modified).
# VERSION:         1.0.0
# Release_Version: 300
# Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
# RELATIONAL_ID:   811466e3-d743-40bd-b531-888f25e8dcaa
"""Parity oracle B: MFDB flows. Usage: python3 parity_py_b.py <libdir> <outdir>
Runs a deterministic scenario with the frozen Python lib; writes <outdir>/b_expected.json
(step -> normalized result + normalized snapshot of every file in the DB dir)."""
import sys, os, json, re, tempfile, shutil, logging
lib, out = sys.argv[1], sys.argv[2]
sys.path.insert(0, lib)
logging.disable(logging.CRITICAL)
os.makedirs(out, exist_ok=True)
import lib_bejson_Core_mfdb_core as M
import lib_bejson_Core_mfdb_validator as MV

ROOT = tempfile.mkdtemp(prefix="parityB")
UUID = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
TS = re.compile(r"\d{8}T\d{6}Z|\d{4}-\d\d-\d\d[T ][0-9:.]+(?:Z|[+-]\d\d:\d\d)?")
_TS_OLD = re.compile(r"\d{4}-\d\d-\d\d[T ][0-9:.]+(?:Z|[+-]\d\d:\d\d)?")
def norm(text):
    text = text.replace(ROOT, "<ROOT>")
    text = UUID.sub("<UUID>", text); text = TS.sub("<TS>", text)
    text = re.sub(r'"(duration_ms|elapsed_ms|duration)": [0-9.eE+-]+', r'"\1": 0', text)
    text = re.sub(r'"(pid|timestamp)": [0-9.]+', r'"\1": 0', text)
    return text
def snap(d):
    r = {}
    for dp, _, fs in os.walk(d):
        for f in sorted(fs):
            p = os.path.join(dp, f)
            rel = os.path.relpath(p, d)
            if f == "lock_meta.json" or ".lock" in rel: continue
            txt = open(p, encoding="utf-8").read()
            if f.startswith("meta-"):
                dd = json.loads(txt); fm = {x["name"]: i for i, x in enumerate(dd["Fields"])}
                for row in dd["Values"]:
                    for c in ("pid", "duration_ms"):
                        if c in fm: row[fm[c]] = 0
                txt = json.dumps(dd, indent=2)
            r[norm(rel)] = norm(txt)
    return r
steps = {}
def rec(name, val, d=None):
    steps[name] = {"r": json.loads(norm(json.dumps(val, default=str, sort_keys=True))), "files": snap(d) if d else None}
def attempt(fn):
    try: return fn()
    except Exception as e: return {"EXC": type(e).__name__, "msg": str(e), "code": getattr(e, "code", None)}

F = lambda *a: [{"name": n, "type": t} for n, t in a]
DB = os.path.join(ROOT, "db")
mp = M.mfdb_core_create_database(DB, "Shop", [
    {"name": "Customer", "fields": F(("id","integer"),("name","string"),("email","string")), "description": "customers", "primary_key": "id"},
    {"name": "Order", "fields": F(("oid","integer"),("cust_id","integer"),("total","number"))},
], db_description="demo")
rec("create_database", os.path.relpath(mp, ROOT), DB)
for r_ in [[1,"Ann","a@x"],[2,"Bob",None]]: M.mfdb_core_add_entity_record(mp, "Customer", r_)
for r_ in [[10,1,5.5],[11,1,7],[12,3,1]]: M.mfdb_core_add_entity_record(mp, "Order", r_)
rec("populated", None, DB)
rec("validate_db", [type(x).__name__ for x in [MV.mfdb_validator_validate_database(mp)]] if hasattr(MV, "mfdb_validator_validate_database") else "n/a")
rec("discover", M.mfdb_core_discover(mp))
rec("discover_entity", M.mfdb_core_discover(os.path.join(DB, "data", "customer.bejson")))
rec("load_manifest", M.mfdb_core_load_manifest(mp))
rec("load_entity", M.mfdb_core_load_entity(mp, "Customer"))
rec("entity_doc", M.mfdb_core_get_entity_doc(mp, "Order"))
rec("stats", M.mfdb_core_get_stats(mp))
rec("query", M.mfdb_core_query_entity(mp, "Order", lambda r: r["cust_id"] == 1))
rec("index", M.mfdb_core_build_index(mp, "Customer", "id"))
rec("join", M.mfdb_core_join(mp, "Order", "Customer", "cust_id", "id"))
rec("load_unknown", attempt(lambda: M.mfdb_core_load_entity(mp, "Nope")))
rec("add", M.mfdb_core_add_entity_record(mp, "Customer", [3, "Cy", "c@x"]), DB)
rec("add_bad_len", attempt(lambda: M.mfdb_core_add_entity_record(mp, "Customer", [4])), DB)
rec("add_nosync", M.mfdb_core_add_entity_record(mp, "Customer", [4, "Di", None], sync_count=False), DB)
rec("sync_one", M.mfdb_core_sync_manifest_count(mp, "Customer"), DB)
rec("update", M.mfdb_core_update_entity_record(mp, "Customer", 0, "name", "Anne"), DB)
rec("update_nofield", attempt(lambda: M.mfdb_core_update_entity_record(mp, "Customer", 0, "zzz", 1)), DB)
rec("update_badidx", attempt(lambda: M.mfdb_core_update_entity_record(mp, "Customer", 99, "name", "x")), DB)
rec("update_bulk", M.mfdb_core_update_entity_record_bulk(mp, "Customer", 1, {"name": "Rob", "email": "r@x"}), DB)
rec("remove", M.mfdb_core_remove_entity_record(mp, "Customer", 2), DB)
rec("remove_badidx", attempt(lambda: M.mfdb_core_remove_entity_record(mp, "Customer", 50)), DB)
rec("create_entity", M.mfdb_core_create_entity_file(mp, "Product", F(("sku","string"),("price","number")), description="prods", primary_key="sku"), DB)
rec("add_product", M.mfdb_core_add_entity_record(mp, "Product", ["S1", 9.99]), DB)
rec("sync_all", M.mfdb_core_sync_all_counts(mp), DB)
rec("deep_verify_clean", M.mfdb_core_deep_verify(mp))
# tamper: wrong count in manifest + damaged entity
doc = json.load(open(mp)); 
for row in doc["Values"]:
    if row[0] == "Order": row[3] = 99
json.dump(doc, open(mp, "w"), indent=2)
rec("deep_verify_tampered", M.mfdb_core_deep_verify(mp))
rec("self_heal", M.mfdb_core_self_heal(mp), DB)
rec("deep_verify_after", M.mfdb_core_deep_verify(mp))
# missing entity file
os.remove(os.path.join(DB, "data", "product.bejson"))
rec("deep_verify_missing", M.mfdb_core_deep_verify(mp))
rec("self_heal_missing", attempt(lambda: M.mfdb_core_self_heal(mp)), DB)
# slave / debug / drift
rec("resolve_path", [M.mfdb_core_resolve_path("data/x.bejson"), M.mfdb_core_resolve_path("/abs/x")])
rec("slave", os.path.relpath(M.mfdb_core_create_connected_slave_entity(mp), ROOT), DB)
dbg = M.mfdb_core_enable_debug(mp, row_cap=5, debug_reads=True)
rec("enable_debug", os.path.relpath(dbg, ROOT) if isinstance(dbg, str) and dbg.startswith(ROOT) else "meta", DB)
M.mfdb_core_load_entity(mp, "Customer")
M.mfdb_core_add_entity_record(mp, "Customer", [7, "Eve", None])
attempt(lambda: M.mfdb_core_add_entity_record(mp, "Customer", [1]))
attempt(lambda: M.mfdb_core_update_entity_record(mp, "Customer", 0, "zzz", 1))
log = M.mfdb_core_get_debug_log(mp)
rec("debug_log", [ {k: v for k, v in e.items() if k not in ("timestamp", "duration_ms")} for e in log])
rec("failed_ops", [ {k: v for k, v in e.items() if k not in ("timestamp", "duration_ms")} for e in M.mfdb_core_get_failed_ops(mp)])
_ds = M.mfdb_core_debug_summary(mp); _ds["top_3_slowest"] = len(_ds["top_3_slowest"])  # durations are non-deterministic
rec("debug_summary", _ds)
rec("clear_log", M.mfdb_core_clear_debug_log(mp), DB)
for i in range(8): M.mfdb_core_add_entity_record(mp, "Customer", [100+i, "n%d" % i, None])
rec("rowcap", len(M.mfdb_core_get_debug_log(mp)))
# drift: add a field to Customer schema in the entity file
cp = os.path.join(DB, "data", "customer.bejson"); cd = json.load(open(cp))
cd["Fields"].append({"name": "age", "type": "integer"})
for r in cd["Values"]: r.append(None)
json.dump(cd, open(cp, "w"), indent=2)
rec("drift", M.mfdb_core_detect_schema_drift(mp))
M.mfdb_core_disable_debug(mp)
rec("disable_debug", None, DB)
rec("drift_after_disable", attempt(lambda: M.mfdb_core_detect_schema_drift(mp)))
# federation
disp = os.path.join(ROOT, "drop"); os.makedirs(disp)
rec("push_config", M.mfdb_federation_push_config({"a": 1, "b": [1, 2]}, os.path.join(disp, "cfg.json")), disp)
json.dump({"hello": "world"}, open(os.path.join(disp, "m1.bejson"), "w"))
open(os.path.join(disp, "bad.bejson"), "w").write("{")
got = []
rec("poll", M.mfdb_federation_poll_dropzone(disp, lambda p, d: got.append((os.path.basename(p), d)), poll_interval=0.01, timeout=0.05), disp)
rec("poll_cb", got)
MASTER = os.path.join(ROOT, "master"); os.makedirs(MASTER)
rec("distill", M.mfdb_federation_distill_logs(mp, "data/customer.bejson", MASTER, max_rows=3), MASTER)
rec("distill_nothing", M.mfdb_federation_distill_logs(mp, "data/customer.bejson", MASTER, max_rows=500))
rec("distill_after_entity", None, DB)
json.dump(steps, open(os.path.join(out, "b_expected.json"), "w"), sort_keys=True)
json.dump({"ROOT": ROOT}, open(os.path.join(out, "b_meta.json"), "w"))
shutil.rmtree(ROOT)
print("oracle B steps:", len(steps))
