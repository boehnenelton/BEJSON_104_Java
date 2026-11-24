# File Name:       parity_py_g.py
# Relative Path:   dev/parity/parity_py_g.py
# Description:     Differential fuzz oracle for the MFDB validator: mutated manifests/entity files on disk, validated by the frozen Python reference.
# VERSION:         1.0.0
# Release_Version: 300
# Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
# RELATIONAL_ID:   5e91b3c7-0a4d-4f28-9c6e-2d7a8b1f4e03
"""Usage: python3 parity_py_g.py <libdir> <outdir> -> <outdir>/gfx/case_NNNN/{104a.mfdb.bejson,data/*.bejson}, g_expected.json"""
import sys, os, json, random, copy, shutil, logging
lib, out = sys.argv[1], sys.argv[2]
sys.path.insert(0, lib); logging.disable(logging.CRITICAL)
import lib_bejson_Core_mfdb_core as M
import lib_bejson_Core_mfdb_validator as MV
rnd = random.Random(20261123)
G = os.path.join(out, "gfx"); shutil.rmtree(G, ignore_errors=True); os.makedirs(G)
F = lambda *a: [{"name": n, "type": t} for n, t in a]
BASE = os.path.join(G, "_base")
mp = M.mfdb_core_create_database(BASE, "Shop", [
    {"name": "Customer", "fields": F(("id", "integer"), ("name", "string")), "primary_key": "id"},
    {"name": "Order", "fields": F(("oid", "integer"), ("cust_id", "integer"))}], db_description="d")
for r in [[1, "A"], [2, "B"]]: M.mfdb_core_add_entity_record(mp, "Customer", r)
for r in [[10, 1], [11, 2]]: M.mfdb_core_add_entity_record(mp, "Order", r)
JUNK = [None, True, False, 0, -1, 7, 1.5, "", "x", "Master", "Slave", "1.31", "data/customer.bejson", "../x", "/abs/p", [], [1], {}, {"a": 1}, "mfdb", ["mfdb"], "104a", "104", "../104a.mfdb.bejson", "Customer", "Order"]
def walk(o, path=()):
    yield path, o
    if isinstance(o, dict):
        for k, v in o.items(): yield from walk(v, path + (k,))
    elif isinstance(o, list):
        for i, v in enumerate(o): yield from walk(v, path + (i,))
def mut(d):
    d = copy.deepcopy(d)
    for _ in range(rnd.randint(1, 3)):
        paths = [p for p, _ in walk(d) if p]
        p = rnd.choice(paths); cur = d
        try:
            for q in p[:-1]: cur = cur[q]
            op = rnd.random()
            if op < 0.6: cur[p[-1]] = copy.deepcopy(rnd.choice(JUNK))
            elif op < 0.85:
                if isinstance(cur, dict): cur.pop(p[-1], None)
                else: del cur[p[-1]]
            else:
                if isinstance(cur, list): cur.append(copy.deepcopy(rnd.choice(JUNK)))
        except Exception: pass
    return d
files = {"m": "104a.mfdb.bejson", "c": "data/customer.bejson", "o": "data/order.bejson"}
docs = {k: json.load(open(os.path.join(BASE, v))) for k, v in files.items()}
def R(r): return {"valid": r.valid, "errors": r.errors, "warnings": r.warnings}
def att(fn):
    try: return fn()
    except Exception as e: return {"EXC": type(e).__name__, "msg": str(e)}
exp = {}
N = 700
for n in range(N):
    d = os.path.join(G, "case_%04d" % n); shutil.copytree(BASE, d)
    target = rnd.choice(["m", "c", "o", "m", "c"])
    nd = mut(docs[target])
    json.dump(nd, open(os.path.join(d, files[target]), "w"), indent=2)
    if rnd.random() < 0.1: os.remove(os.path.join(d, files[rnd.choice(["c", "o"])])) if False else None
    m = os.path.join(d, "104a.mfdb.bejson")
    exp["case_%04d" % n] = {"target": target,
        "manifest": att(lambda: R(MV.validate_mfdb_manifest(m))), "db": att(lambda: R(MV.validate_mfdb_database(m))),
        "db_strict": att(lambda: R(MV.validate_mfdb_database(m, strict_fk=True))),
        "ent_c": att(lambda: R(MV.validate_mfdb_entity_file(os.path.join(d, "data/customer.bejson")))),
        "ent_o": att(lambda: R(MV.validate_mfdb_entity_file(os.path.join(d, "data/order.bejson"), check_bidirectional=False)))}
shutil.rmtree(BASE)
def fix(o):
    return json.loads(json.dumps(o).replace(G, "<G>"))
json.dump(fix(exp), open(os.path.join(out, "g_expected.json"), "w"))
print("oracle G:", N, "cases;", sum(1 for v in exp.values() if isinstance(v["db"], dict) and v["db"].get("valid")), "valid dbs")
