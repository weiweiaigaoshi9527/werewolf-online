"""
SenseVoice-Small ASR 微服务（独立进程，运行于 venv-asr）。
接口：
  GET  /health                -> {ok, model, ...}
  POST /transcribe?sr=16000   -> 请求体为裸 PCM16 单声道小端音频，返回 {"text": "带标点的一句话/一段话"}
设计：Java 侧已用能量 VAD 把麦克风切成短语再发过来，这里只做“离线整句识别 + 加标点”，
不依赖流式，鲁棒且足够近实时。任一段静音或失败都返回空串，由上层忽略。
环境变量（可覆盖默认路径）：
  WW_MODEL_DIR   模型根目录，默认 G:\\werewolf-voice\\models
  WW_ASR_PORT    监听端口，默认 5001
"""
import io
import os
import re
import tempfile
import threading

import numpy as np
import soundfile as sf
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
import uvicorn

MODEL_DIR = os.environ.get("WW_MODEL_DIR", r"G:\werewolf-voice\models")
ASR_MODEL = os.environ.get("WW_ASR_MODEL", os.path.join(MODEL_DIR, "SenseVoiceSmall"))
PUNC_MODEL = os.environ.get("WW_PUNC_MODEL", os.path.join(MODEL_DIR, "punc_ct"))
PORT = int(os.environ.get("WW_ASR_PORT", "5001"))
LANG = os.environ.get("WW_ASR_LANG", "zh")   # 中文对局固定 zh，识别更稳（可选 auto）
TAG = re.compile(r"<\|[^|>]*\|>")

app = FastAPI()
_asr = None
_punc = None
# 模块级模型实例在 FastAPI 多并发下会被多个请求共享；funasr 模型推理非线程安全，
# 用一把互斥锁串行化真正的推理调用，避免并发竞态（不影响 HTTP 接口路径与请求/响应结构）。
_model_lock = threading.Lock()


def _load():
    global _asr, _punc
    from funasr import AutoModel
    _asr = AutoModel(model=ASR_MODEL, disable_update=True, trust_remote_code=True)
    try:
        _punc = AutoModel(model=PUNC_MODEL, disable_update=True)
    except Exception as e:  # 标点模型缺失也只管识别
        print("[asr] punctuation model unavailable:", e)
        _punc = None


@app.get("/health")
def health():
    return {"ok": _asr is not None, "model": ASR_MODEL, "punc": _punc is not None}


@app.post("/transcribe")
async def transcribe(request: Request, sr: int = 16000):
    data = await request.body()
    if not data or len(data) < 640:
        return JSONResponse({"text": ""})
    samples = np.frombuffer(data, dtype=np.int16).astype(np.float32) / 32768.0
    # SenseVoice 走文件输入兼容性最好
    fd, path = tempfile.mkstemp(suffix=".wav")
    os.close(fd)
    try:
        sf.write(path, samples, sr, format="WAV", subtype="PCM_16")
        # 推理为共享模型实例，需加锁串行；锁只包住模型调用，缩短临界区。
        with _model_lock:
            res = _asr.generate(input=path, cache={}, language=LANG, use_itn=True)
        text = ""
        if res:
            text = res[0].get("text", "")
        text = TAG.sub("", text).strip()
        if text and _punc is not None:
            try:
                with _model_lock:
                    pr = _punc.generate(input=text)
                if pr:
                    text = pr[0].get("text", text)
            except Exception:
                pass
        return JSONResponse({"text": text})
    except Exception as e:
        print("[asr] transcribe error:", e)
        return JSONResponse({"text": ""}, status_code=200)
    finally:
        try:
            os.remove(path)
        except OSError:
            pass


if __name__ == "__main__":
    _load()
    print(f"[asr] SenseVoice loaded, serving on 127.0.0.1:{PORT}")
    uvicorn.run(app, host="127.0.0.1", port=PORT, log_level="warning", timeout_keep_alive=300)
