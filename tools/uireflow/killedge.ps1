$ErrorActionPreference="SilentlyContinue"
$left = Get-CimInstance Win32_Process -Filter "Name=\"msedge.exe\"" | Where-Object { $_.CommandLine -like "*remote-debugging-port*" }
foreach ($p in $left) { Write-Output ("kill pid=" + $p.ProcessId); Stop-Process -Id $p.ProcessId -Force }
Start-Sleep -Seconds 2
$n = (Get-Process msedge -ErrorAction SilentlyContinue | Measure-Object).Count
Write-Output ("remaining msedge=" + $n)
