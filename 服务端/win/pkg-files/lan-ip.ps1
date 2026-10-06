# 输出本机当前使用的局域网 IPv4（供 .bat 调用；单行表达式，兼容 PowerShell 5.1）
$ErrorActionPreference = 'SilentlyContinue'
$ip = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.*' } | Select-Object -First 1).IPAddress
if (-not $ip) { $ip = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '127.*' } | Select-Object -First 1).IPAddress }
if (-not $ip) { $ip = '127.0.0.1' }
Write-Output $ip
