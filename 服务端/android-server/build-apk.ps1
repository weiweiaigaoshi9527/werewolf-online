# ============================================================
#  狼人杀服务端（Android 版）· 一键构建 APK
#
#  背景：Android Gradle Plugin 不接受含中文的工程路径（本仓库路径含“狼人杀”），
#        因此本脚本会先把工程复制到纯 ASCII 路径下再构建，最后把 APK 复制回 dist/。
#
#  用法（在 android-server 目录下）：
#      powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
#      powershell -ExecutionPolicy Bypass -File .\build-apk.ps1 -SdkDir G:\android-sdk -JdkHome G:\狼人杀\tools\jdk-21
#
#  依赖：JDK 17+（工程自带 tools\jdk-21）、Android SDK（platform 34 + build-tools 34）、
#        网络可访问 maven.aliyun.com 与 mirrors.cloud.tencent.com（首次会下载 Gradle 8.7）。
# ============================================================
param(
    [string]$SdkDir = "",
    [string]$JdkHome = ""
)

$ErrorActionPreference = 'Stop'
$src = $PSScriptRoot

if (-not $JdkHome) {
    $candidate = Join-Path (Split-Path (Split-Path $src -Parent) -Parent) 'tools\jdk-21'
    if (Test-Path $candidate) { $JdkHome = (Resolve-Path $candidate).Path }
}
if (-not $JdkHome) { $JdkHome = $env:JAVA_HOME }
if (-not (Test-Path (Join-Path $JdkHome 'bin\java.exe'))) {
    throw "未找到 JDK（需要含 javac 的 JDK 17+）：'$JdkHome'。请用 -JdkHome 指定。"
}

if (-not $SdkDir) { $SdkDir = $env:ANDROID_SDK_ROOT; if (-not $SdkDir) { $SdkDir = $env:ANDROID_HOME } }
if (-not $SdkDir) { $SdkDir = 'G:\android-sdk' }
if (-not (Test-Path (Join-Path $SdkDir 'platforms'))) {
    throw "未找到 Android SDK：'$SdkDir'。请用 -SdkDir 指定（需含 platforms/android-34 与 build-tools）。"
}

$build = if ($env:WW_BUILD_DIR) { $env:WW_BUILD_DIR } else { Join-Path $env:TEMP 'wwserver-android-build' }
Write-Host "[1/4] 同步工程到纯 ASCII 路径：$build"
robocopy $src $build /E /NFL /NDL /NJH /NJS /XD build .gradle dist | Out-Null

Write-Host "[2/4] 写入 local.properties"
Set-Content -Path (Join-Path $build 'local.properties') -Value ("sdk.dir=" + ($SdkDir -replace '\\', '/')) -Encoding ASCII

$env:JAVA_HOME = $JdkHome
$env:ANDROID_HOME = $SdkDir
$env:ANDROID_SDK_ROOT = $SdkDir

Write-Host "[3/4] 构建 release APK（首次会下载 Gradle 8.7 与 AGP 依赖）…"
Push-Location $build
try {
    & (Join-Path $build 'gradlew.bat') --no-daemon --console=plain assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败（退出码 $LASTEXITCODE）" }
} finally {
    Pop-Location
}

$apk = Join-Path $build 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $apk)) { throw "构建失败：未生成 $apk" }

$outDir = Join-Path $src 'dist'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$out = Join-Path $outDir '狼人杀服务端-android-arm64-v1.0.0.apk'
Copy-Item -Force $apk $out
Write-Host ("[4/4] 完成：" + $out + "  (" + [math]::Round((Get-Item $apk).Length / 1MB, 1) + " MB)")
