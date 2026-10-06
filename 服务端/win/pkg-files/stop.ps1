# ============================================================
#  狼人杀服务端 · 停止脚本
#   1) 停本目录的服务端（按 jar 完整路径匹配，不影响别的实例）
#   2) 停本目录启动时拉起过的语音微服务（按 logs\voice-pids.txt 记录，
#      只停自己启动的，不动别人已经跑着的 ASR/TTS）
# ============================================================
$ErrorActionPreference = 'SilentlyContinue'
$jar = Join-Path $PSScriptRoot 'werewolf-online.jar'
$pidFile = Join-Path $PSScriptRoot 'logs\voice-pids.txt'

# ---- 1) 服务端 ----
$p = @(Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like ('*' + $jar + '*') })
if ($p.Count -eq 0) {
    Write-Host '本目录的服务端未在运行。'
} else {
    foreach ($x in $p) {
        Write-Host ('正在停止服务端 PID ' + $x.ProcessId)
        Stop-Process -Id $x.ProcessId -Force
    }
    Start-Sleep -Milliseconds 900
    Write-Host '服务端已停止。'
}

# ---- 2) 本目录启动过的语音微服务 ----
if (-not (Test-Path $pidFile)) {
    Write-Host '（本目录未启动语音微服务，其它实例运行中的 ASR/TTS 保持不动）'
    exit 0
}

$script:all = @(Get-CimInstance Win32_Process)

function Get-Tree([int]$rootPid) {
    $result = New-Object System.Collections.ArrayList
    $stack = New-Object System.Collections.Stack
    $stack.Push($rootPid)
    while ($stack.Count -gt 0) {
        $cur = $stack.Pop()
        foreach ($k in @($script:all | Where-Object { $_.ParentProcessId -eq $cur })) {
            [void]$result.Add([int]$k.ProcessId)
            $stack.Push([int]$k.ProcessId)
        }
    }
    return $result
}

$roots = @()
foreach ($line in (Get-Content $pidFile)) {
    $t = $line.Trim()
    if ($t -match '^\d+$') { $roots += [int]$t }
}

$killed = 0
foreach ($r in $roots) {
    foreach ($child in (Get-Tree $r)) {
        Stop-Process -Id $child -Force -ErrorAction SilentlyContinue
    }
    if (@($script:all | Where-Object { $_.ProcessId -eq $r }).Count -gt 0) {
        Stop-Process -Id $r -Force -ErrorAction SilentlyContinue
    }
    $killed++
}
Remove-Item -Force $pidFile -ErrorAction SilentlyContinue
if ($killed -gt 0) {
    Write-Host ('已停止本次启动的语音微服务（' + $killed + ' 项）。')
}
Start-Sleep -Milliseconds 700
exit 0
