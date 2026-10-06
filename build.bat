@echo off
chcp 65001 >nul
REM 狼人杀 Online · 构建脚本
cd /d "%~dp0"

set JAVA_HOME=%~dp0tools\jdk-21
set PATH=%JAVA_HOME%\bin;%~dp0tools\apache-maven-3.9.16\bin;%PATH%

call mvn -B -DskipTests package
if errorlevel 1 exit /b 1
echo.
echo 构建完成: target\werewolf-online-0.1.0-SNAPSHOT.jar
