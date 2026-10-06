@echo off
chcp 65001 >nul
REM ============================================================
REM  狼人杀 Online · 服务端 停止脚本
REM  只停止「本目录」启动的服务端进程（按 jar 完整路径匹配），
REM  不会影响从别处启动的其它狼人杀服务端实例。
REM  加 /y 参数可跳过结尾的暂停（供安装/卸载程序调用）。
REM ============================================================
setlocal
cd /d "%~dp0"
echo 正在查找本目录的服务端进程...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop.ps1"
if /i not "%~1"=="/y" pause
endlocal
exit /b 0
