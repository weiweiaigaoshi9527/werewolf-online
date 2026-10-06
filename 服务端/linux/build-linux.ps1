# ============================================================
#  狼人杀 Online · 服务端（Linux x64）· 一键重建脚本
#
#  产物（输出到 linux\dist\）：
#    werewolf-server-1.0.0-linux-x64.tar.gz
#
#  用法（在 linux 目录下）：
#    powershell -ExecutionPolicy Bypass -File .\build-linux.ps1
#    powershell -ExecutionPolicy Bypass -File .\build-linux.ps1 -BasePackage D:\old.tar.gz -PythonExe D:\py\python.exe
#
#  ── 为什么是「只换载荷」而不是重新 jlink ──────────────────────────────
#  Linux 版运行时必须是 Linux 的 JDK 产物。而本机是 Windows 且无管理员权限：
#    · jlink 只能用 tools\jdk-21（Windows JDK），产出的是 Windows 镜像
#      （jre\bin 里全是 api-ms-win-*.dll，release 里没有 OS_NAME），Linux 上跑不起来；
#    · 解包官方 Linux JDK/JRE 也走不通：本机无法创建符号链接（需要管理员权限），
#      而 Linux 运行时恰恰依赖 145 个符号链接。
#  因此本脚本以「已发布的 tar.gz」为运行时基座，只替换包内 werewolf-online.jar：
#    jre/（Temurin 21 Linux x64，OS_NAME=Linux）、启动脚本、config、使用说明.md 全部原样保留。
#  重打包交给 Python 的 tarfile（符号链接只作元数据，不需要文件系统支持），
#  见同目录 repack-linux.py。基座若缺失，请先在能创建符号链接的环境里出包。
#
#  依赖：Git for Windows（仅用它的 GNU tar 做只读校验）、Python 3
# ============================================================
param(
    [string]$Jar = "",
    [string]$BasePackage = "",
    [string]$PythonExe = "",
    [string]$GitUsrBin = "C:\Program Files\Git\usr\bin"
)

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$root = (Resolve-Path (Join-Path $here '..\..')).Path

if (-not $Jar) { $Jar = Join-Path $root 'target\werewolf-online-0.1.0-SNAPSHOT.jar' }
if (-not $BasePackage) { $BasePackage = Join-Path $root '服务端\werewolf-server-1.0.0-linux-x64.tar.gz' }
$outDir = Join-Path $here 'dist'
$tarballName = 'werewolf-server-1.0.0-linux-x64.tar.gz'
$tarball = Join-Path $outDir $tarballName
$jarMember = 'werewolf-server/werewolf-online.jar'
$repacker = Join-Path $here 'repack-linux.py'

# ---- 前置检查 ----
if (-not (Test-Path $Jar)) { throw "未找到服务端 jar：'$Jar'`n请先在工程根目录执行 build.bat。" }
$jarSize = (Get-Item $Jar).Length
if ($jarSize -lt 20000000) { throw "jar 体积异常（$jarSize 字节，疑似 repackage 未执行）：'$Jar'" }
if (-not (Test-Path $BasePackage)) {
    throw "未找到运行时基座 tar.gz：'$BasePackage'`nLinux 版需要一份含 Linux 运行时的既有包作基座（原因见脚本头部注释）。"
}
if (-not (Test-Path $repacker)) { throw "未找到 repack-linux.py：'$repacker'" }
if (-not (Test-Path (Join-Path $GitUsrBin 'tar.exe'))) { throw "未找到 GNU tar：'$GitUsrBin'" }
if (-not $PythonExe) {
    foreach ($c in @('G:\werewolf-voice\py311\python.exe')) { if (Test-Path $c) { $PythonExe = $c; break } }
}
if (-not $PythonExe) { $PythonExe = (Get-Command python -ErrorAction SilentlyContinue).Source }
if (-not $PythonExe -or -not (Test-Path $PythonExe)) {
    throw "未找到 Python 3（重打包需要，见脚本头部注释）。可用 -PythonExe 指定。"
}
$tar = Join-Path $GitUsrBin 'tar.exe'

