# tools/uireflow/tts_asr_roundtrip.ps1 —— 用 Windows SAPI 合成中文语音，验证 TTS/ASR 微服务
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech

$out = 'G:\狼人杀\tools\uireflow\sapi_zh.wav'
$phrase = '天黑请闭眼，狼人请睁眼'

# 找一个中文语音；没有就用默认（英文语音念中文会含糊，但仍是真实语音，可验证链路）
$v = (New-Object System.Speech.Synthesis.SpeechSynthesizer)
$zh = $v.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -like 'zh*' } | Select-Object -First 1
if ($zh) { $v.SelectVoice($zh.VoiceInfo.Name); Write-Output ("voice=" + $zh.VoiceInfo.Name) }
else { Write-Output ("voice=" + $v.VoiceInfo.Name + " (无中文语音，回退默认)") }

$v.SetOutputToWaveFile($out, (New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)))
$v.Speak($phrase)
$v.Dispose()
$len = (Get-Item $out).Length
Write-Output ("wav_bytes=" + $len)
