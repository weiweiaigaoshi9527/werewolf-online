@echo off
chcp 65001 >nul
REM ============================================================
REM  生成 / 重新生成自签 HTTPS 证书（含本机局域网 IP 与主机名）
REM  换网络、IP 变了，重跑一次本脚本再启动服务即可。
REM  密码默认 changeit；可用环境变量 SSL_PASSWORD 覆盖。
REM ============================================================
setlocal
cd /d "%~dp0"
set "APP_DIR=%~dp0"
set "KEYTOOL=%APP_DIR%runtime\bin\keytool.exe"
set "KS=%APP_DIR%config\keystore.p12"
set "TMPKS=%APP_DIR%config\keystore.p12.tmp"

if not exist "%KEYTOOL%" (
    echo [错误] 未找到 keytool：%KEYTOOL%
    endlocal
    exit /b 1
)

if defined SSL_PASSWORD (
    set "PASS=%SSL_PASSWORD%"
) else (
    set "PASS=changeit"
    echo [安全警告] 未设置环境变量 SSL_PASSWORD，使用默认密码 changeit。
    echo            如服务端会暴露到公网，请先 set SSL_PASSWORD=强密码 再生成。
)

if not exist "%APP_DIR%config" mkdir "%APP_DIR%config"

set "LANIP="
for /f "delims=" %%i in ('powershell -NoProfile -ExecutionPolicy Bypass -File "%APP_DIR%lan-ip.ps1"') do set "LANIP=%%i"
if not defined LANIP set "LANIP=127.0.0.1"

REM 先生成到临时文件，成功后再替换，避免失败时丢失旧证书
if exist "%TMPKS%" del "%TMPKS%" >nul 2>&1
"%KEYTOOL%" -genkeypair -alias werewolf -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore "%TMPKS%" -validity 3650 -storepass %PASS% -keypass %PASS% -dname "CN=Werewolf LAN, OU=Dev, O=Werewolf, L=Local, ST=Local, C=CN" -ext "san=dns:localhost,dns:%COMPUTERNAME%,ip:127.0.0.1,ip:%LANIP%"
if errorlevel 1 (
    echo [错误] 证书生成失败，保留原有证书不变。
    if exist "%TMPKS%" del "%TMPKS%" >nul 2>&1
    endlocal
    exit /b 1
)

move /y "%TMPKS%" "%KS%" >nul
"%KEYTOOL%" -exportcert -alias werewolf -storetype PKCS12 -keystore "%KS%" -storepass %PASS% -rfc -file "%APP_DIR%config\werewolf-cert.cer" >nul 2>&1

echo 已生成自签证书：%KS%
echo   SAN：localhost / %COMPUTERNAME% / 127.0.0.1 / %LANIP%
echo   已导出客户端证书：%APP_DIR%config\werewolf-cert.cer
echo   （把它发给玩家双击安装到「受信任的根证书颁发机构」，浏览器就不再弹安全警告）
endlocal
exit /b 0
