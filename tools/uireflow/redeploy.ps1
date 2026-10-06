$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$java = Join-Path $root 'tools\jdk-21\bin\java.exe'
$mvn  = Join-Path $root 'tools\apache-maven-3.9.16\bin\mvn.cmd'
$jar  = Join-Path $root 'target\werewolf-online-0.1.0-SNAPSHOT.jar'
$log  = Join-Path $root 'logs'
if (-not (Test-Path $log)) { New-Item -ItemType Directory -Path $log | Out-Null }
$env:JAVA_HOME = Join-Path $root 'tools\jdk-21'
Set-Location $root

Write-Output '[1/3] stop java service'
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -like '*werewolf-online*' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 2

Write-Output '[2/3] maven package'
& $mvn -o -q -DskipTests package 2>&1 | Select-Object -Last 20
if ($LASTEXITCODE -ne 0) { Write-Output "BUILD FAILED exit=$LASTEXITCODE"; exit 1 }

Write-Output '[3/3] start java service'
$a = @('-Xms128m','-Xmx512m','-jar', $jar, '--spring.profiles.active=https')
Start-Process -FilePath $java -ArgumentList $a -WindowStyle Hidden `
  -RedirectStandardOutput (Join-Path $log 'ui-verify.out') `
  -RedirectStandardError  (Join-Path $log 'ui-verify.err')
Write-Output 'launched, waiting for health...'
for ($i = 0; $i -lt 60; $i++) {
  Start-Sleep -Seconds 2
  $code = & curl.exe -s -k -o NUL -w "%{http_code}" --max-time 3 'https://127.0.0.1:11111/api/health' 2>$null
  if ("$code" -eq '200') { Write-Output "UP after $($i*2)s"; break }
}
Write-Output 'done'
