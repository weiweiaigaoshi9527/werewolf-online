$ErrorActionPreference='SilentlyContinue'
Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*wwelectron*' -or $_.CommandLine -like '*werewolf-client*' -or ($_.Name -eq '狼人杀.exe') } | ForEach-Object {
  Write-Output ("pid=" + $_.ProcessId + " name=" + $_.Name + " start=" + $_.CreationDate)
}
Write-Output "---"
(Get-CimInstance Win32_Process -Filter "Name='狼人杀.exe'" | Measure-Object).Count
