<#
  Downloads the pinned third-party runtimes, verifies them and lays them out under <Stage>\engines and <Stage>\licenses.
  Nothing downloaded here is ever committed to Git.

  Verification order for every archive:
    1. sha256 pinned in engines.lock.json (when not empty)  -> must match
    2. checksum published by the upstream project           -> must match
    3. Authenticode signature (WireGuard MSI)               -> must be valid and signed by WireGuard
  A runtime that could only be verified by a hash computed at download time is reported as UNPINNED
  in the build summary so that the hash can be added to engines.lock.json.
#>
param(
  [Parameter(Mandatory = $true)][string]$Stage
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 -bor [Net.SecurityProtocolType]::Tls13

$lock = Get-Content (Join-Path $PSScriptRoot 'engines.lock.json') -Raw | ConvertFrom-Json
$work = Join-Path $PSScriptRoot '..\.deps'
New-Item -ItemType Directory -Force -Path $work, $Stage | Out-Null
$work = (Resolve-Path $work).Path
$Stage = (Resolve-Path $Stage).Path
$engines = Join-Path $Stage 'engines'
$licenses = Join-Path $Stage 'licenses'
New-Item -ItemType Directory -Force -Path $engines, $licenses | Out-Null
$report = New-Object System.Collections.Generic.List[string]

function Get-Remote([string]$Url, [string]$Out) {
  Write-Host "Downloading $Url"
  $maxAttempts = 8
  $delaySec = 5
  $lastError = $null
  for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
    try {
      if (Test-Path $Out) { Remove-Item -Force $Out -ErrorAction SilentlyContinue }
      Invoke-WebRequest -Uri $Url -OutFile $Out -UseBasicParsing -TimeoutSec 180
      if ((Test-Path $Out) -and ((Get-Item $Out).Length -ge 1024)) {
        Write-Host "Download OK (attempt $attempt): $((Get-Item $Out).Length) bytes"
        return
      }
      $lastError = "File missing or too small after download"
    } catch {
      $lastError = $_.Exception.Message
      Write-Warning "Attempt $attempt/$maxAttempts failed: $lastError"
    }
    if ($attempt -lt $maxAttempts) {
      Start-Sleep -Seconds $delaySec
      $delaySec = [Math]::Min(60, $delaySec * 2)
    }
  }
  throw "Download failed after $maxAttempts attempts: $Url — $lastError"
}

function Get-Sha([string]$Path) { (Get-FileHash -Path $Path -Algorithm SHA256).Hash.ToLowerInvariant() }

function Confirm-Hash([string]$Name, [string]$File, [string]$Pinned, [string]$Upstream) {
  $actual = Get-Sha $File
  if ($Pinned) {
    if ($actual -ne $Pinned.ToLowerInvariant()) { throw "${Name}: SHA-256 mismatch. expected(pinned)=$Pinned actual=$actual" }
    $report.Add("- ${Name}: SHA-256 verified against the pinned value ($actual)")
  }
  elseif ($Upstream) {
    if ($actual -ne $Upstream.ToLowerInvariant()) { throw "${Name}: SHA-256 mismatch. expected(upstream)=$Upstream actual=$actual" }
    $report.Add("- ${Name}: SHA-256 verified against the checksum published by the upstream project ($actual)")
  }
  else {
    $report.Add("- ${Name}: **UNPINNED** - no checksum available to compare; computed SHA-256 = ``$actual`` (add it to engines.lock.json to pin it)")
    Write-Warning "$Name is unpinned. SHA-256 = $actual"
  }
}

function Get-License([string]$Url, [string]$Name) {
  try {
    Invoke-WebRequest -Uri $Url -OutFile (Join-Path $licenses $Name) -UseBasicParsing -MaximumRetryCount 3 -RetryIntervalSec 2
  } catch {
    Write-Warning "Could not download license $Name from $Url : $_"
    $report.Add("- license ${Name}: download failed (see THIRD_PARTY_NOTICES.md)")
  }
}

# ------------------------------------------------------------------ Xray-core
$x = $lock.xray
$xDir = Join-Path $engines 'xray'
New-Item -ItemType Directory -Force -Path $xDir | Out-Null
$xBase = "https://github.com/XTLS/Xray-core/releases/download/$($x.version)"
$xZip = Join-Path $work $x.asset
Get-Remote "$xBase/$($x.asset)" $xZip
$xDgst = Join-Path $work ($x.asset + '.dgst')
$xUp = ''
try {
  Get-Remote "$xBase/$($x.asset).dgst" $xDgst
  $m = [regex]::Match((Get-Content $xDgst -Raw), 'SHA2-256=\s*([0-9a-fA-F]{64})')
  if ($m.Success) { $xUp = $m.Groups[1].Value }
} catch { Write-Warning "No upstream digest for Xray: $_" }
Confirm-Hash "Xray-core $($x.version)" $xZip $x.sha256 $xUp
$xTmp = Join-Path $work 'xray'
if (Test-Path $xTmp) { Remove-Item $xTmp -Recurse -Force }
Expand-Archive -Path $xZip -DestinationPath $xTmp -Force
foreach ($f in 'xray.exe', 'geoip.dat', 'geosite.dat') {
  $src = Join-Path $xTmp $f
  if (Test-Path $src) { Copy-Item $src $xDir -Force }
}
if (-not (Test-Path (Join-Path $xDir 'xray.exe'))) { throw 'xray.exe was not found in the Xray archive.' }
Get-License "https://raw.githubusercontent.com/XTLS/Xray-core/$($x.version)/LICENSE" 'Xray-core-MPL-2.0.txt'

