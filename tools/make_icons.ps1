# Generate Obsession launcher icons from tools/icon_src.png (ASCII path is intentional).
# Layers:
#   foreground : whole artwork scaled to 87% of the 108dp canvas (system mask trims the white rim)
#   background : solid #14161F so mask edges never flash white
#   legacy     : pre-Android-8 square/round icons rendered from the same art
param()
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$root   = "E:\study\class\cleantable\app\src\main\res"
$src    = "E:\study\class\cleantable\tools\icon_src.png"
$source = [System.Drawing.Image]::FromFile($src)

function Save-Scaled([System.Drawing.Image]$img, [int]$size, [string]$path) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode  = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode      = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.PixelOffsetMode    = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $g.DrawImage($img, 0, 0, $size, $size)
    $g.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Output "wrote $path ($size px)"
}

function Save-Foreground([System.Drawing.Image]$img, [int]$canvas, [string]$path, [double]$ratio) {
    $bmp = New-Object System.Drawing.Bitmap($canvas, $canvas)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode  = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode      = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.PixelOffsetMode    = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)
    $inner = [int]([Math]::Round($canvas * $ratio))
    $offset = [int]([Math]::Round(($canvas - $inner) / 2.0))
    $g.DrawImage($img, $offset, $offset, $inner, $inner)
    $g.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Output "wrote $path ($canvas px, inner $inner)"
}

function Save-Solid([int]$canvas, [string]$hex, [string]$path) {
    $bmp = New-Object System.Drawing.Bitmap($canvas, $canvas)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $color = [System.Drawing.ColorTranslator]::FromHtml($hex)
    $g.Clear($color)
    $g.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Output "wrote $path (solid $hex)"
}

$densities = @(
    @{ dir = "mipmap-mdpi";    legacy = 48;  layer = 108 },
    @{ dir = "mipmap-hdpi";    legacy = 72;  layer = 162 },
    @{ dir = "mipmap-xhdpi";   legacy = 96;  layer = 216 },
    @{ dir = "mipmap-xxhdpi";  legacy = 144; layer = 324 },
    @{ dir = "mipmap-xxxhdpi"; legacy = 192; layer = 432 }
)

foreach ($d in $densities) {
    $dir = Join-Path $root $d.dir
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Save-Foreground $source $d.layer (Join-Path $dir "ic_launcher_foreground.png") 0.87
    Save-Solid      $d.layer "#14161F" (Join-Path $dir "ic_launcher_background.png")
    Save-Scaled     $source $d.legacy (Join-Path $dir "ic_launcher_legacy.png")
}

# Round legacy icon (circular clip) at xxxhdpi, downscale others from it
$roundBig = "E:\study\class\cleantable\tools\ic_round_192.png"
$bmp = New-Object System.Drawing.Bitmap(192, 192)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.SmoothingMode     = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
$g.Clear([System.Drawing.Color]::Transparent)
$clip = New-Object System.Drawing.Drawing2D.GraphicsPath
$clip.AddEllipse(0, 0, 192, 192)
$g.SetClip($clip)
$g.DrawImage($source, 0, 0, 192, 192)
$g.Dispose()
$clip.Dispose()
$bmp.Save($roundBig, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Output "wrote $roundBig"

foreach ($d in $densities) {
    $dir = Join-Path $root $d.dir
    $img = [System.Drawing.Image]::FromFile($roundBig)
    Save-Scaled $img $d.legacy (Join-Path $dir "ic_launcher_round_legacy.png")
    $img.Dispose()
}

$source.Dispose()
Remove-Item $roundBig
Write-Output "DONE"
