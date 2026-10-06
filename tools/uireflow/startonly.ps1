
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$java = Join-Path $root 'tools\jdk-21\bin\java.exe'
$jar  = Join-Path $root 'target\werewolf-online-0.1.0-SNAPSHOT.jar'
$env:JAVA_HOME = Join-Path $root 'tools\jdk-21'
Start-Process -FilePath $java -ArgumentList @('-Xms128m','-Xmx512m','-jar',$jar,'--spring.profiles.active=https') -WindowStyle Hidden `
  -RedirectStandardOutput (Join-Path $root 'logs\ui-verify.out') -RedirectStandardError (Join-Path $root 'logs\ui-verify.err')
Write-Output 'launched'
