$ErrorActionPreference = 'SilentlyContinue'
$rows = @()
Get-NetFirewallRule -Direction Inbound | ForEach-Object {
  $rule = $_
  $port = $rule | Get-NetFirewallPortFilter
  $app = $rule | Get-NetFirewallApplicationFilter
  if (($port.LocalPort -match '8080|11111|5001|5002') -or ($app.Program -match 'java|wolf')) {
    $rows += [pscustomobject]@{
      Name = $rule.DisplayName
      Action = $rule.Action
      Enabled = $rule.Enabled
      Protocol = $port.Protocol
      Port = $port.LocalPort
      Program = $app.Program
    }
  }
}
if ($rows.Count -eq 0) { 'NO_MATCHING_RULES' } else { $rows | Format-Table -AutoSize | Out-String -Width 220 }
