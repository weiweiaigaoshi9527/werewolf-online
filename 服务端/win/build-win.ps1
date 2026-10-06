# ============================================================
#  狼人杀 Online · 服务端（Windows x64）· 一键重建脚本
#
#  产物（输出到 win\dist\）：
#    werewolf-server-1.0.0-windows-x64.zip        便携版
#    werewolf-server-setup-1.0.0-win64.exe        NSIS 安装包
#
#  用法（在 win 目录下）：
#    powershell -ExecutionPolicy Bypass -File .\build-win.ps1
#    powershell -ExecutionPolicy Bypass -File .\build-win.ps1 -JdkHome G:\狼人杀\tools\jdk-21 -NsisDir "C:\Program Files (x86)\NSIS"
#
#  依赖：JDK 21（含 jmods，工程自带 tools\jdk-21）、target\werewolf-online-*.jar
#        可选：7-Zip（没有则退回 PowerShell 自带 Compress-Archive）
#        可选：NSIS 3（加 -SkipInstaller 可跳过安装包）
# ============================================================
param(
    [string]$JdkHome = "",
    [string]$NsisDir = "",
    [switch]$SkipInstaller
)

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$root = (Resolve-Path (Join-Path $here '..\..')).Path

if (-not $JdkHome) { $JdkHome = Join-Path $root 'tools\jdk-21' }
$jar = Join-Path $root 'target\werewolf-online-0.1.0-SNAPSHOT.jar'
$outDir = Join-Path $here 'dist'

# ---- 前置检查 ----
if (-not (Test-Path (Join-Path $JdkHome 'bin\jlink.exe'))) {
    throw "未找到 JDK（需要含 jmods 与 jlink 的 JDK 21）：'$JdkHome'"
}
if (-not (Test-Path $jar)) {
    throw "未找到服务端 jar：'$jar'`n请先在工程根目录执行 build.bat。"
}

$work = Join-Path $env:TEMP 'wwserver-win-build'
$stage = Join-Path $work 'werewolf-server'
$rt = Join-Path $stage 'runtime'
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force -Path $stage, $outDir | Out-Null

# ---- 1) jlink 精简运行时 ----
$mods = @(
    'java.se', 'jdk.unsupported', 'jdk.crypto.ec', 'jdk.crypto.cryptoki', 'jdk.charsets',
    'jdk.localedata', 'jdk.zipfs', 'jdk.jfr', 'jdk.management', 'jdk.naming.dns',
    'jdk.naming.rmi', 'jdk.net', 'jdk.httpserver', 'jdk.random', 'jdk.security.auth',
    'jdk.security.jgss', 'jdk.sctp', 'jdk.unsupported.desktop', 'jdk.management.agent', 'jdk.jartool'
) -join ','
Write-Host "[1/6] jlink 生成运行时（$mods）..."
& (Join-Path $JdkHome 'bin\jlink.exe') `
    --module-path (Join-Path $JdkHome 'jmods') `
    --add-modules $mods `
    --output $rt `
    --strip-debug --no-header-files --no-man-pages --compress=zip-6
if ($LASTEXITCODE -ne 0) { throw "jlink 失败（退出码 $LASTEXITCODE）" }

# keytool 不在 jlink 镜像里，但从 JDK 拷过来可直接用（实测通过）
Copy-Item -Force (Join-Path $JdkHome 'bin\keytool.exe') (Join-Path $rt 'bin\keytool.exe')

# ---- 2) 组装包内容 ----
Write-Host "[2/6] 组装包内容..."
Copy-Item -Force (Join-Path $here 'pkg-files\*') $stage
Copy-Item -Force $jar (Join-Path $stage 'werewolf-online.jar')
foreach ($d in @('config', 'data', 'uploads', 'logs')) {
    New-Item -ItemType Directory -Force -Path (Join-Path $stage $d) | Out-Null
}

# ---- 3) 打 ZIP ----
$zip = Join-Path $outDir 'werewolf-server-1.0.0-windows-x64.zip'
if (Test-Path $zip) { Remove-Item -Force $zip }
Write-Host "[3/6] 压缩便携版 ZIP..."
$sz = (Get-Command 7z.exe -ErrorAction SilentlyContinue).Source
if (-not $sz -and (Test-Path 'C:\Program Files\7-Zip\7z.exe')) { $sz = 'C:\Program Files\7-Zip\7z.exe' }
if ($sz) {
    Push-Location $work
    try { & $sz a -tzip -mx5 -mmt=on $zip 'werewolf-server' | Out-Null } finally { Pop-Location }
} else {
    Compress-Archive -Path $stage -DestinationPath $zip -CompressionLevel Optimal
}

# ---- 4) 编译 NSIS 安装包 ----
if ($SkipInstaller) {
    Write-Host "[4/6] 跳过安装包（-SkipInstaller）"
} else {
    Write-Host "[4/6] 编译 NSIS 安装包..."
    $mk = $null
    if ($NsisDir -and (Test-Path (Join-Path $NsisDir 'makensis.exe'))) { $mk = Join-Path $NsisDir 'makensis.exe' }
    if (-not $mk) { $mk = (Get-Command makensis.exe -ErrorAction SilentlyContinue).Source }
    if (-not $mk -and (Test-Path 'C:\Program Files (x86)\NSIS\makensis.exe')) { $mk = 'C:\Program Files (x86)\NSIS\makensis.exe' }
    if (-not $mk) {
        # 注意：NSIS 目录下 Bin\makensis.exe 与根目录 makensis.exe 并存，
        # 必须选根目录那份（Bin 那份找不到 Include\，会编译失败）
        $mk = (Get-ChildItem "$env:LOCALAPPDATA\electron-builder\Cache" -Recurse -Filter 'makensis.exe' -ErrorAction SilentlyContinue |
               Where-Object { $_.FullName -notmatch '\\Bin\\' } |
               Select-Object -First 1).FullName
    }
    if (-not $mk) {
        Write-Warning "未找到 makensis.exe，跳过安装包（便携版 ZIP 已生成）。可加 -NsisDir 指定 NSIS 目录。"
    } else {
        Copy-Item -Force (Join-Path $here 'install.nsi') (Join-Path $work 'install.nsi')
        Push-Location $work
        try {
            & $mk 'install.nsi'
            if ($LASTEXITCODE -ne 0) { throw "makensis 失败（退出码 $LASTEXITCODE）" }
        } finally { Pop-Location }
        Copy-Item -Force (Join-Path $work 'werewolf-server-setup-1.0.0-win64.exe') $outDir
    }
}

# ---- 5) 结果 ----
Write-Host "[5/6] 产物："
Get-ChildItem $outDir | ForEach-Object { Write-Host ("    " + $_.Name + "  " + [math]::Round($_.Length / 1MB, 1) + " MB") }
Write-Host "[6/6] 上架提示：把 dist 里的文件复制到工程 downloads\ 并确保 manifest.json 里有对应条目。"
