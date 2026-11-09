<# Generates a valid multi-size TIREXNET application icon (PNG-in-ICO). #>
param([Parameter(Mandatory=$true)][string]$Out)
$ErrorActionPreference = 'Stop'
try {
  $Out = [System.IO.Path]::GetFullPath($Out)
  $dir = [System.IO.Path]::GetDirectoryName($Out)
  if (-not [string]::IsNullOrEmpty($dir)) { [System.IO.Directory]::CreateDirectory($dir) | Out-Null }
  Add-Type -AssemblyName System.Drawing
  function New-Bitmap([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'; $g.TextRenderingHint = 'AntiAliasGridFit'
    $g.Clear([System.Drawing.Color]::Transparent)
    $rect = New-Object System.Drawing.RectangleF 0, 0, $size, $size
    $r = $size * 0.22; $path = New-Object System.Drawing.Drawing2D.GraphicsPath; $d = $r * 2
    $path.AddArc(0, 0, $d, $d, 180, 90); $path.AddArc($size - $d, 0, $d, $d, 270, 90)
    $path.AddArc($size - $d, $size - $d, $d, $d, 0, 90); $path.AddArc(0, $size - $d, $d, $d, 90, 90)
    $path.CloseFigure()
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, ([System.Drawing.Color]::FromArgb(255,17,43,72)), ([System.Drawing.Color]::FromArgb(255,7,17,32)), 90
    $g.FillPath($brush, $path)
    $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(255,55,151,244)), ([Math]::Max(1, $size * 0.045))
    $g.DrawPath($pen, $path)
    $font = New-Object System.Drawing.Font 'Segoe UI', ($size * 0.5), ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
    $sf = New-Object System.Drawing.StringFormat; $sf.Alignment = 'Center'; $sf.LineAlignment = 'Center'
    $tb = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255,55,151,244))
    $g.DrawString('T', $font, $tb, (New-Object System.Drawing.RectangleF 0, ($size * 0.02), $size, $size), $sf)
    $g.Dispose(); return $bmp
  }
  $sizes = 16, 32, 48, 64, 128, 256
  $images = foreach ($s in $sizes) {
    $bmp = New-Bitmap $s; $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose(); , $ms.ToArray()
  }
  $fs = [System.IO.File]::Create($Out); $bw = New-Object System.IO.BinaryWriter $fs
  $bw.Write([uint16]0); $bw.Write([uint16]1); $bw.Write([uint16]$sizes.Count)
  $offset = 6 + 16 * $sizes.Count
  for ($i = 0; $i -lt $sizes.Count; $i++) {
    $s = $sizes[$i]; $w = if ($s -ge 256) { 0 } else { $s }
    $bw.Write([byte]$w); $bw.Write([byte]$w); $bw.Write([byte]0); $bw.Write([byte]0)
    $bw.Write([uint16]1); $bw.Write([uint16]32)
    $bw.Write([uint32]$images[$i].Length); $bw.Write([uint32]$offset)
    $offset += $images[$i].Length
  }
  foreach ($img in $images) { $bw.Write($img) }
  $bw.Dispose(); $fs.Dispose()
  if (-not (Test-Path -LiteralPath $Out)) { throw "Icon not written: $Out" }
  $len = (Get-Item -LiteralPath $Out).Length
  if ($len -lt 1000) { throw "Icon too small ($len bytes)" }
  $bytes = [System.IO.File]::ReadAllBytes($Out)
  $reserved = [BitConverter]::ToUInt16($bytes, 0)
  $type = [BitConverter]::ToUInt16($bytes, 2)
  $count = [BitConverter]::ToUInt16($bytes, 4)
  if ($reserved -ne 0) { throw "Reserved=$reserved (expected 0)" }
  if ($type -ne 1) { throw "Type=$type (expected 1)" }
  if ($count -ne 6) { throw "ImageCount=$count (expected 6)" }
  Write-Host "Icon written and validated: $Out ($len bytes, $count images)"
  exit 0
} catch { Write-Error $_.Exception.Message; exit 1 }
