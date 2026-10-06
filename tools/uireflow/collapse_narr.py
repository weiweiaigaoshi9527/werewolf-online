# -*- coding: utf-8 -*-
"""collapse_narr.py —— 旁白系统化：narr(...) 调用塌缩为第一变体，删除 narr() 辅助函数"""
import io, re

p = r"G:\狼人杀\src\main\java\com\werewolf\game\LiveGameService.java"
s = io.open(p, encoding="utf-8").read()

# 1) 所有 narr(lg, "key", "变体1", "变体2", ...) → "变体1"
pat = re.compile(r'narr\(lg,\s*"[^"]*",\s*("(?:[^"\\]|\\.)*")(?:,\s*"(?:[^"\\]|\\.)*")*\s*\)')
s, n1 = pat.subn(lambda m: m.group(1), s)
print("narr 调用塌缩:", n1, "处")

# 2) 删除 narr() 辅助函数及其 javadoc
pat2 = re.compile(
    r'    /\*\*\n'
    r'     \* 旁白多变体轮换：[\s\S]*?'
    r'private String narr\(LiveGame lg, String key, String\.\.\. variants\) \{\n'
    r'[^}]*\n'
    r'    \}\n\n'
)
s, n2 = pat2.subn("", s)
print("narr() 辅助函数删除:", n2, "处")

assert "narr(lg" not in s, "仍有 narr 调用残留"
assert "private String narr(" not in s, "辅助函数未删净"

io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("写入 ✓")
