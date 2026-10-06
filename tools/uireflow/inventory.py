#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""前端重排版前置盘点：抽取 HTML id/class/i18n key、JS 内引用、CSS 选择器，做交叉比对。"""
import io, json, os, re, sys
from collections import Counter

ROOT = r"G:/狼人杀/src/main/resources/static"
HTML = os.path.join(ROOT, "index.html")
JS_FILES = ["js/app.js", "js/extras.js", "js/voice.js", "js/i18n.js"]
CSS = os.path.join(ROOT, "css/style.css")

def read(p):
    with io.open(p, "r", encoding="utf-8-sig") as f:
        return f.read()

html = read(HTML)
css = read(CSS)
js = "\n".join(read(os.path.join(ROOT, j)) for j in JS_FILES)

# ---- HTML ----
html_ids = sorted(set(re.findall(r'\bid="([^"]+)"', html)))
html_classes = sorted(set(c for a in re.findall(r'\bclass="([^"]+)"', html) for c in a.split()))
i18n_keys = sorted(set(re.findall(r'data-i18n(?:-ph)?="([^"]+)"', html)))
main_views = re.findall(r'<main id="(view-[^"]+)"', html)
modals = re.findall(r'<div id="(modal-[^"]+)"', html)

# ---- JS references ----
js_ids = set(re.findall(r"""\$\(\s*['"]([^'"]+)['"]""", js))
js_ids |= set(re.findall(r"""getElementById\(\s*['"]([^'"]+)['"]""", js))
js_ids |= set(re.findall(r"""querySelector(?:All)?\(\s*['"]#([\w-]+)""", js))
js_ids |= set(re.findall(r"""['"](view-[a-z]+|modal-[a-z-]+)['"]""", js))
js_classes = set(re.findall(r"""classList\.(?:add|remove|toggle)\(\s*['"]([\w-]+)['"]""", js))
js_classes |= set(re.findall(r"""querySelector(?:All)?\(\s*['"]\.([\w-]+)""", js))
# classes emitted from JS-generated html strings
js_gen_classes = Counter(re.findall(r'class="?\$?\{?\s*([\w-]+)', js))
js_gen_classes = set(k for k, v in js_gen_classes.items() if len(k) > 2)

# ---- CSS ----
css_classes = set(re.findall(r"\.([A-Za-z][\w-]*)", re.sub(r"/\*.*?\*/", "", css, flags=re.S)))
css_top_sections = re.findall(r"/\* -{2,}\s*(.+?)\s*-{2,}\s*\*/", css)

only_js_ids = sorted(js_ids - set(html_ids))
dead_html_ids = sorted(set(html_ids) - js_ids)
cls_in_js_not_css = sorted((js_classes | js_gen_classes) - css_classes)
cls_css_not_html_js = sorted(css_classes - set(html_classes) - js_classes - js_gen_classes)

out = {
    "views": main_views,
    "modals": modals,
    "counts": {
        "html_ids": len(html_ids), "js_id_refs": len(js_ids),
        "html_classes": len(html_classes), "css_classes": len(css_classes),
        "i18n_keys": len(i18n_keys), "css_lines": css.count("\n") + 1,
    },
    "only_js_ids_must_keep_or_add": only_js_ids,
    "dead_html_ids_maybe_drop": dead_html_ids,
    "js_classes_not_in_css": cls_in_js_not_css,
    "css_classes_unused_by_markup_js": cls_css_not_html_js,
    "css_sections": css_top_sections,
}
print(json.dumps(out, ensure_ascii=False, indent=1))
