# -*- coding: utf-8 -*-
"""tools/uireflow/roundtrip.py —— TTS/ASR 微服务直连验证
1) POST /tts  合成中文 → 校验 WAV
2) 把 TTS 产物的 PCM 喂给 POST /transcribe → 校验转写文本（TTS→ASR 互证）
3) 把 SAPI 合成的真人语音（如可用）也喂给 ASR
"""
import io, json, struct, sys, urllib.request

ASR = "http://127.0.0.1:5001"
TTS = "http://127.0.0.1:5002"
PHRASE = "天黑请闭眼，狼人请睁眼"

def http(url, data=None, headers=None, timeout=30):
    req = urllib.request.Request(url, data=data, headers=headers or {})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, r.read()

def health():
    ok = True
    for name, base in (("ASR", ASR), ("TTS", TTS)):
        st, body = http(base + "/health", timeout=10)
        ok = ok and st == 200 and b'"ok"' in body or b'"ok":true' in body
        print(f"[health] {name} {base} → {st} {body[:120].decode('utf-8','replace')}")
    return ok

def wav_pcm16(data: bytes):
    """极简 WAV 解析：返回 (采样率, PCM16 字节)。fmt 结构 <HHIIHH> = 格式/声道/采样率/字节率/块对齐/位深"""
    assert data[:4] == b"RIFF" and data[8:12] == b"WAVE", "不是 WAV"
    pos, fmt, pcm = 12, {}, b""
    while pos + 8 <= len(data):
        cid = data[pos:pos+4]; size = struct.unpack("<I", data[pos+4:pos+8])[0]; body = data[pos+8:pos+8+size]
        if cid == b"fmt ": fmt = struct.unpack("<HHIIHH", body[:16])
        elif cid == b"data": pcm = body
        pos += 8 + size + (size & 1)
    return fmt[2], pcm   # fmt[2] 才是采样率；fmt[1] 是声道数（上次就栽在这）

results = []

def check(ok, name, detail):
    results.append((ok, name, detail))
    print(("  PASS " if ok else "  FAIL ") + name + ("  [" + detail + "]" if detail else ""))

print("== 0. 健康检查 ==")
check(health(), "ASR/TTS /health 均 ok", "")

print("== 1. TTS 直连合成 ==")
st, wav = http(TTS + "/tts", data=json.dumps({"text": PHRASE, "voice": "zf_xiaobei", "speed": 1.0}).encode(),
               headers={"Content-Type": "application/json"}, timeout=60)
sr, pcm = (None, b"")
try: sr, pcm = wav_pcm16(wav)
except Exception as e: pass
check(st == 200 and len(wav) > 8000 and wav[:4] == b"RIFF", "TTS 返回有效 WAV（Kokoro zh 音色）",
      f"status={st} bytes={len(wav)} sr={sr}")

print("== 2. TTS→ASR 互证（合成语音喂回识别） ==")
st, body = http(ASR + "/transcribe?sr=%d" % (sr or 16000), data=pcm, headers={"Content-Type": "application/octet-stream"}, timeout=60)
text = ""
try: text = json.loads(body).get("text", "")
except Exception: pass
hit = any(k in text for k in ("天黑", "闭眼", "狼人"))
check(st == 200 and len(text) > 0 and hit, "ASR 把 TTS 语音转写回原文关键词", f"text={text!r}")

print("== 3. SAPI 真人语音 → ASR ==")
try:
    with open(r"G:\狼人杀\tools\uireflow\sapi_zh.wav", "rb") as f:
        sapi = f.read()
    sr2, pcm2 = wav_pcm16(sapi)
    st, body = http(ASR + "/transcribe?sr=%d" % sr2, data=pcm2, headers={"Content-Type": "application/octet-stream"}, timeout=60)
    text2 = ""
    try: text2 = json.loads(body).get("text", "")
    except Exception: pass
    check(st == 200 and len(text2) > 0, "Windows SAPI 合成语音 → ASR 非空转写", f"text={text2!r}")
except FileNotFoundError:
    check(False, "SAPI WAV 缺失（先跑 tts_asr_roundtrip.ps1）", "")

print("== 4. 空输入边界 ==")
st, body = http(ASR + "/transcribe?sr=16000", data=b"\x00\x00" * 100, headers={"Content-Type": "application/octet-stream"}, timeout=30)
short_ok = st == 200
st, body = http(TTS + "/tts", data=json.dumps({"text": ""}).encode(), headers={"Content-Type": "application/json"}, timeout=30)
tts_empty_ok = st == 200 and len(body) > 0
check(short_ok and tts_empty_ok, "空文本/极短音频不 500（返回空串/静音）", f"asr={st} tts_empty_bytes={len(body)}")

print("\n结果：通过 %d / 失败 %d" % (sum(1 for r in results if r[0]), sum(1 for r in results if not r[0])))
sys.exit(0 if all(r[0] for r in results) else 1)
