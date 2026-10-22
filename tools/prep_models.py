#!/usr/bin/env python
"""Prepares the eight animal models for the app (run with `python -I tools/prep_models.py`).

Input : life-size-creatures-assets/raw/<animal>/<uid>.glb (CC BY 4.0, see README "Credits")
Output: app/src/main/assets/models/<animal>.glb   the model, with KHR_materials_pbrSpecularGlossiness
                                                  turned into core metallic-roughness (the SpatialEngine
                                                  path is core glTF 2.0); geometry, skins and clips untouched
        app/src/main/res/drawable-nodpi/sil_<animal>.png   side silhouette, head to the right (field guide tiles)
        tools/models.json                         default-pose box in file units and the height profile
                                                  along the length axis (used to set lengthRef by hand)

All files are Y-up with the head towards +Z once the node transforms are applied (checked by plots).
Skinned meshes are posed with linear blend skinning in the default node pose.
"""
import json
import math
import os
import struct
import sys

from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RAW = os.path.abspath(os.path.join(ROOT, "..", "life-size-creatures-assets", "raw"))
OUT_MODELS = os.path.join(ROOT, "app", "src", "main", "assets", "models")
OUT_SIL = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")
ANIMALS = ["shiba", "wolf", "velociraptor", "stag", "horse", "shark", "triceratops", "trex"]

CT = {5126: ("f", 4), 5123: ("H", 2), 5121: ("B", 1), 5122: ("h", 2), 5120: ("b", 1), 5125: ("I", 4)}
NC = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


def read_glb(path):
    data = open(path, "rb").read()
    magic, version, length = struct.unpack_from("<III", data, 0)
    assert magic == 0x46546C67, "not a GLB"
    off = 12
    js, binc = None, b""
    while off < length:
        clen, ctype = struct.unpack_from("<II", data, off)
        chunk = data[off + 8 : off + 8 + clen]
        if ctype == 0x4E4F534A:
            js = json.loads(chunk.decode("utf-8"))
        elif ctype == 0x004E4942:
            binc = chunk
        off += 8 + clen
    return js, binc


def write_glb(path, js, binc):
    jb = json.dumps(js, separators=(",", ":")).encode("utf-8")
    jb += b" " * ((4 - len(jb) % 4) % 4)
    bb = binc + b"\x00" * ((4 - len(binc) % 4) % 4)
    total = 12 + 8 + len(jb) + (8 + len(bb) if bb else 0)
    with open(path, "wb") as f:
        f.write(struct.pack("<III", 0x46546C67, 2, total))
        f.write(struct.pack("<II", len(jb), 0x4E4F534A))
        f.write(jb)
        if bb:
            f.write(struct.pack("<II", len(bb), 0x004E4942))
            f.write(bb)


def spec_gloss_to_metal_rough(js):
    """Core metallic-roughness from the archived spec-gloss extension; animals are dielectric and matte."""
    changed = 0
    for m in js.get("materials", []):
        ext = m.get("extensions", {}).pop("KHR_materials_pbrSpecularGlossiness", None)
        if ext is None:
            continue
        pbr = m.setdefault("pbrMetallicRoughness", {})
        pbr["baseColorFactor"] = ext.get("diffuseFactor", [1, 1, 1, 1])
        if "diffuseTexture" in ext:
            pbr["baseColorTexture"] = ext["diffuseTexture"]
        pbr["metallicFactor"] = 0.0
        pbr["roughnessFactor"] = max(0.55, min(1.0, 1.0 - ext.get("glossinessFactor", 0.3)))
        pbr.pop("metallicRoughnessTexture", None)
        if not m["extensions"]:
            m.pop("extensions")
        changed += 1
    for key in ("extensionsUsed", "extensionsRequired"):
        if key in js:
            js[key] = [e for e in js[key] if e != "KHR_materials_pbrSpecularGlossiness"]
            if not js[key]:
                js.pop(key)
    return changed


def mat_mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]


def node_matrix(n):
    if "matrix" in n:
        m = n["matrix"]
        return [[m[c * 4 + r] for c in range(4)] for r in range(4)]
    t = n.get("translation", [0, 0, 0])
    x, y, z, w = n.get("rotation", [0, 0, 0, 1])
    s = n.get("scale", [1, 1, 1])
    r = [
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ]
    return [
        [r[0][0] * s[0], r[0][1] * s[1], r[0][2] * s[2], t[0]],
        [r[1][0] * s[0], r[1][1] * s[1], r[1][2] * s[2], t[1]],
        [r[2][0] * s[0], r[2][1] * s[1], r[2][2] * s[2], t[2]],
        [0, 0, 0, 1],
    ]


def apply(m, p):
    return [m[i][0] * p[0] + m[i][1] * p[1] + m[i][2] * p[2] + m[i][3] for i in range(3)]


def read_acc(js, binc, ai):
    a = js["accessors"][ai]
    bv = js["bufferViews"][a["bufferView"]]
    fmt, sz = CT[a["componentType"]]
    n = NC[a["type"]]
    stride = bv.get("byteStride", sz * n)
    off = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
    out = []
    for i in range(a["count"]):
        v = struct.unpack_from("<" + fmt * n, binc, off + i * stride)
        if a.get("normalized") and fmt != "f":
            v = tuple(x / float((1 << (8 * sz - (0 if fmt.isupper() else 1))) - 1) for x in v)
        out.append(v)
    return out


