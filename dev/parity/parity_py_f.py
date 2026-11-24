# File Name:       parity_py_f.py
# Relative Path:   dev/parity/parity_py_f.py
# Description:     Differential fuzz oracle: seeded random mutations of BEJSON documents and raw text, run through the frozen Python validator and json.loads.
# VERSION:         1.0.0
# Release_Version: 300
# Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
# RELATIONAL_ID:   7c0e9a52-41d8-4f36-b2a1-5e8d3c6f9b14
"""Usage: python3 parity_py_f.py <libdir> <outdir>  -> f_inputs.json, f_expected.json"""
import sys, os, json, random, copy, logging
lib, out = sys.argv[1], sys.argv[2]
sys.path.insert(0, lib); logging.disable(logging.CRITICAL)
import lib_bejson_Core_bejson_validator as V
rnd = random.Random(20261122)
F = lambda *a: [{"name": n, "type": t} for n, t in a]
SEEDS = [
 {"Format":"BEJSON","Format_Version":"104","Format_Creator":"Elton Boehnen","Records_Type":["T"],"Fields":F(("id","integer"),("name","string"),("score","number"),("ok","boolean")),"Values":[[1,"a",1.5,True],[2,"b",2,False],[3,None,None,None]]},
 {"Format":"BEJSON","Format_Version":"104a","Format_Creator":"Elton Boehnen","Records_Type":["T"],"Custom":"x","Fields":F(("a","string"),("b","integer")),"Values":[["x",1]]},
 {"Format":"BEJSON","Format_Version":"104db","Format_Creator":"Elton Boehnen","Records_Type":["A","B"],"Fields":[{"name":"Record_Type_Parent","type":"string"},{"name":"x","type":"string","Record_Type_Parent":"A"},{"name":"y","type":"integer","Record_Type_Parent":"B"}],"Values":[["A","hi",None],["B",None,5]]},
 {"Format":"BEJSON","Format_Version":"104","Format_Creator":"Elton Boehnen","Records_Type":["T"],"Fields":F(("a","array"),("o","object")),"Values":[[[1,{"k":2}],{"z":[]}]]},
]
JUNK = [None, True, False, 0, -1, 1, 2**70, 1.5, -0.0, "", " ", "x", "104", "BEJSON", [], [[]], {}, {"a": 1}, [None], "é😀", 1e300, "Elton Boehnen"]
def walk(o, path=()):
    yield path, o
    if isinstance(o, dict):
        for k, v in o.items(): yield from walk(v, path + (k,))
    elif isinstance(o, list):
        for i, v in enumerate(o): yield from walk(v, path + (i,))
def setp(root, path, val):
    cur = root
    for p in path[:-1]: cur = cur[p]
    cur[path[-1]] = val
def delp(root, path):
    cur = root
    for p in path[:-1]: cur = cur[p]
    if isinstance(cur, dict): cur.pop(path[-1], None)
    else: del cur[path[-1]]
def mutate(doc):
    d = copy.deepcopy(doc)
    for _ in range(rnd.randint(1, 3)):
        paths = [p for p, _ in walk(d) if p]
        if not paths: break
        p = rnd.choice(paths); op = rnd.random()
        try:
            if op < 0.4: setp(d, p, copy.deepcopy(rnd.choice(JUNK)))
            elif op < 0.6: delp(d, p)
            elif op < 0.75 and isinstance(d, dict): d["Extra_%d" % rnd.randint(0, 3)] = rnd.choice(JUNK)
            elif op < 0.85:
                cur = d
                for q in p[:-1]: cur = cur[q]
                if isinstance(cur, list): cur.insert(rnd.randint(0, len(cur)), copy.deepcopy(rnd.choice(JUNK)))
            else:
                if isinstance(d.get("Values"), list) and d["Values"]: d["Values"].append(copy.deepcopy(rnd.choice(d["Values"])))
        except Exception: pass
    return d
def texts():
    for _ in range(2500):
        yield json.dumps(mutate(rnd.choice(SEEDS)), indent=rnd.choice([None, 2]))
    base = [json.dumps(s) for s in SEEDS]
    for _ in range(1500):
        t = rnd.choice(base); op = rnd.random()
        if op < 0.3: t = t[:rnd.randint(0, len(t))]
        elif op < 0.6:
            i = rnd.randrange(len(t)); t = t[:i] + rnd.choice(['', ',', '}', ']', '"', '\\', ':', 'x', '\u0000', '\n', '1', '-', 'e', 'NaN', 'Infinity', "'", '\ud83d']) + t[i+1:]
        elif op < 0.8:
            i = rnd.randrange(len(t)); t = t[:i] + rnd.choice([' ', '\t', '\n', '\r', ' ', '﻿']) + t[i:]
        else:
            i = rnd.randrange(len(t)); t = t[:i] + rnd.choice([',]', ',}', '[,', '{,', '01', '1.', '.5', '+1', '0x1', '-', '--1', '1e', '1E+', '"\\u12"', '"\\x"', '"\n"', 'tru', 'nul', 'falsey']) + t[i:]
        yield t
ts = list(texts())
exp = []
for t in ts:
    try: loaded = json.loads(t); jr = {"ok": True}
    except Exception as e: jr = {"ok": False, "type": type(e).__name__, "msg": str(e)}
    r = V.validate_bejson(t)
    exp.append({"json": jr, "valid": r.valid, "errors": r.errors, "warnings": r.warnings})
json.dump(ts, open(os.path.join(out, "f_inputs.json"), "w"))
json.dump(exp, open(os.path.join(out, "f_expected.json"), "w"))
print("oracle F:", len(ts), "cases;", sum(1 for e in exp if e["valid"]), "valid")
