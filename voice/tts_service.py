"""
Kokoro TTS 微服务（独立进程，运行于 venv-tts）。
接口：
  GET  /health   -> {ok, lang, default_voice, voices:[...]}
  GET  /voices   -> {voices:[...]}
  POST /tts      -> {"text","voice","speed"} 返回 audio/wav（24kHz 单声道 PCM16）
说明：
  - 通过 hf-mirror 下载 hexgrad/Kokoro-82M；中文用 lang_code（默认 'z'，可用 WW_KOKORO_LANG 覆盖）。
  - 音色名以实际模型 voices 目录为准；请求的 voice 不存在时自动回退默认音色，保证永不哑火。
环境变量：
  WW_MODEL_DIR     模型缓存根，默认 G:\\werewolf-voice\\models（作为 HF_HOME）
  WW_KOKORO_LANG   语言码，默认 z
  WW_KOKORO_REPO   仓库 id，默认 hexgrad/Kokoro-82M
  WW_TTS_PORT      端口，默认 5002
"""
import io
import os
import glob
import threading

os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
MODEL_DIR = os.environ.get("WW_MODEL_DIR", r"G:\werewolf-voice\models")
os.environ.setdefault("HF_HOME", os.path.join(MODEL_DIR, "hf"))

import numpy as np
import soundfile as sf
from fastapi import FastAPI, Request
from fastapi.responses import Response, JSONResponse
import uvicorn

LANG = os.environ.get("WW_KOKORO_LANG", "z")
REPO = os.environ.get("WW_KOKORO_REPO", "hexgrad/Kokoro-82M")
PORT = int(os.environ.get("WW_TTS_PORT", "5002"))
SR = 24000

app = FastAPI()
_pipe = None
_voices = []
_default_voice = None
# 共享 KPipeline 实例在 FastAPI 多并发下推理非线程安全，用互斥锁串行化 _synth 内部模型调用。
_pipe_lock = threading.Lock()


def _load():
    global _pipe, _voices, _default_voice
    from kokoro import KPipeline
    _pipe = KPipeline(lang_code=LANG, repo_id=REPO)
    _voices = _discover_voices()
    # 首选与 lang 匹配的音色（zh: zf_/zm_），否则取第一个
    pref = [v for v in _voices if v.startswith(("zf_", "zm_"))]
    _default_voice = (pref or _voices or ["zf_xiaoxiao"])[0]
    print(f"[tts] Kokoro lang={LANG} default_voice={_default_voice} voices={len(_voices)}")


def _discover_voices():
    # 从 HF 缓存里的 voices 目录枚举可用音色（*.pt / *.bin）
    try:
        from huggingface_hub import snapshot_download
        d = snapshot_download(REPO)
        files = glob.glob(os.path.join(d, "voices", "*.pt")) + glob.glob(os.path.join(d, "voices", "*.bin"))
        return sorted(os.path.splitext(os.path.basename(f))[0] for f in files)
    except Exception as e:
        print("[tts] voice discovery failed:", e)
        return []


@app.get("/health")
def health():
    return {"ok": _pipe is not None, "lang": LANG, "default_voice": _default_voice,
            "voices": _voices[:40], "count": len(_voices)}


@app.get("/voices")
def voices():
    return JSONResponse({"voices": _voices, "default": _default_voice})


def _audio_from_chunk(chunk):
    # KPipeline 产出形态兼容处理
    obj = chunk
    if isinstance(chunk, (tuple, list)):
        obj = chunk[-1]
    if hasattr(obj, "audio"):
        obj = obj.audio
    return np.asarray(obj, dtype=np.float32)


@app.post("/tts")
async def tts(request: Request):
    body = await request.json()
    text = (body.get("text") or "").strip()
    voice = body.get("voice") or _default_voice
    speed = float(body.get("speed", 1.0) or 1.0)
    # 音色白名单校验：请求的 voice 必须属于「已发现音色集合 ∪ 默认音色」；否则回退默认音色，
    # 并通过响应头 X-Voice-Fallback 与日志给出可观测提示（不抛 500）。
    allowed = set(_voices)
    if _default_voice:
        allowed.add(_default_voice)
    fallback = False
    if voice not in allowed:
        fallback = True
        print(f"[tts] voice '{voice}' not in whitelist, fallback to '{_default_voice}'")
        voice = _default_voice
    headers = {"X-Voice-Fallback": "1"} if fallback else {}
    if not text:
        return Response(status_code=200, media_type="audio/wav", content=_silence(), headers=headers)
    audio = _synth(text, voice, speed)
    if audio is None:  # 音色无效 → 回退默认
        audio = _synth(text, _default_voice, speed)
        headers["X-Voice-Fallback"] = "1"
    if audio is None:
        return Response(status_code=503, content=b"tts failed")
    buf = io.BytesIO()
    sf.write(buf, audio, SR, format="WAV", subtype="PCM_16")
    return Response(content=buf.getvalue(), media_type="audio/wav", headers=headers)


def _synth(text, voice, speed):
    try:
        parts = []
        # 共享 _pipe 推理非线程安全，加锁串行；锁仅包住模型生成循环。
        with _pipe_lock:
            for _, _, chunk in _pipe(text, voice, speed):
                parts.append(_audio_from_chunk(chunk))
        if not parts:
            return None
        return np.concatenate(parts)
    except Exception as e:
        print(f"[tts] synth failed voice={voice}: {e}")
        return None


def _silence():
    buf = io.BytesIO()
    sf.write(buf, np.zeros(int(SR * 0.2), dtype=np.float32), SR, format="WAV", subtype="PCM_16")
    return buf.getvalue()


if __name__ == "__main__":
    _load()
    print(f"[tts] serving on 127.0.0.1:{PORT}")
    uvicorn.run(app, host="127.0.0.1", port=PORT, log_level="warning", timeout_keep_alive=300)
