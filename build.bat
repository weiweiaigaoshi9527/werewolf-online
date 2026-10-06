@echo off
chcp 936 >nul
REM 狼人杀 Online · 构建脚本
REM 说明：本文件保存为 GBK(ANSI) 编码，勿改为 UTF-8。
cd /d "%~dp0"

set JAVA_HOME=%~dp0tools\jdk-21
set PATH=%JAVA_HOME%\bin;%~dp0tools\apache-maven-3.9.16\bin;%PATH%

set JAR=target\werewolf-online-0.1.0-SNAPSHOT.jar

REM ===== 先清理旧产物：若 jar 正被运行中的服务占用，这里会失败并给出明确提示 =====
if exist "%JAR%" del /f /q "%JAR%" 2>nul
if exist "%JAR%.original" del /f /q "%JAR%.original" 2>nul
if exist "%JAR%" (
    echo [错误] 无法删除旧 jar，它可能正被正在运行的服务占用。
    echo        请先运行 stop.bat 关闭服务，然后重新构建。
    exit /b 1
)

call mvn -B -DskipTests package
if errorlevel 1 (
    REM 打包失败：清掉可能残留的“普通 jar”（不含 BOOT-INF，无法 java -jar 启动），避免下次误用
    if exist "%JAR%" del /f /q "%JAR%" 2>nul
    echo.
    echo [错误] 构建失败，已清理残留产物。请检查上方 Maven 日志。
    exit /b 1
)

REM ===== 校验产物确为可执行的 Spring Boot 胖包（正常 50MB+，内含 BOOT-INF）=====
set JARSIZE=0
for %%A in ("%JAR%") do set JARSIZE=%%~zA
if %JARSIZE% LSS 20000000 (
    echo.
    echo [错误] 构建产物异常（%JARSIZE% 字节，疑似 repackage 未执行）。
    if exist "%JAR%" del /f /q "%JAR%" 2>nul
    echo        已清理该无效 jar，请检查上方日志后重试。
    exit /b 1
)

echo.
echo 构建完成: %JAR%  (%JARSIZE% 字节)
