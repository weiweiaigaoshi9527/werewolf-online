@echo off
chcp 65001 >nul
REM ===== 狼人杀 · 语音环境一键安装（SenseVoice ASR + Kokoro TTS，全部走国内镜像）=====
REM 安装到纯 ASCII 家目录 G:\werewolf-voice（非 ASCII 目录会让部分原生库加载失败）。
setlocal
set VOICE_HOME=G:\werewolf-voice
set PY311=%VOICE_HOME%\py311
set ASR=%VOICE_HOME%\venv-asr
set TTS=%VOICE_HOME%\venv-tts
set MODELS=%VOICE_HOME%\models
set PIPIDX=https://mirrors.aliyun.com/pypi/simple/
set SRC=%~dp0voice
set INSTALLER=%~dp0tools\dl\python-3.11.9-amd64.exe

echo [1/5] 准备目录 %VOICE_HOME%
if not exist "%VOICE_HOME%" mkdir "%VOICE_HOME%"
if not exist "%MODELS%" mkdir "%MODELS%"

if not exist "%PY311%\python.exe" (
    echo [2/5] 安装 Python 3.11 到 %PY311%
    if not exist "%INSTALLER%" (
        echo 缺少离线安装包 %INSTALLER%，请先手动下载 python-3.11.9-amd64.exe
        pause & exit /b 1
    )
    "%INSTALLER%" /quiet InstallAllUsers=0 TargetDir="%PY311%" PrependPath=0 Include_launcher=0 Include_test=0 CompileAll=1
) else (
    echo [2/5] 已检测到 Python，跳过
)

echo [3/5] 创建虚拟环境并安装依赖（较大，视网速数分钟～数十分钟）
if not exist "%ASR%\Scripts\python.exe" "%PY311%\python.exe" -m venv "%ASR%"
if not exist "%TTS%\Scripts\python.exe" "%PY311%\python.exe" -m venv "%TTS%"
"%ASR%\Scripts\python.exe" -m pip install --upgrade pip -i %PIPIDX%
"%ASR%\Scripts\python.exe" -m pip install -i %PIPIDX% torch torchaudio funasr modelscope soundfile numpy fastapi "uvicorn[standard]"
"%TTS%\Scripts\python.exe" -m pip install --upgrade pip -i %PIPIDX%
"%TTS%\Scripts\python.exe" -m pip install -i %PIPIDX% kokoro "misaki[zh]" soundfile numpy fastapi "uvicorn[standard]" "huggingface_hub[cli]"

echo [4/5] 复制服务脚本到 %VOICE_HOME%
copy /Y "%SRC%\asr_service.py" "%VOICE_HOME%\asr_service.py" >nul
copy /Y "%SRC%\tts_service.py" "%VOICE_HOME%\tts_service.py" >nul

echo [5/5] 下载模型（modelscope + hf-mirror）
set WW_MODEL_DIR=%MODELS%
"%ASR%\Scripts\python.exe" -c "from modelscope import snapshot_download as s; s('iic/SenseVoiceSmall', local_dir=r'%MODELS%\SenseVoiceSmall'); print('SenseVoice OK')"
"%ASR%\Scripts\python.exe" -c "from modelscope import snapshot_download as s; s('iic/punc_ct-transformer_zh-cn-common-vocab272727-pytorch', local_dir=r'%MODELS%\punc_ct'); print('Punc OK')"
set HF_ENDPOINT=https://hf-mirror.com
set HF_HOME=%MODELS%\hf
"%TTS%\Scripts\python.exe" -c "from huggingface_hub import snapshot_download as s; print('Kokoro ->', s('hexgrad/Kokoro-82M'))"

echo.
echo 完成。现在双击 start.bat 即可带语音启动（首次加载模型需等待）。
pause
