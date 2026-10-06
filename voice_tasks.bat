@echo off
REM 语音微服务计划任务注册/启动（环境由任务计划重建，与双击一致，脱离父进程继承链）
schtasks /Create /F /SC ONCE /ST 23:59 /TN WW-Voice-ASR /TR "cmd /c cd /d G:\werewolf-voice && venv-asr\Scripts\python.exe asr_service.py" >nul 2>&1
schtasks /Create /F /SC ONCE /ST 23:59 /TN WW-Voice-TTS /TR "cmd /c cd /d G:\werewolf-voice && venv-tts\Scripts\python.exe tts_service.py" >nul 2>&1
schtasks /Run /TN WW-Voice-ASR
schtasks /Run /TN WW-Voice-TTS
