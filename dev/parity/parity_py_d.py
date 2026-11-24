# File Name:       parity_py_d.py
# Relative Path:   dev/parity/parity_py_d.py
# Description:     Python-oracle script for Java parity tests (runs against the frozen Lib_PY/Core reference; frozen libs are not modified).
# VERSION:         1.0.0
# Release_Version: 300
# Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
# RELATIONAL_ID:   251a694b-05ec-4aef-ab00-3d9686e1487c
"""Parity oracle D: chunking / MFDB-132 / MFDB validator / archives / parse. Usage: python3 parity_py_d.py <libdir> <outdir>
Writes fixtures under <outdir>/fx and d_expected.json."""
import sys, os, json, re, shutil, hashlib, logging, zipfile, copy
lib, out = sys.argv[1], sys.argv[2]
sys.path.insert(0, lib); logging.disable(logging.CRITICAL)
import lib_bejson_Core_bejson_chunking as CH
import lib_bejson_Core_mfdb_core as M
import lib_bejson_Core_mfdb_validator as MV
import lib_bejson_Core_bejson_parse as P

FX = os.path.join(out, "fx"); shutil.rmtree(FX, ignore_errors=True); os.makedirs(FX)
WORK = os.path.join(out, "work_py"); shutil.rmtree(WORK, ignore_errors=True); os.makedirs(WORK)
UUID = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
TS = re.compile(r"\d{8}T\d{6}Z|\d{4}-\d\d-\d\d[T _][0-9:.]+(?:Z|[+-]\d\d:\d\d)?|\d{8}_\d{6}")
def norm(t):
    t = t.replace(FX, "<FX>").replace(WORK, "<WORK>").replace(os.path.abspath(out), "<OUT>")
    t = UUID.sub("<UUID>", t); return TS.sub("<TS>", t)
def J(v): return json.loads(norm(json.dumps(v, default=str, sort_keys=True)))
def att(fn):
    try: return fn()
    except Exception as e: return {"EXC": type(e).__name__, "msg": norm(str(e)), "code": getattr(e, "code", None)}
def tree(d):
    r = {}
    for dp, _, fs in os.walk(d):
        for f in fs:
            p = os.path.join(dp, f)
            vol = f in (".mfdb132_lock", ".mfdb_lock", "_REPORT.txt") or f.endswith("_update.zip")
            r[os.path.relpath(p, d)] = "<volatile>" if vol else hashlib.sha256(open(p, "rb").read()).hexdigest()
    return r
def wr(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "wb").write(data if isinstance(data, bytes) else data.encode("utf-8"))
exp = {}
# ---------- project fixture
PR = os.path.join(FX, "proj")
wr(PR + "/a.py", "print('héllo')\n# ünï 😀\n")
wr(PR + "/docs/readme.md", "# Title\r\nline2\n\nend")
wr(PR + "/bin/x.bin", bytes([0, 1, 2, 255, 254, 0, 10, 13, 200]) * 20)
wr(PR + "/sub/deep/z.json", '{"k": [1, 2, 3]}')
wr(PR + "/empty.txt", "")
wr(PR + "/note.xyz", "unknown ext text")
wr(PR + "/.git/ignored.txt", "x")
wr(PR + "/node_modules/m.js", "x")
wr(PR + "/latin1.txt", b"caf\xe9\n")
exp["is_binary"] = {f: CH.bejson_core_chunking_is_binary(os.path.join(PR, f)) for f in ["a.py", "bin/x.bin", "empty.txt", "latin1.txt", "note.xyz"]}
exp["hash"] = [CH.bejson_core_chunking_hash_file_bytes(b""), CH.bejson_core_chunking_hash_file_bytes(b"abc")]
exp["bump"] = [CH.bejson_core_chunking_bump_package_version(x) for x in [None, {}, {"Package_Version": "3"}, {"Package_Version": "x"}, {"Package_Version": 7}, {"Package_Version": None}]]
def chunk(**kw): return CH.bejson_core_chunking_create_chunked_104(PR, **kw)
variants = {"default": {}, "ver": {"version": "2.0"}, "ext": {"extensions": [".py", ".md"]}, "excl": {"exclude_dirs": ["docs"]}, "pkg": {"package_version": "9"}, "all": {"extensions": [".py", ".bin", ".xyz", ".json", ".txt", ".md"], "version": "v", "package_version": "5"}}
exp["chunk"] = {}; exp["unchunk"] = {}
docs = {}
for k, kw in variants.items():
    d = att(lambda: chunk(**kw)); docs[k] = d
    exp["chunk"][k] = J(d)
    if isinstance(d, dict) and "Values" in d:
        o = os.path.join(WORK, "un_" + k)
        n = att(lambda: CH.bejson_core_chunking_unchunk_chunked_104(copy.deepcopy(d), o))
        exp["unchunk"][k] = {"n": n, "tree": tree(o) if os.path.isdir(o) else None}
