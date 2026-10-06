# -*- coding: utf-8 -*-
"""measure_kokoro.py —— 实测 Kokoro 中文语速，校准 estimateNarrationMs 的 msPerChar"""
import io, json, struct, sys, urllib.request, time

TTS = "http://127.0.0.1:5002"

def wav_ms(data: bytes):
    assert data[:4] == b"RIFF"
    pos, rate, pcm = 12, 16000, b""
    while pos + 8 <= len(data):
        cid = data[pos:pos+4]; size = struct.unpack("<I", data[pos+4:pos+8])[0]; body = data[pos+8:pos+8+size]
        if cid == b"fmt ": rate = struct.unpack("<HHIIHH", body[:16])[2]
        elif cid == b"data": pcm = body
        pos += 8 + size + (size & 1)
    return rate, len(pcm) / 2 / rate * 1000.0   # ms

samples = [
    ("短句", "天黑请闭眼。", 6),
    ("中句", "狼人请睁眼，请统一今晚要刀的目标。", 16),
    ("长句", "女巫请睁眼，昨晚的情况你在心里判断，是否使用解药或毒药？", 27),
    ("长句2", "夜幕落下，请各位闭眼。狼人请睁眼，请统一今晚要刀的目标。女巫请睁眼，看完昨晚的局势再决定是否用药。", 48),
]
total_chars, total_ms = 0, 0.0
for name, text, chars in samples:
    t0 = time.time()
    req = urllib.request.Request(TTS + "/tts", data=json.dumps({"text": text, "voice": "zf_xiaobei"}).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        data = r.read()
    rate, ms = wav_ms(data)
    synth = (time.time() - t0) * 1000
    per_char = ms / max(1, len(text))
    total_chars += len(text); total_ms += ms
    print(f"{name}: 文本{len(text)}字 → 音频{ms:.0f}ms（{per_char:.0f}ms/字，{rate}Hz），合成耗时{synth:.0f}ms")
print(f"\n整体：{total_ms/total_chars:.0f} ms/字（当前默认估算 100ms/字 + 260ms 头 + 分句间隙）")
