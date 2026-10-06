#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检查 admin.html / admin.js 依赖 style.css 的哪些类，以及管理台自带样式覆盖了多少。"""
import io, re

R = "G:/狼人杀/src/main/resources/static/"
h = io.open(R + "admin.html", encoding="utf-8").read()
js = io.open(R + "js/admin.js", encoding="utf-8").read()
css = io.open(R + "css/style.css", encoding="utf-8").read()

i = h.find("<style")
body, own = (h[:i], h[i:]) if i > 0 else (h, "")

cls = set()
for a in re.findall(r'class="([^"]+)"', body):
    cls |= {t for t in a.split() if "$" not in t and "{" not in t}
for a in re.findall(r'class="([^"]+)"', js):
    for t in re.split(r"\s+", a):
        t = re.sub(r"\$\{[^}]*\}", "", t).strip()
        if t and "$" not in t and "{" not in t:
            cls.add(t)

own_defined = set(re.findall(r"\.([A-Za-z][\w-]*)", own))
shared_defined = set(re.findall(r"\.([A-Za-z][\w-]*)", re.sub(r"/\*.*?\*/", "", css, flags=re.S)))

print("admin 用到的类:", len(cls))
dep = sorted(c for c in cls if c in shared_defined and c not in own_defined)
print("\n仅靠 style.css 提供（重写必须保持可用）:", len(dep))
print(" ".join(dep))
print("\nadmin 自己 <style> 里已定义的:", len(sorted(c for c in cls if c in own_defined)))
print(" ".join(sorted(c for c in cls if c in own_defined)))
print("\n两边都没有的:", sorted(c for c in cls if c not in shared_defined and c not in own_defined))
