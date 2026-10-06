@echo off
chcp 65001 >nul
REM 狼人杀 Online · 局域网一键启动（含 SenseVoice/Kokoro 语音微服务）
cd /d "%~dp0"

REM ===== 语音服务家目录（Python/venv/模型/服务脚本，纯 ASCII 路径，避免非 ASCII 目录导致原生库加载失败）=====
REM 优先使用已存在的环境变量；否则回退到与项目同级目录 ..\werewolf-voice（相对路径）
if not defined VOICE_HOME set VOICE_HOME=%~dp0..\werewolf-voice
if not exist "%VOICE_HOME%" echo [警告] 未找到语音服务目录 "%VOICE_HOME%"，语音功能将不可用（不影响文字模式启动）。
set WW_MODEL_DIR=%VOICE_HOME%\models
set HF_ENDPOINT=https://hf-mirror.com

set JAVA_HOME=%~dp0tools\jdk-21
set PATH=%JAVA_HOME%\bin;%PATH%

REM 启动时把这些已存在的用户名置为管理员（逗号分隔）。默认留空，可由环境变量或部署方显式设置。
REM 仅当环境变量尚未设置时才保持为空，不再硬编码任何用户名。
if not defined ADMIN_BOOTSTRAP_USERNAMES set ADMIN_BOOTSTRAP_USERNAMES=

REM ===== 校验 java.exe 是否存在，避免后续启动时出现难排查的报错 =====
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [错误] 未找到 Java：%JAVA_HOME%\bin\java.exe
    echo        请确认 tools\jdk-21 已随项目提供，或设置正确的 JAVA_HOME。
    pause
    exit /b 1
)

if not exist target\werewolf-online-0.1.0-SNAPSHOT.jar (
    echo [首次运行] 未检测到 jar，开始构建，请稍候...
    call "%~dp0build.bat"
    if errorlevel 1 (
        echo 构建失败，请检查错误信息
        pause
        exit /b 1
    )
)

REM ===== 拉起两枚语音微服务（若已安装），置 VOICE_ENABLED=true；否则退回纯文字 =====
set VOICE_ENABLED=false
if exist "%VOICE_HOME%\venv-asr\Scripts\python.exe" if exist "%VOICE_HOME%\asr_service.py" (
    start "WW-ASR" /min "%VOICE_HOME%\venv-asr\Scripts\python.exe" "%VOICE_HOME%\asr_service.py"
)
if exist "%VOICE_HOME%\venv-tts\Scripts\python.exe" if exist "%VOICE_HOME%\tts_service.py" (
    start "WW-TTS" /min "%VOICE_HOME%\venv-tts\Scripts\python.exe" "%VOICE_HOME%\tts_service.py"
)
if exist "%VOICE_HOME%\asr_service.py" if exist "%VOICE_HOME%\tts_service.py" set VOICE_ENABLED=true

REM ===== HTTPS：自签证书缺失则自动生成 =====
if not exist "%~dp0config\keystore.p12" (
    echo [首次运行] 生成自签 HTTPS 证书...
    call "%~dp0make-cert.bat"
)
set SSL_KEYSTORE=file:%~dp0config\keystore.p12

echo ============================================================
echo   狼人杀 Online 启动中（HTTPS）...
echo   本机访问:   https://localhost:11111
echo   语音模式:   %VOICE_ENABLED%  (SenseVoice/Kokoro 若首次加载模型需等待数十秒)
for /f "delims=" %%i in ('powershell -NoProfile -Command "(Get-NetIPAddress -AddressFamily IPv4 ^| Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.*' } ^| Select-Object -First 1).IPAddress"') do echo   局域网访问: https://%%i:11111   ^(麦克风需 HTTPS，首次访问请在浏览器点“高级→继续访问”信任自签证书^)
echo   停止服务:   关闭本窗口 或 运行 stop.bat
echo   按 Ctrl+C 也可停止
echo ============================================================
echo.
REM ===== 守护循环：管理员在后台点“重启”会以退出码 86 结束，这里自动重新拉起；正常退出/关闭则停止 =====
:ww_loop
"%JAVA_HOME%\bin\java.exe" -jar target\werewolf-online-0.1.0-SNAPSHOT.jar --spring.profiles.active=https
if "%ERRORLEVEL%"=="86" (
    echo.
    echo [守护] 收到重启指令，正在重新拉起服务...
    timeout /t 2 /nobreak >nul
    goto ww_loop
)
echo [守护] 服务已退出（退出码 %ERRORLEVEL%）。
