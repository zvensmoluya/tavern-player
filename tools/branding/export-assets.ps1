# Deterministic exports of the approved artwork; requires Windows System.Drawing.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$brandRoot = Join-Path $repoRoot 'assets/branding'
$resRoot = Join-Path $repoRoot 'app/src/main/res'
$masterPath = Join-Path $brandRoot 'master/mascot-dark-v1.png'
$sourceImage = [Drawing.Image]::FromFile($masterPath)

function Save-Png($Bitmap, [string]$Path) {
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path)) | Out-Null
    $Bitmap.Save($Path, [Drawing.Imaging.ImageFormat]::Png)
}

function New-Export([int]$Size, [string]$Shape = 'square', [bool]$Adaptive = $false) {
    # Supersample masks so circular exports have smooth, truly transparent edges.
    $renderSize = [Math]::Max($Size, 1024)
    $canvas = [Drawing.Bitmap]::new($renderSize, $renderSize)
    $graphics = [Drawing.Graphics]::FromImage($canvas)
    $graphics.Clear([Drawing.Color]::Transparent)
    $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $mask = [Drawing.Drawing2D.GraphicsPath]::new()
    if ($Shape -eq 'circle') {
        $mask.AddEllipse(0, 0, $renderSize, $renderSize)
        $graphics.SetClip($mask)
    } elseif ($Shape -eq 'rounded') {
        $diameter = [single]($renderSize * 0.44)
        $edge = [single]($renderSize - $diameter)
        $mask.AddArc(0, 0, $diameter, $diameter, 180, 90)
        $mask.AddArc($edge, 0, $diameter, $diameter, 270, 90)
        $mask.AddArc($edge, $edge, $diameter, $diameter, 0, 90)
        $mask.AddArc(0, $edge, $diameter, $diameter, 90, 90)
        $mask.CloseFigure()
        $graphics.SetClip($mask)
    }
    # Adaptive previews show the central 72 dp viewport of the 108 dp layer.
    $sourceInset = if ($Adaptive) { [single]($sourceImage.Width / 6) } else { 0 }
    $sourceSide = [single]($sourceImage.Width - 2 * $sourceInset)
    $sourceWrap = [Drawing.Imaging.ImageAttributes]::new()
    $sourceWrap.SetWrapMode([Drawing.Drawing2D.WrapMode]::TileFlipXY)
    $graphics.DrawImage($sourceImage, [Drawing.Rectangle]::new(0, 0, $renderSize, $renderSize),
        $sourceInset, $sourceInset, $sourceSide, $sourceSide, [Drawing.GraphicsUnit]::Pixel, $sourceWrap)
    $sourceWrap.Dispose()
    $graphics.Dispose()
    $mask.Dispose()
    $result = [Drawing.Bitmap]::new($Size, $Size)
    $resize = [Drawing.Graphics]::FromImage($result)
    $resize.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $resize.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $resize.CompositingMode = [Drawing.Drawing2D.CompositingMode]::SourceCopy
    $wrap = [Drawing.Imaging.ImageAttributes]::new()
    $wrap.SetWrapMode([Drawing.Drawing2D.WrapMode]::TileFlipXY)
    $resize.DrawImage($canvas, [Drawing.Rectangle]::new(0, 0, $Size, $Size),
        0, 0, $renderSize, $renderSize, [Drawing.GraphicsUnit]::Pixel, $wrap)
    $wrap.Dispose()
    $resize.Dispose()
    $canvas.Dispose()
    return ,$result
}

try {
    if ($sourceImage.Width -ne $sourceImage.Height) { throw 'Master artwork must be square.' }
    foreach ($size in @(1024, 512, 256, 128, 64)) {
        $bitmap = New-Export $size
        Save-Png $bitmap (Join-Path $brandRoot "exports/avatar-square-$size.png")
        $bitmap.Dispose()
    }
    foreach ($size in @(512, 256, 128)) {
        $bitmap = New-Export $size 'circle'
        Save-Png $bitmap (Join-Path $brandRoot "exports/avatar-circle-$size.png")
        $bitmap.Dispose()
    }
    # All supported Android versions (minSdk 26) use the adaptive resource.
    # Keep the original full-bleed composition in each 108 dp foreground layer.
    foreach ($entry in @{'mdpi'=108; 'hdpi'=162; 'xhdpi'=216; 'xxhdpi'=324; 'xxxhdpi'=432}.GetEnumerator()) {
        $bitmap = New-Export $entry.Value
        Save-Png $bitmap (Join-Path $resRoot "mipmap-$($entry.Key)/ic_launcher_artwork.png")
        $bitmap.Dispose()
    }
    foreach ($shape in @('circle', 'rounded')) {
        $bitmap = New-Export 512 $shape $true
        Save-Png $bitmap (Join-Path $brandRoot "previews/launcher-$shape-512.png")
        $bitmap.Dispose()
    }

    $sheet = [Drawing.Bitmap]::new(1120, 760)
    $g = [Drawing.Graphics]::FromImage($sheet)
    $g.Clear([Drawing.ColorTranslator]::FromHtml('#20191c'))
    $titleFont = [Drawing.Font]::new('Segoe UI', 23)
    $labelFont = [Drawing.Font]::new('Segoe UI', 12)
    $white = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml('#f4e9e9'))
    $muted = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml('#c5afb7'))
    $g.DrawString('Tavern Player / approved dark artwork', $titleFont, $white, 32, 24)
    $g.DrawString('Original color preserved. Launcher masks are approximate previews.', $labelFont, $muted, 34, 68)
    $columns = @(@('Master composition', 'square', $false), @('Launcher / circle', 'circle', $true), @('Launcher / rounded', 'rounded', $true))
    for ($i = 0; $i -lt $columns.Count; $i++) {
        $x = 32 + $i * 364
        $bitmap = New-Export 328 $columns[$i][1] $columns[$i][2]
        $g.DrawImageUnscaled($bitmap, $x, 112)
        $bitmap.Dispose()
        $g.DrawString($columns[$i][0], $labelFont, $white, $x, 452)
    }
    $g.DrawString('Actual pixel sizes / launcher circle', $labelFont, $white, 34, 506)
    $x = 34
    foreach ($size in @(32, 48, 64, 96, 128)) {
        $bitmap = New-Export $size 'circle' $true
        $g.DrawImageUnscaled($bitmap, $x, 542)
        $bitmap.Dispose()
        $g.DrawString("$size px", $labelFont, $muted, $x, 684)
        $x += $size + 44
    }
    $light = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml('#f5eeee'))
    $g.FillRectangle($light, 822, 510, 264, 218)
    $bitmap = New-Export 128 'circle' $true
    $g.DrawImageUnscaled($bitmap, 890, 534)
    $bitmap.Dispose()
    $dark = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml('#30252a'))
    $g.DrawString('Light background', $labelFont, $dark, 886, 682)
    $g.Dispose()
    Save-Png $sheet (Join-Path $brandRoot 'previews/contact-sheet.png')
    $sheet.Dispose()
    foreach ($resource in @($titleFont, $labelFont, $white, $muted, $light, $dark)) { $resource.Dispose() }
    Write-Output 'Exported avatars, Android launcher layers, and visual previews.'
} finally {
    $sourceImage.Dispose()
}