# unchunk edge cases
bad = copy.deepcopy(docs["default"]); bad["Values"][0][bad["Fields"].index(next(f for f in bad["Fields"] if f["name"] == "Relative_Path"))] = "../escape.txt"
o = os.path.join(WORK, "un_escape"); exp["unchunk_escape"] = {"r": att(lambda: CH.bejson_core_chunking_unchunk_chunked_104(bad, o)), "tree": tree(o) if os.path.isdir(o) else None, "esc": os.path.exists(os.path.join(WORK, "escape.txt"))}
hb = copy.deepcopy(docs["default"]); hi = [f["name"] for f in hb["Fields"]].index("File_Hash"); hb["Values"][0][hi] = "deadbeef"
o = os.path.join(WORK, "un_hash"); exp["unchunk_badhash"] = {"r": att(lambda: CH.bejson_core_chunking_unchunk_chunked_104(hb, o)), "tree": tree(o) if os.path.isdir(o) else None}
exp["unchunk_notdoc"] = att(lambda: CH.bejson_core_chunking_unchunk_chunked_104({"x": 1}, os.path.join(WORK, "un_nd")))
# ---------- MFDB fixture
def build_db(root):
    F = lambda *a: [{"name": n, "type": t} for n, t in a]
    mp = M.mfdb_core_create_database(root, "Shop", [{"name": "Customer", "fields": F(("id", "integer"), ("name", "string")), "primary_key": "id"},
        {"name": "Order", "fields": F(("oid", "integer"), ("cust_id", "integer"), ("total", "number"))}], db_description="demo")
    M.mfdb_core_add_entity_record(mp, "Customer", [1, "Ann"]); M.mfdb_core_add_entity_record(mp, "Customer", [2, "Bob"])
    M.mfdb_core_add_entity_record(mp, "Order", [10, 1, 5.5])
    return mp
DBA = os.path.join(FX, "db_ok"); mpA = build_db(DBA)
# broken variants
def variant(name, fn):
    d = os.path.join(FX, "db_" + name); shutil.copytree(DBA, d); fn(d); return d
def edit(path, fn):
    doc = json.load(open(path)); fn(doc); json.dump(doc, open(path, "w"), indent=2)
