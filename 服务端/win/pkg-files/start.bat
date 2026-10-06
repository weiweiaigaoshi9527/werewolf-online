@echo off
chcp 65001 >nul
REM ============================================================
REM  狼人杀 Online · 服务端（Windows x64）一键启动
REM  内置 Java 21 运行时，目标机无需安装 Java。
REM
REM  可选环境变量：
REM     WW_PORT        监听端口（默认 11111）
REM     WW_HEAP        JVM 堆上限（默认 1024m，人少可 512m，人多可 2048m）
REM     WW_BIND        只监听指定地址，如 set WW_BIND=192.168.1.5
REM     WW_VOICE_HOME  语音微服务目录（含 venv-asr/venv-tts/models/*_service.py）
REM ============================================================
setlocal
cd /d "%~dp0"
set "APP_DIR=%~dp0"
set "JAVA=%APP_DIR%runtime\bin\java.exe"
set "JAR=%APP_DIR%werewolf-online.jar"
if not defined WW_PORT set "WW_PORT=11111"
if not defined WW_HEAP set "WW_HEAP=1024m"
set "BIND="
if defined WW_BIND set "BIND=--server.address=%WW_BIND%"

if not exist "%JAVA%" (
    echo [错误] 未找到内置 Java 运行时：%JAVA%
    echo        请确认压缩包已完整解压（不要只把 jar 拷出来）。
    pause
    exit /b 1
)
if not exist "%JAR%" (
    echo [错误] 未找到服务端程序：%JAR%
    pause
    exit /b 1
)

if not exist "%APP_DIR%config"  mkdir "%APP_DIR%config"
if not exist "%APP_DIR%data"    mkdir "%APP_DIR%data"
if not exist "%APP_DIR%uploads" mkdir "%APP_DIR%uploads"
if not exist "%APP_DIR%logs"    mkdir "%APP_DIR%logs"

REM ---- 自签 HTTPS 证书（浏览器麦克风需要安全上下文）；缺失则自动生成 ----
set "USE_HTTPS=1"
if not exist "%APP_DIR%config\keystore.p12" (
    echo [首次运行] 生成自签 HTTPS 证书...
    call "%APP_DIR%make-cert.bat"
    if errorlevel 1 set "USE_HTTPS=0"
)

REM ---- 语音微服务（SenseVoice ASR + Kokoro TTS）----
REM  语音运行时体量很大（Python + torch + 模型，数 GB），不随安装包分发，需单独安装。
REM  这里按优先级自动查找语音家目录；找到就拉起两个服务并启用语音，找不到退回纯文字模式。
set "VOICE_ENABLED=false"
set "VOICE_HOME="
set "VOICE_DESC=未安装（纯文字模式）"
if defined WW_VOICE_HOME if exist "%WW_VOICE_HOME%\asr_service.py" set "VOICE_HOME=%WW_VOICE_HOME%"
if not defined VOICE_HOME if exist "%APP_DIR%voice\asr_service.py" set "VOICE_HOME=%APP_DIR%voice"
if not defined VOICE_HOME if exist "%APP_DIR%..\werewolf-voice\asr_service.py" set "VOICE_HOME=%APP_DIR%..\werewolf-voice"
if not defined VOICE_HOME if exist "G:\werewolf-voice\asr_service.py" set "VOICE_HOME=G:\werewolf-voice"

if defined VOICE_HOME (
    echo [语音] 语音家目录：%VOICE_HOME%
    powershell -NoProfile -ExecutionPolicy Bypass -File "%APP_DIR%voice-up.ps1" -VoiceHome "%VOICE_HOME%" -PidFile "%APP_DIR%logs\voice-pids.txt"
    set "VOICE_ENABLED=true"
    set "VOICE_DESC=已启用（%VOICE_HOME%）"
) else (
    echo [语音] 未检测到语音服务，将以纯文字模式运行：打字发言、AI 对局、房间、战绩全部正常，
    echo        仅浏览器麦克风（语音同传）不可用。
    echo        如需语音：把语音目录放到本目录下的 voice\ ，或用
    echo            set WW_VOICE_HOME=你的语音目录
    echo        指定已有语音环境后再启动；详见 使用说明.txt 的「语音」一节。
)

REM ---- 局域网 IP ----
set "LANIP="
for /f "delims=" %%i in ('powershell -NoProfile -ExecutionPolicy Bypass -File "%APP_DIR%lan-ip.ps1"') do set "LANIP=%%i"
if not defined LANIP set "LANIP=127.0.0.1"

if "%USE_HTTPS%"=="1" (set "SCHEME=https") else (set "SCHEME=http")

echo ============================================================
echo   狼人杀 Online 服务端（Windows）启动中...
echo   本机访问:   %SCHEME%://localhost:%WW_PORT%
echo   局域网访问: %SCHEME%://%LANIP%:%WW_PORT%     ^<== 把这个地址发给玩家
echo   后台管理:   %SCHEME%://localhost:%WW_PORT%/admin   ^(首个注册账号即管理员^)
echo   语音同传:   %VOICE_DESC%
echo   数据目录:   %APP_DIR%data
echo   停止服务:   关闭本窗口 或 双击 stop.bat
if "%USE_HTTPS%"=="1" (
    echo   证书提示: 自签证书，浏览器首次访问点「高级 - 继续访问」即可；
    echo             也可把 config\werewolf-cert.cer 发给玩家，双击安装到
    echo             「受信任的根证书颁发机构」，之后就不再弹警告。
) else (
    echo   警告: 未生成证书，当前以 HTTP 启动；麦克风语音需要 HTTPS。
)
echo ============================================================
echo.

REM ---- 守护循环：后台点「重启」会以退出码 86 结束，这里自动重新拉起 ----
:ww_loop
if "%USE_HTTPS%"=="1" (
    "%JAVA%" -Dfile.encoding=UTF-8 -Djava.awt.headless=true -Xms128m -Xmx%WW_HEAP% -jar "%JAR%" --server.port=%WW_PORT% %BIND% --spring.profiles.active=https
) else (
    "%JAVA%" -Dfile.encoding=UTF-8 -Djava.awt.headless=true -Xms128m -Xmx%WW_HEAP% -jar "%JAR%" --server.port=%WW_PORT% %BIND%
)
if "%ERRORLEVEL%"=="86" (
    echo.
    echo [守护] 收到重启指令，2 秒后重新拉起服务...
    timeout /t 2 /nobreak >nul
    goto ww_loop
)
echo.
echo [守护] 服务已退出（退出码 %ERRORLEVEL%）。
pause
endlocal
