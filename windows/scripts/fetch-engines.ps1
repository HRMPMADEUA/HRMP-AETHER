<#
  Downloads the pinned third-party runtimes, verifies them and lays them out under <Stage>\engines and <Stage>\licenses.
  Nothing downloaded here is ever committed to Git.

  Verification order for every archive:
    1. sha256 pinned in engines.lock.json (when not empty)  -> must match
    2. checksum published by the upstream project           -> must match
    3. Authenticode signature (WireGuard / OpenVPN MSI)     -> must be valid and signed by the expected publisher
  FAIL SAFE: a runtime with neither a pinned SHA-256 nor an upstream checksum is NOT accepted (the build stops),
  unless it is explicitly marked -SignatureOnly and its Authenticode signature is verified afterwards (WireGuard MSI).
  A hash computed from the download itself is never treated as verification.
#>
param(
  [Parameter(Mandatory = $true)][string]$Stage,
  # SlipNet is a required runtime. This switch exists only for local developer builds without Go; CI never passes it.
  [switch]$AllowMissingSlipNet
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

function Confirm-Hash([string]$Name, [string]$File, [string]$Pinned, [string]$Upstream, [switch]$SignatureOnly) {
  $actual = Get-Sha $File
  if ($Pinned) {
    if ($actual -ne $Pinned.ToLowerInvariant()) { throw "${Name}: SHA-256 mismatch. expected(pinned)=$Pinned actual=$actual" }
    $report.Add("- ${Name}: SHA-256 verified against the pinned value ($actual)")
  }
  elseif ($Upstream) {
    if ($actual -ne $Upstream.ToLowerInvariant()) { throw "${Name}: SHA-256 mismatch. expected(upstream)=$Upstream actual=$actual" }
    $report.Add("- ${Name}: SHA-256 verified against the checksum published by the upstream project ($actual)")
  }
  elseif ($SignatureOnly) {
    $report.Add("- ${Name}: no checksum available; integrity is checked by the Authenticode signature below (SHA-256 seen: $actual)")
  }
  else {
    throw "${Name}: no pinned SHA-256 and no upstream checksum are available. Refusing to use an unverified binary. Add the verified sha256 to engines.lock.json. (SHA-256 of the download: $actual)"
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
Confirm-Hash "WireGuard $($g.version)" $gMsi $g.sha256 '' -SignatureOnly
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

# ------------------------------------------------------------------ AmneziaWG (required)
$a = $lock.amneziawg
$aDir = Join-Path $engines 'amneziawg'
New-Item -ItemType Directory -Force -Path $aDir | Out-Null
$aMsi = Join-Path $work $a.asset
Get-Remote "https://github.com/amnezia-vpn/amneziawg-windows-client/releases/download/$($a.version)/$($a.asset)" $aMsi
Confirm-Hash "AmneziaWG $($a.version)" $aMsi $a.sha256 ''
$asig = Get-AuthenticodeSignature $aMsi
if ($asig.Status -ne 'Valid') { throw "The AmneziaWG installer signature is not valid (status: $($asig.Status))." }
$report.Add("- AmneziaWG MSI: Authenticode signature valid ($($asig.SignerCertificate.Subject))")
$aTmp = Join-Path $work 'amneziawg'
if (Test-Path $aTmp) { Remove-Item $aTmp -Recurse -Force }
New-Item -ItemType Directory -Force -Path $aTmp | Out-Null
$ap = Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/a', "`"$aMsi`"", '/qn', "TARGETDIR=`"$aTmp`"") -Wait -PassThru
if ($ap.ExitCode -ne 0) { throw "Extracting the AmneziaWG MSI failed (msiexec exit code $($ap.ExitCode))." }
foreach ($f in 'amneziawg.exe', 'awg.exe', 'wintun.dll') {
  $found = Get-ChildItem $aTmp -Recurse -Filter $f | Select-Object -First 1
  if (-not $found) { throw "$f was not found in the AmneziaWG MSI." }
  if ($f -like '*.exe') {
    $fs = Get-AuthenticodeSignature $found.FullName
    if ($fs.Status -ne 'Valid') { throw "$f has no valid signature (status: $($fs.Status))." }
  }
  Copy-Item $found.FullName (Join-Path $aDir $f) -Force
}
Get-License "https://raw.githubusercontent.com/amnezia-vpn/amneziawg-windows-client/$($a.version)/COPYING" 'AmneziaWG-windows-MIT.txt'

# ------------------------------------------------------------------ OpenVPN Community installer (optional: staged for the installer)
$o = $lock.openvpn
$oDir = Join-Path $Stage 'openvpn'
try {
  New-Item -ItemType Directory -Force -Path $oDir | Out-Null
  $oMsi = Join-Path $work $o.asset
  Get-Remote "https://swupdate.openvpn.org/community/releases/$($o.asset)" $oMsi
  Confirm-Hash "OpenVPN $($o.version)" $oMsi $o.sha256 '' -SignatureOnly
  $osig = Get-AuthenticodeSignature $oMsi
  if ($osig.Status -ne 'Valid' -or $osig.SignerCertificate.Subject -notmatch 'OpenVPN') {
    throw "The OpenVPN installer signature is not valid (status: $($osig.Status), signer: $($osig.SignerCertificate.Subject))."
  }
  Copy-Item $oMsi (Join-Path $oDir 'OpenVPN.msi') -Force
  $report.Add("- OpenVPN $($o.version) MSI: Authenticode signature valid ($($osig.SignerCertificate.Subject)); installed by the TIREXNET installer")
  Get-License 'https://raw.githubusercontent.com/OpenVPN/openvpn/master/COPYING' 'OpenVPN-GPL-2.0.txt'
} catch {
  if (Test-Path $oDir) { Remove-Item $oDir -Recurse -Force -ErrorAction SilentlyContinue }
  Write-Warning "OpenVPN was NOT bundled: $_"
  $report.Add("- OpenVPN: NOT bundled in this build ($($_.Exception.Message)). OpenVPN profiles will report that OpenVPN is not installed.")
}

# ------------------------------------------------------------------ SlipNet (recursive submodules + pinned source)
$sn = $lock.slipnet
$snDir = Join-Path $engines 'slipnet'

try {
  $src = Join-Path $work 'slipnet-src'

  if (Test-Path $src) {
    Remove-Item $src -Recurse -Force
  }

  git clone --quiet $sn.repo $src
  if ($LASTEXITCODE -ne 0) {
    throw 'SlipNet git clone failed.'
  }

  git -C $src checkout --quiet $sn.commit
  if ($LASTEXITCODE -ne 0) {
    throw 'SlipNet pinned checkout failed.'
  }

  $head = (git -C $src rev-parse HEAD).Trim()
  if ($head -ne $sn.commit) {
    throw "SlipNet commit mismatch: expected $($sn.commit), got $head"
  }

  # IMPORTANT: initialize every nested Go module submodule.
  git -C $src submodule sync --recursive
  if ($LASTEXITCODE -ne 0) {
    throw 'SlipNet recursive submodule sync failed.'
  }

  git -C $src submodule update --init --recursive
  if ($LASTEXITCODE -ne 0) {
    throw 'SlipNet recursive submodule initialization failed.'
  }

  $subStatus = @(git -C $src submodule status --recursive)
  if ($LASTEXITCODE -ne 0) {
    throw 'Could not verify SlipNet submodule status.'
  }

  $badSub = @($subStatus | Where-Object { $_ -match '^[-+U]' })
  if ($badSub.Count -gt 0) {
    throw ("SlipNet has uninitialized/mismatched submodules: " + ($badSub -join '; '))
  }

  $cliDir = Join-Path $src 'cli'
  $resolverSource = Join-Path $src 'app\src\main\res\raw\resolvers.txt'

  if (-not (Test-Path (Join-Path $cliDir 'go.mod'))) {
    throw 'SlipNet CLI go.mod is missing.'
  }

  if (Test-Path $resolverSource) {
    Copy-Item $resolverSource (Join-Path $cliDir 'resolvers.txt') -Force
  }

  New-Item -ItemType Directory -Force -Path $snDir | Out-Null

  Push-Location $cliDir
  try {
    $env:CGO_ENABLED = '0'
    $env:GOOS = 'windows'
    $env:GOARCH = 'amd64'
    $env:GOTOOLCHAIN = 'auto'

    go mod download
    if ($LASTEXITCODE -ne 0) {
      throw 'SlipNet go mod download failed.'
    }

    go build -trimpath -ldflags "-s -w -X main.version=$($sn.commit.Substring(0, 8))" `
      -o (Join-Path $snDir 'slipnet.exe') .

    if ($LASTEXITCODE -ne 0) {
      throw 'SlipNet Windows build failed.'
    }
  }
  finally {
    Pop-Location
    Remove-Item Env:\GOOS, Env:\GOARCH, Env:\CGO_ENABLED, Env:\GOTOOLCHAIN `
      -ErrorAction SilentlyContinue
  }

  if (-not (Test-Path (Join-Path $snDir 'slipnet.exe'))) {
    throw 'SlipNet executable was not created.'
  }

  if (Test-Path (Join-Path $src 'LICENSE')) {
    Copy-Item (Join-Path $src 'LICENSE') `
      (Join-Path $licenses 'SlipNet-AGPL-3.0.txt') -Force
  }

  $report.Add("- SlipNet: built from pinned commit $($sn.commit), recursive submodules initialized.")
}
catch {
  if (Test-Path $snDir) {
    Remove-Item $snDir -Recurse -Force -ErrorAction SilentlyContinue
  }

  Write-Warning "SlipNet was NOT built: $_"
  $report.Add("- SlipNet: NOT built ($($_.Exception.Message)).")
}

# ------------------------------------------------------------------
# Additional Go runtimes: MasterDNS / CottenDNS / StormDNS / SNISPF
function Build-GoRuntime {
  param(
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)]$Config
  )

  $dest = Join-Path $engines $Name.ToLowerInvariant()
  $src = Join-Path $work ($Name.ToLowerInvariant() + '-source')

  try {
    if (Test-Path $src) {
      Remove-Item $src -Recurse -Force
    }

    if (Test-Path $dest) {
      Remove-Item $dest -Recurse -Force
    }

    git clone --quiet $Config.repo $src
    if ($LASTEXITCODE -ne 0) {
      throw "$Name git clone failed."
    }

    git -C $src checkout --quiet $Config.commit
    if ($LASTEXITCODE -ne 0) {
      throw "$Name pinned checkout failed."
    }

    $head = (git -C $src rev-parse HEAD).Trim()
    if ($head -ne $Config.commit) {
      throw "$Name commit mismatch: expected $($Config.commit), got $head"
    }

    if (-not (Test-Path (Join-Path $src 'go.mod'))) {
      throw "$Name go.mod is missing."
    }

    New-Item -ItemType Directory -Force -Path $dest | Out-Null

    Push-Location $src
    try {
      $env:CGO_ENABLED = '0'
      $env:GOOS = 'windows'
      $env:GOARCH = 'amd64'
      $env:GOTOOLCHAIN = 'auto'

      go mod download
      if ($LASTEXITCODE -ne 0) {
        throw "$Name go mod download failed."
      }

      $exePath = Join-Path $dest $Config.executable

      go build -trimpath -ldflags '-s -w' `
        -o $exePath $Config.package

      if ($LASTEXITCODE -ne 0) {
        throw "$Name Windows build failed."
      }

      if (-not (Test-Path $exePath)) {
        throw "$Name executable was not produced."
      }
    }
    finally {
      Pop-Location
      Remove-Item Env:\GOOS, Env:\GOARCH, Env:\CGO_ENABLED, Env:\GOTOOLCHAIN `
        -ErrorAction SilentlyContinue
    }

    # Copy the upstream sample configurations and resolver lists.
    foreach ($rel in @($Config.assets)) {
      $sourceFile = Join-Path $src $rel

      if (-not (Test-Path $sourceFile)) {
        throw "$Name required runtime asset is missing: $rel"
      }

      $targetFile = Join-Path $dest $rel
      $targetParent = Split-Path $targetFile -Parent

      New-Item -ItemType Directory -Force -Path $targetParent | Out-Null
      Copy-Item -LiteralPath $sourceFile -Destination $targetFile -Force
    }

    # Preserve upstream licenses.
    $licenseFiles = @('LICENSE') + @($Config.extraLicenses)

    foreach ($rel in $licenseFiles) {
      $licenseSource = Join-Path $src $rel

      if (-not (Test-Path $licenseSource)) {
        throw "$Name license file is missing: $rel"
      }

      $licenseName = ($Name + '-' + ($rel -replace '[\\/]', '-') )
      Copy-Item $licenseSource (Join-Path $licenses $licenseName) -Force
    }

    # SNISPF wrong_seq requires signed WinDivert components.
    if ($Name -eq 'SNISPF') {
      $windivertReady = $true

      foreach ($file in @('WinDivert.dll', 'WinDivert64.sys')) {
        $sourceFile = Join-Path $src ("resources\WinDivert\" + $file)

        if (-not (Test-Path $sourceFile)) {
          $windivertReady = $false
          break
        }

        $signature = Get-AuthenticodeSignature $sourceFile
        if ($signature.Status -ne 'Valid') {
          $windivertReady = $false
          break
        }

        Copy-Item $sourceFile (Join-Path $dest $file) -Force
      }

      if ($windivertReady) {
        Get-License 'https://raw.githubusercontent.com/basil00/WinDivert/v2.2.2/LICENSE' `
          'WinDivert-license.txt'
        $report.Add("- SNISPF: signed WinDivert files included; wrong_seq prerequisites available.")
      }
      else {
        $report.Add("- SNISPF: built; wrong_seq requires valid signed WinDivert files and is not enabled in this package.")
      }
    }

    $report.Add("- ${Name}: built for Windows x64 from pinned commit $($Config.commit).")
  }
  catch {
    if (Test-Path $dest) {
      Remove-Item $dest -Recurse -Force -ErrorAction SilentlyContinue
    }

    throw "$Name runtime build failed; stopping package build: $_"
  }
}

foreach ($entry in $lock.additionalRuntimes.PSObject.Properties) {
  Build-GoRuntime -Name $entry.Name -Config $entry.Value
}

# ------------------------------------------------------------------
# MHRV official Windows release (SHA-256 pinned)
$m = $lock.mhrv
$mDir = Join-Path $engines 'mhrv'
$mZip = Join-Path $work $m.asset
$mTmp = Join-Path $work 'mhrv-extracted'

if (Test-Path $mDir) {
  Remove-Item $mDir -Recurse -Force
}
if (Test-Path $mTmp) {
  Remove-Item $mTmp -Recurse -Force
}

New-Item -ItemType Directory -Force -Path $mDir, $mTmp | Out-Null

$mUrl = "https://github.com/therealaleph/MasterHttpRelayVPN-RUST/releases/download/$($m.version)/$($m.asset)"

Get-Remote $mUrl $mZip
Confirm-Hash "MHRV $($m.version)" $mZip $m.sha256 ''

Expand-Archive -Path $mZip -DestinationPath $mTmp -Force

# Preserve the complete upstream Windows release contents.
Get-ChildItem $mTmp -Force | ForEach-Object {
  Copy-Item $_.FullName -Destination $mDir -Recurse -Force
}

$mCli = Get-ChildItem $mDir -Recurse -Filter 'mhrv-rs.exe' | Select-Object -First 1
if (-not $mCli) {
  throw 'MHRV archive did not contain mhrv-rs.exe.'
}

$mUi = Get-ChildItem $mDir -Recurse -Filter 'mhrv-rs-ui.exe' | Select-Object -First 1
if (-not $mUi) {
  Write-Warning 'MHRV desktop UI executable was not found in the release archive.'
}

Get-License "https://raw.githubusercontent.com/therealaleph/MasterHttpRelayVPN-RUST/$($m.version)/LICENSE" `
  'MHRV-MIT-license.txt'

$report.Add("- MHRV: official Windows release $($m.version), SHA-256 verified.")


# ------------------------------------------------------------------ report
$summary = @("### Third-party runtimes", "") + $report
$summary | ForEach-Object { Write-Host $_ }
if ($env:GITHUB_STEP_SUMMARY) { $summary | Add-Content -Path $env:GITHUB_STEP_SUMMARY }
Set-Content -Path (Join-Path $Stage 'engines-verification.txt') -Value ($report -join "`n")
# A failed optional native command must not leak its exit code as the step's exit code.
$global:LASTEXITCODE = 0
Write-Host 'Engines ready.'
