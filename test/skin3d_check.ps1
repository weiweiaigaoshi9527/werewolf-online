# 3D skin pipeline end-to-end check via curl.exe (ASCII-only: PS 5.1 reads .ps1 as ANSI)
$BASE = 'https://localhost:11111'
$ts = [int][double]::Parse((Get-Date -UFormat %s))
$USER = "sk3d$ts"
$PWD_ = 'Test123456'

function C($path, $method = 'GET', $body = $null, $tok = $null) {
  $a = @('-k', '-s', '--max-time', '25', '-X', $method, "$BASE$path", '-H', 'Content-Type: application/json')
  if ($tok) { $a += @('-H', "Authorization: Bearer $tok") }
  if ($body -ne $null) {
    $tmp = [IO.Path]::GetTempFileName()
    [IO.File]::WriteAllText($tmp, ($body | ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
    $a += @('--data-binary', "@$tmp")
  }
  $out = & curl.exe @a
  if ($body -ne $null) { Remove-Item $tmp -ErrorAction SilentlyContinue }
  try { return $out | ConvertFrom-Json } catch { return $out }
}

Write-Output "### 1. register $USER"
$reg = C '/api/auth/register' 'POST' @{ username = $USER; password = $PWD_; nickname = 'SkinCheck' }
$tok = $reg.token
if (-not $tok) { Write-Output "  FAILED: $($reg | Out-String)"; exit 1 }
Write-Output "  OK userId=$($reg.user.id) gold=$($reg.user.gold)"

Write-Output "### 2. SKIN3D items in shop catalog"
$cat = C '/api/shop/catalog' 'GET' $null $tok
$skins = @($cat | Where-Object { $_.type -eq 'SKIN3D' })
Write-Output "  count = $($skins.Count)"
foreach ($it in $skins) { Write-Output ("    code={0,-10} asset={1,-9} price={2}" -f $it.code, $it.asset, $it.price) }

Write-Output "### 3. buy (new account has only 100 gold -> expected to fail)"
$target = $skins[0]
Write-Output "  buy -> $((C '/api/shop/buy' 'POST' @{ itemDefId = $target.id } $tok) | Out-String)"

Write-Output "### 4. create room + start all-AI game"
$room = C '/api/room/create' 'POST' $null $tok
Write-Output "  roomNo = $($room.roomNo)"
$start = C '/api/game/start' 'POST' @{ allBots = $true } $tok
Write-Output "  start = $($start | Out-String)"

$state = $null
for ($i = 0; $i -lt 20; $i++) {
  $s = C '/api/game/state' 'GET' $null $tok
  if ($s.seats) { $state = $s; break }
  Start-Sleep -Milliseconds 1500
}
Write-Output "### 5. skin3d delivered to the client"
if ($state) {
  $vals = $state.seats | ForEach-Object { "$($_.seat):$($_.skin3d)" }
  Write-Output "  seats = $($vals -join '  ')"
  Write-Output "  all seats have skin3d = $([bool](-not ($state.seats | Where-Object { -not $_.skin3d })))"
  Write-Output "  distinct skins = $(($state.seats | Select-Object -ExpandProperty skin3d | Sort-Object -Unique) -join ', ')"
} else { Write-Output "  no game state" }
Write-Output "  forceEnd -> $((C '/api/game/end' 'POST' $null $tok) | Out-String)"
Write-Output 'DONE'