variants_db = {"ok": DBA}
variants_db["missing_entity"] = variant("missing_entity", lambda d: os.remove(d + "/data/order.bejson"))
variants_db["count_mismatch"] = variant("count_mismatch", lambda d: edit(d + "/104a.mfdb.bejson", lambda x: x["Values"][0].__setitem__(3, 77)))
variants_db["bad_parent"] = variant("bad_parent", lambda d: edit(d + "/data/customer.bejson", lambda x: x.__setitem__("Parent_Hierarchy", "../other.json")))
variants_db["no_parent"] = variant("no_parent", lambda d: edit(d + "/data/customer.bejson", lambda x: x.pop("Parent_Hierarchy")))
variants_db["bad_role"] = variant("bad_role", lambda d: edit(d + "/104a.mfdb.bejson", lambda x: x.__setitem__("Network_Role", "Boss")))
variants_db["no_mfdb_ver"] = variant("no_mfdb_ver", lambda d: edit(d + "/104a.mfdb.bejson", lambda x: x.pop("MFDB_Version")))
variants_db["entity_103"] = variant("entity_103", lambda d: edit(d + "/data/order.bejson", lambda x: x.__setitem__("Format_Version", "104a")))
variants_db["entity_badrow"] = variant("entity_badrow", lambda d: edit(d + "/data/order.bejson", lambda x: x["Values"].append([1])))
variants_db["dup_entity"] = variant("dup_entity", lambda d: edit(d + "/104a.mfdb.bejson", lambda x: x["Values"].append(list(x["Values"][0]))))
variants_db["path_escape"] = variant("path_escape", lambda d: edit(d + "/104a.mfdb.bejson", lambda x: x["Values"][0].__setitem__(1, "../../etc/passwd")))
variants_db["manifest_notjson"] = variant("manifest_notjson", lambda d: open(d + "/104a.mfdb.bejson", "w").write("{"))
variants_db["manifest_104"] = variant("manifest_104", lambda d: edit(d + "/104a.mfdb.bejson", lambda x: x.__setitem__("Format_Version", "104")))
variants_db["empty_manifest"] = variant("empty_manifest", lambda d: edit(d + "/104a.mfdb.bejson", lambda x: x.__setitem__("Values", [])))
variants_db["wrong_name"] = variant("wrong_name", lambda d: (shutil.move(d + "/104a.mfdb.bejson", d + "/manifest.json")))
exp["mv"] = {}
def R(r): return {"valid": r.valid, "errors": r.errors, "warnings": r.warnings}
for k, d in variants_db.items():
    m = d + "/104a.mfdb.bejson" if os.path.exists(d + "/104a.mfdb.bejson") else d + "/manifest.json"
    exp["mv"][k] = J({"manifest": att(lambda: R(MV.validate_mfdb_manifest(m))), "db": att(lambda: R(MV.validate_mfdb_database(m))),
        "db_strict": att(lambda: R(MV.validate_mfdb_database(m, strict_fk=True))),
        "entity": att(lambda: R(MV.validate_mfdb_entity_file(d + "/data/customer.bejson"))),
        "entity_nobidi": att(lambda: R(MV.validate_mfdb_entity_file(d + "/data/order.bejson", check_bidirectional=False))),
        "raise_manifest": att(lambda: MV.mfdb_validator_validate_manifest(m)), "raise_db": att(lambda: MV.mfdb_validator_validate_database(m))})
exp["mv_helpers"] = J({"rows": MV._rows_as_dicts(json.load(open(mpA))), "resolve": MV._resolve_entity_path(mpA, "data/customer.bejson"),
    "load_bad": att(lambda: MV._load_json(variants_db["manifest_notjson"] + "/104a.mfdb.bejson")), "load_missing": att(lambda: MV._load_json(DBA + "/zzz"))})
# ---------- MFDB-132
pk = CH.bejson_core_chunking_create_mfdb132_package(DBA, "Shop")
exp["m132_pkg"] = J(pk)
exp["m132_pkg2"] = J(CH.bejson_core_chunking_create_mfdb132_package(DBA, "Shop", package_version="4", prior_package_doc=pk))
exp["m132_pkg3"] = J(att(lambda: CH.bejson_core_chunking_create_mfdb132_package(DBA, "Shop", prior_package_doc=pk)))
exp["m132_is"] = [CH.bejson_core_chunking_is_mfdb132_package(pk), CH.bejson_core_chunking_is_mfdb132_package(docs["default"]), att(lambda: CH.bejson_core_chunking_is_mfdb132_package({}))]
exp["m132_validate"] = J([att(lambda: CH.bejson_core_chunking_validate_mfdb132_package(pk)), att(lambda: CH.bejson_core_chunking_validate_mfdb132_package(docs["default"])), att(lambda: CH.bejson_core_chunking_validate_mfdb132_package({"x": 1}))])
exp["m132_mv"] = J([att(lambda: MV.mfdb_validator_validate_mfdb132_package(pk)), att(lambda: MV.mfdb_validator_is_mfdb132_package(pk)), att(lambda: MV.mfdb_validator_detect_mfdb_in_chunk(pk)), att(lambda: MV.mfdb_validator_detect_mfdb_in_chunk(docs["default"])), att(lambda: MV._mfdb_validator_find_row_by_relpath(pk, "104a.mfdb.bejson"))])
exp["m132_detect"] = J([att(lambda: CH.bejson_core_chunking_detect_mfdb_in_chunk(pk)), att(lambda: CH.bejson_core_chunking_detect_mfdb_in_chunk(docs["default"])), att(lambda: CH.bejson_core_chunking_detect_mfdb_in_chunk({}))])
exp["m132_fm"] = J(CH.bejson_core_chunking_mfdb_get_field_map(pk))
exp["m132_schema"] = [CH.bejson_core_chunking_mfdb_detect_schema(x) for x in [pk, docs["default"], json.load(open(mpA)), json.load(open(DBA + "/data/customer.bejson")), {}]]
exp["m132_ver"] = [CH.bejson_core_chunking_mfdb_check_version(x) for x in [pk, {}, {"MFDB_Version": "9.9"}, {"MFDB_Version": 1.31}]]
o = os.path.join(WORK, "m132"); r = att(lambda: CH.bejson_core_chunking_unchunk_mfdb132_package(copy.deepcopy(pk), o))
exp["m132_unchunk"] = {"r": J(r), "tree": tree(o) if os.path.isdir(o) else None}
for name, d_ in [("m132_gen_chunked", docs["default"]), ("m132_gen_pk", pk), ("m132_gen_manifest", json.load(open(mpA))), ("m132_gen_entity", json.load(open(DBA + "/data/customer.bejson")))]:
    o = os.path.join(WORK, name)
    r = att(lambda: CH.bejson_core_chunking_mfdb_unchunk(copy.deepcopy(d_), o))
    exp[name] = {"r": J(r), "tree": tree(o) if os.path.isdir(o) else None}
