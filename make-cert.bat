@echo off
chcp 65001 >nul
setlocal
REM 生成/重新生成狼人杀自签 HTTPS 证书（含本机局域网 IP 与主机名的 SAN）。IP 变了重跑一次即可。
cd /d "%~dp0"
set KEYTOOL=%~dp0tools\jdk-21\bin\keytool.exe
set KS=%~dp0config\keystore.p12
set TMPKS=%~dp0config\keystore.p12.tmp

REM 密码优先读取环境变量 SSL_PASSWORD；未设置则回退 changeit，并打印安全警告
if defined SSL_PASSWORD (
    set PASS=%SSL_PASSWORD%
) else (
    set PASS=changeit
    echo [安全警告] 未设置环境变量 SSL_PASSWORD，已使用默认密码 changeit。
    echo            生产环境请务必设置 SSL_PASSWORD 为强密码后再生成证书！
)
if not exist "%~dp0config" mkdir "%~dp0config"

set LANIP=
for /f "delims=" %%i in ('powershell -NoProfile -Command "(Get-NetIPAddress -AddressFamily IPv4 ^| Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.*' } ^| Select-Object -First 1).IPAddress"') do set LANIP=%%i

REM 先生成到临时文件，成功后再替换正式证书，避免生成失败时丢失旧证书
if exist "%TMPKS%" del "%TMPKS%" >nul 2>&1
REM 证书 SAN 覆盖 localhost / 本机主机名 / 127.0.0.1 / 局域网 IP。
REM 如需按域名访问（如 your.domain.com），把 ,dns:your.domain.com 追加到下面 -ext 的 san 列表里。
"%KEYTOOL%" -genkeypair -alias werewolf -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore "%TMPKS%" -validity 3650 -storepass %PASS% -keypass %PASS% -dname "CN=Werewolf LAN, OU=Dev, O=Werewolf, L=Local, ST=Local, C=CN" -ext "san=dns:localhost,dns:%COMPUTERNAME%,ip:127.0.0.1,ip:%LANIP%"
if errorlevel 1 (
    echo [错误] 证书生成失败，保留原有证书不变。
    if exist "%TMPKS%" del "%TMPKS%" >nul 2>&1
    endlocal
    exit /b 1
)
REM 生成成功：用新证书替换旧证书
move /y "%TMPKS%" "%KS%" >/dev/null
REM 导出公钥证书（.cer）：发给玩家安装到"受信任的根证书颁发机构"，浏览器/客户端即不再弹安全警告
"%KEYTOOL%" -exportcert -alias werewolf -storetype PKCS12 -keystore "%KS%" -storepass %PASS% -rfc -file "%~dp0config\werewolf-cert.cer" >/dev/null 2>&1
echo 已生成自签证书：%KS%  （LAN IP=%LANIP% 主机名=%COMPUTERNAME%）
echo 已导出客户端证书：%~dp0config\werewolf-cert.cer  （发给玩家双击安装到"受信任的根证书颁发机构"）
endlocal
