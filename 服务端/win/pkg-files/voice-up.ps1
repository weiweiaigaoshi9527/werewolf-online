# ============================================================
#  狼人杀服务端 · 语音微服务拉起脚本（SenseVoice ASR + Kokoro TTS）
#  由 start.bat 调用：找到语音家目录就拉起两个 Python 服务，
#  并把自己启动的 PID 记录到 -PidFile，供 stop.ps1 精确停止
#  （不碰别的实例已经跑起来的同名服务）。
# ============================================================
param(
    [Parameter(Mandatory = $true)][string]$VoiceHome,
    [string]$PidFile = '',
    [int]$AsrPort = 5001,
    [int]$TtsPort = 5002,
    [int]$TimeoutSec = 25
)
$ErrorActionPreference = 'SilentlyContinue'

$venvAsr = Join-Path $VoiceHome 'venv-asr\Scripts\python.exe'
$venvTts = Join-Path $VoiceHome 'venv-tts\Scripts\python.exe'
$asrPy = Join-Path $VoiceHome 'asr_service.py'
$ttsPy = Join-Path $VoiceHome 'tts_service.py'

# 让两个服务即使被搬到别处也能找到模型（脚本默认写死 G:\werewolf-voice\models）
$env:WW_MODEL_DIR = Join-Path $VoiceHome 'models'
$env:HF_HOME = Join-Path $VoiceHome 'models\hf'
$env:HF_ENDPOINT = 'https://hf-mirror.com'

function Test-Health([int]$port) {
    try {
        $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 ("http://127.0.0.1:" + $port + "/health")
        return ($r.StatusCode -eq 200)
    } catch {
        return $false
    }
}

function Start-One([string]$exePath, [string]$pyScript, [int]$port, [string]$label) {
    if (-not (Test-Path $exePath)) {
        Write-Host ("[语音] " + $label + " 缺少虚拟环境：" + $exePath)
        return $null
    }
    if (-not (Test-Path $pyScript)) {
        Write-Host ("[语音] " + $label + " 缺少服务脚本：" + $pyScript)
        return $null
    }
    if (Test-Health $port) {
        Write-Host ("[语音] " + $label + " 已在运行（127.0.0.1:" + $port + "），复用")
        return $null
    }
    Write-Host ("[语音] 拉起 " + $label + " ...")
    $p = Start-Process -FilePath $exePath -ArgumentList ('"' + $pyScript + '"') `
        -WorkingDirectory $VoiceHome -WindowStyle Minimized -PassThru
    if ($p) { return $p.Id }
    return $null
}

$launched = New-Object System.Collections.ArrayList
$pidAsr = Start-One $venvAsr $asrPy $AsrPort 'ASR(SenseVoice)'
if ($pidAsr) { [void]$launched.Add($pidAsr) }
$pidTts = Start-One $venvTts $ttsPy $TtsPort 'TTS(Kokoro)'
if ($pidTts) { [void]$launched.Add($pidTts) }

# 记录本次拉起的 PID（已在运行的服务不记，也就不会被本脚本停掉）
# 注意：本次没拉起任何服务时，**保留**已有的 PID 记录（否则下次 stop 就停不掉先前拉起的那批）
if ($PidFile -and $launched.Count -gt 0) {
    $dir = Split-Path $PidFile -Parent
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    Set-Content -Path $PidFile -Value ($launched -join "`r`n") -Encoding ASCII
    Write-Host ("[语音] 已记录本次拉起的 PID：" + ($launched -join ', '))
}

# 等服务就绪（首次加载模型可能数十秒；超时就不等了，游戏服务先起，稍后自动可用）
if (-not ((Test-Health $AsrPort) -and (Test-Health $TtsPort))) {
    Write-Host ("[语音] 等待模型加载（最多 " + $TimeoutSec + " 秒）...")
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 700
        if ((Test-Health $AsrPort) -and (Test-Health $TtsPort)) { break }
    }
}

$okAsr = Test-Health $AsrPort
$okTts = Test-Health $TtsPort
$sAsr = '未就绪'; if ($okAsr) { $sAsr = '在线' }
$sTts = '未就绪'; if ($okTts) { $sTts = '在线' }
Write-Host ("[语音] 状态：ASR=" + $sAsr + "  TTS=" + $sTts)

if ($okAsr -and $okTts) { exit 0 }
exit 1
