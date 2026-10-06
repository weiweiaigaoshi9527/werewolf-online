@echo off
chcp 65001 >nul
REM 停止狼人杀 Online（Java 服务 + SenseVoice/Kokoro 两枚语音微服务）
set VOICE_HOME=G:\werewolf-voice

echo 正在停止 Java 服务...
REM 精确匹配：只杀命令行包含 werewolf-online 的 java 进程，避免误杀其它 Java 程序
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like '*werewolf-online*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }" >nul 2>&1

echo 正在停止语音微服务...
taskkill /F /FI "WINDOWTITLE eq WW-ASR*"  >nul 2>&1
taskkill /F /FI "WINDOWTITLE eq WW-TTS*"  >nul 2>&1
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='python.exe'\" | Where-Object { $_.CommandLine -match 'asr_service|tts_service' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }" >nul 2>&1

echo 已停止。
timeout /t 2 >nul
