@echo off
schtasks /Create /F /SC ONCE /ST 23:59 /TN WW-Voice-ASR2 /TR "cmd /c cd /d G:\werewolf-voice && venv-asr\Scripts\python.exe asr_service.py"
echo create_exit=%errorlevel%
schtasks /Run /TN WW-Voice-ASR2
echo run_exit=%errorlevel%