def posed_triangles(js, binc):
    nodes = js["nodes"]
    ident = [[1 if i == j else 0 for j in range(4)] for i in range(4)]
    world = {}

    def walk(ni, parent):
        m = mat_mul(parent, node_matrix(nodes[ni]))
        world[ni] = m
        for c in nodes[ni].get("children", []):
            walk(c, m)

    for r in js["scenes"][js.get("scene", 0)]["nodes"]:
        walk(r, ident)
    tris = []
    for ni, n in enumerate(nodes):
        if "mesh" not in n or ni not in world:
            continue
        joint_m = None
        if "skin" in n:
            sk = js["skins"][n["skin"]]
            ibms = read_acc(js, binc, sk["inverseBindMatrices"])
            joint_m = [mat_mul(world[j], [[ibm[c * 4 + r] for c in range(4)] for r in range(4)]) for j, ibm in zip(sk["joints"], ibms)]
        for p in js["meshes"][n["mesh"]]["primitives"]:
            pos = read_acc(js, binc, p["attributes"]["POSITION"])
            if joint_m is not None and "JOINTS_0" in p["attributes"]:
                joints = read_acc(js, binc, p["attributes"]["JOINTS_0"])
                weights = read_acc(js, binc, p["attributes"]["WEIGHTS_0"])
                pts = []
                for v, jj, ww in zip(pos, joints, weights):
                    acc = [0.0, 0.0, 0.0]
                    ws = 0.0
                    for j, w in zip(jj, ww):
                        if w <= 0:
                            continue
                        q = apply(joint_m[int(j)], v)
                        for i in range(3):
                            acc[i] += w * q[i]
                        ws += w
                    pts.append([a / ws for a in acc] if ws > 0 else apply(world[ni], v))
            else:
                pts = [apply(world[ni], v) for v in pos]
            if "indices" in p:
                idx = [i[0] for i in read_acc(js, binc, p["indices"])]
            else:
                idx = list(range(len(pts)))
            for k in range(0, len(idx) - 2, 3):
                tris.append((pts[idx[k]], pts[idx[k + 1]], pts[idx[k + 2]]))
    return tris


def silhouette(tris, lo, hi, path, height_px=256):
    """Side view (Z right = head, Y up), filled, white on transparent, cropped to the box."""
    lz, hy = hi[2] - lo[2], hi[1] - lo[1]
    s = height_px / hy if hy * 2.2 > lz else (height_px * 2.2) / lz
    w = max(2, int(math.ceil(lz * s)) + 2)
    h = max(2, int(math.ceil(hy * s)) + 2)
    big = 3
    img = Image.new("L", (w * big, h * big), 0)
    d = ImageDraw.Draw(img)
    for a, b, c in tris:
        poly = [((p[2] - lo[2]) * s * big + big, (hi[1] - p[1]) * s * big + big) for p in (a, b, c)]
        d.polygon(poly, fill=255)
    img = img.resize((w, h), Image.LANCZOS)
    rgba = Image.new("RGBA", (w, h), (255, 255, 255, 0))
    rgba.putalpha(img)
    rgba.save(path)
    return w, h


def main():
    os.makedirs(OUT_MODELS, exist_ok=True)
    os.makedirs(OUT_SIL, exist_ok=True)
    report = {}
    for animal in ANIMALS:
        folder = os.path.join(RAW, animal)
        glbs = [f for f in os.listdir(folder) if f.endswith(".glb")]
        assert len(glbs) == 1, (animal, glbs)
        js, binc = read_glb(os.path.join(folder, glbs[0]))
        changed = spec_gloss_to_metal_rough(js)
        write_glb(os.path.join(OUT_MODELS, animal + ".glb"), js, binc)
        tris = posed_triangles(js, binc)
        pts = [p for t in tris for p in t]
        lo = [min(p[i] for p in pts) for i in range(3)]
        hi = [max(p[i] for p in pts) for i in range(3)]
        # height of the body in 40 slices along Z, tail (-Z) to head (+Z)
        n = 40
        top = [None] * n
        bottom = [None] * n
        for p in pts:
            k = min(n - 1, int((p[2] - lo[2]) / (hi[2] - lo[2]) * n))
            top[k] = p[1] if top[k] is None else max(top[k], p[1])
            bottom[k] = p[1] if bottom[k] is None else min(bottom[k], p[1])
        profile = [round(((t or 0) - (b or 0)) / (hi[1] - lo[1]), 3) for t, b in zip(top, bottom)]
        sw, sh = silhouette(tris, lo, hi, os.path.join(OUT_SIL, "sil_" + animal + ".png"))
        report[animal] = {
            "source": glbs[0],
            "specGlossConverted": changed,
            "triangles": len(tris),
            "min": [round(v, 4) for v in lo],
            "max": [round(v, 4) for v in hi],
            "size": [round(hi[i] - lo[i], 4) for i in range(3)],
            "heightProfileTailToHead": profile,
            "silhouettePx": [sw, sh],
            "clips": [a.get("name", "") for a in js.get("animations", [])],
        }
        print(animal, report[animal]["size"], "tris", len(tris), "clips", report[animal]["clips"], "converted", changed)
    with open(os.path.join(ROOT, "tools", "models.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, indent=1)


if __name__ == "__main__":
    sys.exit(main())
