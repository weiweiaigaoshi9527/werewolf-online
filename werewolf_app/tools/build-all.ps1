<#
.SYNOPSIS
  本机一键出包：Windows 桌面版 + Android APK（Linux 版必须在 GNU/Linux 上出，见 build-linux.sh）。
.DESCRIPTION
  把这些年踩过的坑一次性固化：
  1) 工程路径必须纯 ASCII（含中文会让 Flutter 原生构建报 app.dill / Cannot create link）。
  2) Flutter/Android SDK/JDK 用本机绝对路径，不走 PATH。
  3) Gradle 依赖走国内镜像（maven.google.com、repo.maven.apache.org 在本机不可达）。
  4) 产物统一收进 release-artifacts\，文件名带版本号（版本取自 pubspec.yaml）。
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\build-all.ps1
  powershell -ExecutionPolicy Bypass -File tools\build-all.ps1 -Target android -Server https://localhost:11111
#>
[CmdletBinding()]
param(
  [ValidateSet('all', 'windows', 'android')]
  [string]$Target = 'all',
  # 预置服务器地址：留空则首启走「服务器设置」页
  [string]$Server = '',
  [string]$Flutter = 'G:\flutter\bin\flutter.bat',
  [string]$AndroidSdk = 'G:\android-sdk',
  [string]$Jdk = 'D:\jdk17',
  [string]$GradleHome = 'G:\wwtool\gradle-home'
)

$ErrorActionPreference = 'Stop'
$proj = Split-Path -Parent $PSScriptRoot
$out = Join-Path $proj 'release-artifacts'
New-Item -ItemType Directory -Force -Path $out | Out-Null

if (-not (Test-Path $Flutter)) { throw "找不到 Flutter：$Flutter" }
$version = (Select-String -Path (Join-Path $proj 'pubspec.yaml') -Pattern '^version:\s*([0-9.]+)\+([0-9]+)').Matches[0].Groups[1].Value
Write-Host "工程 $proj  版本 $version" -ForegroundColor Cyan

$defines = @()
if ($Server) { $defines += "--dart-define=WW_SERVER=$Server" }

if ($Target -in @('all', 'windows')) {
  Write-Host '`n=== Windows x64 ===' -ForegroundColor Green
  Push-Location $proj
  try {
    & $Flutter build windows --release @defines
    if ($LASTEXITCODE -ne 0) { throw "flutter build windows 失败（exit=$LASTEXITCODE）" }
    $rel = Join-Path $proj 'build\windows\x64\runner\Release'
    $zip = Join-Path $out "werewolf-client-$version-windows-x64.zip"
    Remove-Item $zip -ErrorAction SilentlyContinue
    Compress-Archive -Path (Join-Path $rel '*') -DestinationPath $zip
    Write-Host "产物：$zip" -ForegroundColor Yellow
  }
  finally { Pop-Location }
}

if ($Target -in @('all', 'android')) {
  Write-Host '`n=== Android（arm64 / arm32 / 通用）===' -ForegroundColor Green
  if (-not (Test-Path $AndroidSdk)) { throw "找不到 Android SDK：$AndroidSdk" }
  if (-not (Test-Path (Join-Path $Jdk 'bin\javac.exe'))) { throw "找不到 JDK 17：$Jdk" }
  $env:JAVA_HOME = $Jdk
  $env:ANDROID_HOME = $AndroidSdk
  $env:ANDROID_SDK_ROOT = $AndroidSdk
  $env:GRADLE_USER_HOME = $GradleHome
  $env:PATH = "$Jdk\bin;$env:PATH"
  Push-Location $proj
  try {
    & $Flutter build apk --release --split-per-abi @defines
    if ($LASTEXITCODE -ne 0) { throw "flutter build apk 失败（exit=$LASTEXITCODE）" }
    $apkDir = Join-Path $proj 'build\app\outputs\flutter-apk'
    Get-ChildItem $apkDir -Filter '*.apk' | ForEach-Object {
      $abi = ($_.Name -replace 'app-([a-z0-9-]+)-release\.apk', '$1')
      $dst = Join-Path $out "werewolf-client-$version-android-$abi.apk"
      Copy-Item $_.FullName $dst -Force
      Write-Host ("产物：{0}  {1:N1} MB" -f $dst, ($_.Length / 1MB)) -ForegroundColor Yellow
    }
  }
  finally { Pop-Location }
}

Write-Host "`n全部产物在 $out" -ForegroundColor Cyan