# ---------- MFDB132Archive mount/commit/resurrect/unmount
pkf = os.path.join(FX, "shop.chunk.bejson"); json.dump(pk, open(pkf, "w"), indent=2)
mt = os.path.join(WORK, "mnt132")
A = CH.MFDB132Archive
exp["a132"] = {}
exp["a132"]["mount"] = J(att(lambda: A.mount(pkf, mt))); exp["a132"]["tree1"] = tree(mt) if os.path.isdir(mt) else None
exp["a132"]["mount_again"] = J(att(lambda: A.mount(pkf, mt)))
exp["a132"]["mount_force"] = J(att(lambda: A.mount(pkf, mt, force=True)))
edit(mt + "/data/customer.bejson", lambda x: x["Values"].append([9, "Zed"]))
outp = os.path.join(WORK, "out132.bejson")
exp["a132"]["commit"] = J(att(lambda: A.commit(mt, outp)))
exp["a132"]["committed"] = J(json.load(open(outp))) if os.path.exists(outp) else None
os.remove(mt + "/data/order.bejson")
exp["a132"]["resurrect"] = att(lambda: A.resurrect_file(mt, "data/order.bejson"))
exp["a132"]["resurrect_missing"] = att(lambda: A.resurrect_file(mt, "data/nope.bejson"))
exp["a132"]["tree2"] = tree(mt)
exp["a132"]["unmount"] = att(lambda: A.unmount(mt)); exp["a132"]["exists_after"] = os.path.exists(mt)
exp["a132"]["commit_nomount"] = J(att(lambda: A.commit(os.path.join(WORK, "nomount"), outp)))
exp["a132"]["mount_missing"] = J(att(lambda: A.mount(os.path.join(FX, "nofile.json"), os.path.join(WORK, "m2"))))
# ---------- MFDBArchive (zip)
zp = os.path.join(FX, "shop.mfdb.zip")
with zipfile.ZipFile(zp, "w", zipfile.ZIP_DEFLATED) as z:
    for dp, _, fs in os.walk(DBA):
        for f in sorted(fs): z.write(os.path.join(dp, f), os.path.relpath(os.path.join(dp, f), DBA))