# msys 工具不认 "C:\..." 这类 Windows 路径（会当成远端主机），只读校验一律在 ASCII 工作目录内用相对名
$work = Join-Path $env:TEMP 'wwserver-linux-build'
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force -Path $work, $outDir, (Join-Path $work 'payload\werewolf-server') | Out-Null
Copy-Item -Force $BasePackage (Join-Path $work 'base.tar.gz')
Copy-Item -Force $Jar (Join-Path $work 'payload\werewolf-server\werewolf-online.jar')
Copy-Item -Force $repacker (Join-Path $work 'repack-linux.py')

$env:PATH = "$GitUsrBin;$env:PATH"

Push-Location $work
try {
    Write-Host "[1/5] Python 逐成员重打包（符号链接按元数据原样保留）..."
    & $PythonExe 'repack-linux.py' 'base.tar.gz' $tarballName 'payload\werewolf-server\werewolf-online.jar'
    if ($LASTEXITCODE -ne 0) { throw "重打包失败（退出码 $LASTEXITCODE）" }

    Write-Host "[2/5] 校验：包内 jar 体积与新 jar 一致..."
    $jarLine = @(& $tar -tvzf $tarballName | Where-Object { $_ -match [regex]::Escape($jarMember) + '$' })
    if (-not $jarLine) { throw "包内未找到 $jarMember" }
    $innerSize = [int64](($jarLine[0] -split '\s+') | Where-Object { $_ -match '^\d+$' } | Select-Object -First 1)
    Write-Host ("      包内 jar : " + $innerSize + " B")
    Write-Host ("      本地 jar : " + $jarSize + " B")
    if ($innerSize -ne $jarSize) { throw "包内 jar 体积与本地不一致" }

    Write-Host "[3/5] 校验：符号链接数量（Linux 运行时依赖）..."
    $members = & $tar -tzf $tarballName
    $links = @(& $tar -tvzf $tarballName | Where-Object { $_ -match '^l' })
    Write-Host ("      条目总数: " + @($members).Count + "  符号链接: " + @($links).Count)
    if (@($links).Count -lt 100) { throw "符号链接异常（仅 " + @($links).Count + " 个），运行时可能不是 Linux 版" }

    Write-Host "[4/5] 校验：运行时确为 Linux x86_64..."
    Remove-Item -Recurse -Force 'chk' -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path 'chk' | Out-Null
    & $tar -xzf $tarballName -C 'chk' 'werewolf-server/jre/release' 'werewolf-server/jre/bin/java'
    $rel = Get-Content 'chk\werewolf-server\jre\release' -Raw
    if ($rel -notmatch 'OS_NAME="Linux"') { throw "jre/release 里 OS_NAME 不是 Linux" }
    $elf = [IO.File]::ReadAllBytes((Resolve-Path 'chk\werewolf-server\jre\bin\java'))
    if (-not ($elf[0] -eq 127 -and $elf[1] -eq 69 -and $elf[2] -eq 76 -and $elf[3] -eq 70)) {
        throw "jre/bin/java 不是 ELF 可执行文件"
    }
    Write-Host "      OS_NAME=Linux、jre/bin/java 为 ELF（7f 45 4c 46）"

    Copy-Item -Force $tarballName $tarball
    Write-Host ("      已拷回 dist: " + $tarball)
} finally { Pop-Location }

Write-Host "[5/5] 完成"
$h = (Get-FileHash $tarball -Algorithm SHA256).Hash.ToLower()
Write-Host ""
Write-Host ("产物: " + $tarball)
Write-Host ("体积: " + [math]::Round((Get-Item $tarball).Length / 1MB, 1) + " MB")
Write-Host ("SHA-256: " + $h)
Write-Host "上架提示：把 dist 里的 tar.gz 复制到工程 downloads\ 并确保 manifest.json 里有对应条目。"
