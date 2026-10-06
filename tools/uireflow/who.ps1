$ErrorActionPreference='SilentlyContinue'
Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | ForEach-Object {
  Write-Output ("pid=" + $_.ProcessId + " start=" + $_.CreationDate + " cmd=" + $_.CommandLine)
}