mt2 = os.path.join(WORK, "mntzip"); AZ = M.MFDBArchive
exp["az"] = {}
exp["az"]["mount"] = J(att(lambda: AZ.mount(zp, mt2))); exp["az"]["tree1"] = tree(mt2) if os.path.isdir(mt2) else None
exp["az"]["mount_sticky"] = J(att(lambda: AZ.mount(zp, mt2)))
M.mfdb_core_add_entity_record(os.path.join(mt2, "104a.mfdb.bejson"), "Customer", [5, "Eve"])
outz = os.path.join(WORK, "out.zip")
exp["az"]["mount_nonforce_modified"] = J(att(lambda: AZ.mount(zp, mt2)))
exp["az"]["commit"] = J(att(lambda: AZ.commit(mt2, outz)))
exp["az"]["zip_names"] = sorted(zipfile.ZipFile(outz).namelist()) if os.path.exists(outz) else None
exp["az"]["resurrect_present"] = att(lambda: AZ.resurrect_file(mt2, "data/customer.bejson"))
os.remove(mt2 + "/data/order.bejson")
exp["az"]["resurrect"] = att(lambda: AZ.resurrect_file(mt2, "data/order.bejson"))
exp["az"]["resurrect_missing"] = att(lambda: AZ.resurrect_file(mt2, "data/nope.bejson"))
exp["az"]["unmount"] = att(lambda: AZ.unmount(mt2)); exp["az"]["exists_after"] = os.path.exists(mt2)
exp["az"]["mount_missing"] = J(att(lambda: AZ.mount(os.path.join(FX, "nofile.zip"), os.path.join(WORK, "m3"))))
# zip-slip
evil = os.path.join(FX, "evil.zip")
with zipfile.ZipFile(evil, "w") as z: z.writestr("../evil.txt", "x"); z.writestr("104a.mfdb.bejson", "{}")
exp["az"]["evil"] = J(att(lambda: AZ.mount(evil, os.path.join(WORK, "m4"))))
exp["az"]["evil_escaped"] = os.path.exists(os.path.join(WORK, "evil.txt"))
nm = os.path.join(FX, "nomanifest.zip")
with zipfile.ZipFile(nm, "w") as z: z.writestr("data/x.bejson", "{}")
exp["az"]["nomanifest"] = J(att(lambda: AZ.mount(nm, os.path.join(WORK, "m5"))))
# ---------- parse
exp["parse"] = {}
for k, txt in {"plain": '{"a": 1}', "wrapped": 'Here you go:\n```json\n{"a": [1,2]}\n```\nbye', "nested": 'x {"a": {"b": 1}} y {"c": 2}', "none": "no json", "arr": "[1,2]", "trail": '{"a": 1,}', "empty": "", "unicode": 'ü {"é": "😀"} z'}.items():
    exp["parse"][k] = J(att(lambda: P.parse_json(txt)))
exp["extract"] = {}
def tab(fields, rows): return {"Fields": [{"name": n, "type": "string"} for n in fields], "Values": rows}
for k, d in {"empty": tab(["a"], []), "basic": tab(["Project Name", "File1 Name", "File1 Content", "file2_name", "File2-Content"], [["Pr<oj>/x", "a.txt", " hello ", "b.txt", "world"], ["Other", None, "x", "c.txt", ""]]),
             "zip": tab(["ZipFileName", "file1name", "file1content"], [["Z:z", "f", "c"]]), "container": tab(["container_name"], [["C"]]), "many": tab(["projectname"] + ["file%dname" % i for i in range(1, 52)] + ["file%dcontent" % i for i in range(1, 52)], [["P"] + ["n%d" % i for i in range(1, 52)] + ["c%d" % i for i in range(1, 52)]]),
             "short_row": tab(["projectname", "file1name", "file1content"], [["P"]]), "nofields": {"Values": [["a"]]}, "num": {"Fields": [{"name": "projectname"}, {"name": "file1name"}, {"name": "file1content"}], "Values": [[5, 6, 7]]}}.items():
    exp["extract"][k] = J(att(lambda: P.extract_data(copy.deepcopy(d))))
base = os.path.join(WORK, "parse_out")
exp["save"] = {}
files = [{"name": "a.txt", "content": "héllo"}, {"name": "sub/b.txt", "content": "x" * 2000}, {"name": "c.txt", "content": ""}]
r = P.save_files("Proj", files, {"output_path": base}); exp["save"]["new"] = {"r": J(r), "tree": {norm(k): v for k, v in tree(base).items()}}
r2 = P.save_files("Proj", files[:1], {"output_path": base, "overwrite_enabled": True}); exp["save"]["over1"] = {"r": J(r2), "tree": {norm(k): v for k, v in tree(base).items()}}
r3 = P.save_files("Proj", files[:2], {"output_path": base, "overwrite_enabled": True}); exp["save"]["over2"] = {"r": J(r3), "tree": {norm(k): v for k, v in tree(base).items()}}
exp["save"]["escape"] = J(att(lambda: P.save_files("Proj", [{"name": "../../evil.txt", "content": "x"}], {"output_path": base, "overwrite_enabled": True})))
exp["save"]["escape_exists"] = os.path.exists(os.path.join(out, "evil.txt"))
json.dump(exp, open(os.path.join(out, "d_expected.json"), "w"), sort_keys=True)
print("oracle D ok;", len(exp), "sections")
