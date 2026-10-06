#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按函数归组，抽取 app.js 中 JS 生成标记里的 id / class 契约。"""
import io, re, sys

path = sys.argv[1] if len(sys.argv) > 1 else r"G:/狼人杀/src/main/resources/static/js/app.js"
lines = io.open(path, encoding="utf-8").read().split("\n")

fn_re = re.compile(r"^\s*(?:async\s+)?(?:function\s+([\w$]+)|const\s+([\w$]+)\s*=\s*(?:\{|\(|async)|class\s+([\w$]+))")
cur = "<top>"
depth_hint = {}
out = {}
for i, l in enumerate(lines, 1):
    m = fn_re.match(l)
    if m:
        cur = next((g for g in m.groups() if g), cur)
    ids = re.findall(r'id="([^"]*)"', l) + re.findall(r"id=\w+", l)
    cls = re.findall(r'class="([^"]*)"', l)
    toks = set()
    for c in cls:
        for t in re.split(r"[\s]+", c):
            t = re.sub(r"\$\{[^}]*\}", "*", t)
            if t and "$" not in t:
                toks.add(t)
    for x in ids:
        if x and "$" not in x and "=" not in x:
            toks.add("#" + x)
    if toks:
        out.setdefault(cur, []).append((i, sorted(toks)))

for fn, items in out.items():
    all_toks = sorted({t for _, ts in items for t in ts})
    print("\n### %s  (%d 处, %d 个记号)  行: %s" % (fn, len(items), len(all_toks), ",".join(str(i) for i, _ in items[:12])))
    print("   " + "  ".join(all_toks))
