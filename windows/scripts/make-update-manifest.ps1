<#
.SYNOPSIS
  Creates or updates the OTA update manifest (windows-update.json) for one release channel.
.EXAMPLE
  ./make-update-manifest.ps1 -Installer .\TIREXNET-Setup-1.1.0.exe -Version 1.1.0 -Channel stable `
      -BaseUrl https://github.com/HRMPMADEUA/HRMP-AETHER/releases/download/win-v1.1.0 -Notes "Faster connect","Bug fixes"
  Upload the installer and the resulting windows-update.json to the release; the app reads
  <releases/latest/download/windows-update.json> by default (editable in Settings > Updates).
#>
param(
  [Parameter(Mandatory)][string]$Installer,
  [Parameter(Mandatory)][string]$Version,
  [ValidateSet('stable','beta')][string]$Channel = 'stable',
  [Parameter(Mandatory)][string]$BaseUrl,
  [string[]]$Notes = @(),
  [string]$MinVersion = '',
  [switch]$RequireSignature,
  [string]$Out = 'windows-update.json'
)
$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^\d+\.\d+(\.\d+)?(-[0-9A-Za-z.]+)?$') { throw "Invalid version: $Version" }
if ($BaseUrl -notmatch '^https://') { throw 'BaseUrl must be https://' }
$file = Get-Item -LiteralPath $Installer
$sha = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
$manifest = @{ schema = 1; channels = @{} }
if (Test-Path -LiteralPath $Out) {
  $old = Get-Content -LiteralPath $Out -Raw | ConvertFrom-Json
  foreach ($p in $old.channels.PSObject.Properties) { $manifest.channels[$p.Name] = $p.Value }
}
$manifest.channels[$Channel] = [ordered]@{
  version = $Version
  url = ($BaseUrl.TrimEnd('/') + '/' + $file.Name)
  sha256 = $sha
  size = $file.Length
  notes = @($Notes)
  minVersion = $MinVersion
  published = (Get-Date).ToUniversalTime().ToString('o')
  requireSignature = [bool]$RequireSignature
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $Out -Encoding UTF8
Write-Host "Wrote $Out ($Channel $Version, sha256 $sha)"