# ------------------------------------------------------------------ tun2socks
$t = $lock.tun2socks
$tDir = Join-Path $engines 'tun2socks'
New-Item -ItemType Directory -Force -Path $tDir | Out-Null
$tBase = "https://github.com/xjasonlyu/tun2socks/releases/download/$($t.version)"
$tZip = Join-Path $work $t.asset
Get-Remote "$tBase/$($t.asset)" $tZip
$tUp = ''
foreach ($name in @('checksums.txt', "$($t.asset).sha256", 'tun2socks.sha256sum')) {
  try {
    $cs = Join-Path $work 'tun2socks-checksums.txt'
    Invoke-WebRequest -Uri "$tBase/$name" -OutFile $cs -UseBasicParsing -ErrorAction Stop
    $line = (Get-Content $cs) | Where-Object { $_ -match [regex]::Escape($t.asset) } | Select-Object -First 1
    if (-not $line) { $line = (Get-Content $cs | Select-Object -First 1) }
    $m = [regex]::Match($line, '([0-9a-fA-F]{64})')
    if ($m.Success) { $tUp = $m.Groups[1].Value; break }
  } catch { }
}
Confirm-Hash "tun2socks $($t.version)" $tZip $t.sha256 $tUp
$tTmp = Join-Path $work 'tun2socks'
if (Test-Path $tTmp) { Remove-Item $tTmp -Recurse -Force }
Expand-Archive -Path $tZip -DestinationPath $tTmp -Force
$exe = Get-ChildItem $tTmp -Recurse -Filter '*.exe' | Select-Object -First 1
if (-not $exe) { throw 'No executable was found in the tun2socks archive.' }
Copy-Item $exe.FullName (Join-Path $tDir 'tun2socks.exe') -Force
Get-License "https://raw.githubusercontent.com/xjasonlyu/tun2socks/$($t.version)/LICENSE" 'tun2socks-MIT.txt'

# ------------------------------------------------------------------ Wintun
$w = $lock.wintun
$wZip = Join-Path $work $w.asset
Get-Remote "https://www.wintun.net/builds/$($w.asset)" $wZip
Confirm-Hash "Wintun $($w.version)" $wZip $w.sha256 ''
$wTmp = Join-Path $work 'wintun'
if (Test-Path $wTmp) { Remove-Item $wTmp -Recurse -Force }
Expand-Archive -Path $wZip -DestinationPath $wTmp -Force
$dll = Join-Path $wTmp 'wintun\bin\amd64\wintun.dll'
if (-not (Test-Path $dll)) { throw 'wintun.dll (amd64) was not found in the Wintun archive.' }
$sig = Get-AuthenticodeSignature $dll
if ($sig.Status -ne 'Valid') { throw "wintun.dll has no valid Authenticode signature (status: $($sig.Status))." }
$report.Add("- wintun.dll: Authenticode signature valid ($($sig.SignerCertificate.Subject))")
Copy-Item $dll (Join-Path $tDir 'wintun.dll') -Force
Get-ChildItem $wTmp -Recurse -Filter 'LICENSE*' | Select-Object -First 1 | ForEach-Object { Copy-Item $_.FullName (Join-Path $licenses 'Wintun-license.txt') -Force }

# ------------------------------------------------------------------ WireGuard
$g = $lock.wireguard
$gDir = Join-Path $engines 'wireguard'
New-Item -ItemType Directory -Force -Path $gDir | Out-Null
$gMsi = Join-Path $work $g.asset
Get-Remote "https://download.wireguard.com/windows-client/$($g.asset)" $gMsi
Confirm-Hash "WireGuard $($g.version)" $gMsi $g.sha256 ''
$gsig = Get-AuthenticodeSignature $gMsi
if ($gsig.Status -ne 'Valid' -or $gsig.SignerCertificate.Subject -notmatch 'WireGuard') {
  throw "The WireGuard installer signature is not valid (status: $($gsig.Status), signer: $($gsig.SignerCertificate.Subject))."
}
$report.Add("- WireGuard MSI: Authenticode signature valid ($($gsig.SignerCertificate.Subject))")
$gTmp = Join-Path $work 'wireguard'
if (Test-Path $gTmp) { Remove-Item $gTmp -Recurse -Force }
New-Item -ItemType Directory -Force -Path $gTmp | Out-Null
$p = Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/a', "`"$gMsi`"", '/qn', "TARGETDIR=`"$gTmp`"") -Wait -PassThru
if ($p.ExitCode -ne 0) { throw "Extracting the WireGuard MSI failed (msiexec exit code $($p.ExitCode))." }
$wg = Get-ChildItem $gTmp -Recurse -Filter 'wireguard.exe' | Select-Object -First 1
if (-not $wg) { throw 'wireguard.exe was not found in the WireGuard MSI.' }
$exeSig = Get-AuthenticodeSignature $wg.FullName
if ($exeSig.Status -ne 'Valid') { throw "wireguard.exe has no valid signature (status: $($exeSig.Status))." }
Copy-Item $wg.FullName (Join-Path $gDir 'wireguard.exe') -Force
Get-License "https://raw.githubusercontent.com/WireGuard/wireguard-windows/v$($g.version)/COPYING" 'WireGuard-windows-MIT.txt'

# ------------------------------------------------------------------ report
$summary = @("### Third-party runtimes", "") + $report
$summary | ForEach-Object { Write-Host $_ }
if ($env:GITHUB_STEP_SUMMARY) { $summary | Add-Content -Path $env:GITHUB_STEP_SUMMARY }
Set-Content -Path (Join-Path $Stage 'engines-verification.txt') -Value ($report -join "`n")
Write-Host 'Engines ready.'
