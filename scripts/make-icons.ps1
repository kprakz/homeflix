# Draws the HOMEFLIX icons: a red "H" on a dark rounded square.
# Writes build/icon.png (installer + exe), electron/icon.png (window) and electron/tray.png (system tray).
Add-Type -AssemblyName System.Drawing
$root = Split-Path $PSScriptRoot

function New-Icon([int]$size, [string]$out, [bool]$background, [bool]$mono = $false) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.TextRenderingHint = 'AntiAliasGridFit'
    $g.Clear([System.Drawing.Color]::Transparent)
    if ($background) {
        $r = [int]($size * 0.22)
        $p = New-Object System.Drawing.Drawing2D.GraphicsPath
        $p.AddArc(0, 0, $r, $r, 180, 90); $p.AddArc($size - $r - 1, 0, $r, $r, 270, 90)
        $p.AddArc($size - $r - 1, $size - $r - 1, $r, $r, 0, 90); $p.AddArc(0, $size - $r - 1, $r, $r, 90, 90)
        $p.CloseFigure()
        $g.FillPath((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 20, 20, 20))), $p)
    }
    # The letter is drawn as three bars so it looks the same on every PC, whatever fonts are installed.
    # macOS menu-bar ("template") icons must be black on transparent; the system tints them.
    $red = New-Object System.Drawing.SolidBrush $(if ($mono) { [System.Drawing.Color]::Black } else { [System.Drawing.Color]::FromArgb(255, 229, 9, 20) })
    $pad = if ($background) { $size * 0.24 } else { $size * 0.08 }
    $h = $size - 2 * $pad
    $bar = $h * 0.27
    $w = $h * 0.78
    $x = ($size - $w) / 2
    $g.FillRectangle($red, [float]$x, [float]$pad, [float]$bar, [float]$h)
    $g.FillRectangle($red, [float]($x + $w - $bar), [float]$pad, [float]$bar, [float]$h)
    $g.FillRectangle($red, [float]$x, [float]($pad + ($h - $bar * 0.8) / 2), [float]$w, [float]($bar * 0.8))
    $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
}

# A Windows .ico holding PNG images at each size Explorer and the taskbar ask for.
function New-Ico([string]$out) {
    $sizes = 16, 24, 32, 48, 64, 128, 256
    $images = foreach ($s in $sizes) {
        $tmp = [IO.Path]::GetTempFileName()
        New-Icon $s $tmp $true
        , [IO.File]::ReadAllBytes($tmp)
        Remove-Item $tmp
    }
    $ms = New-Object IO.MemoryStream
    $w = New-Object IO.BinaryWriter $ms
    $w.Write([uint16]0); $w.Write([uint16]1); $w.Write([uint16]$sizes.Count)
    $offset = 6 + 16 * $sizes.Count
    for ($i = 0; $i -lt $sizes.Count; $i++) {
        $s = $sizes[$i] % 256  # 256 is stored as 0
        $w.Write([byte]$s); $w.Write([byte]$s); $w.Write([byte]0); $w.Write([byte]0)
        $w.Write([uint16]1); $w.Write([uint16]32)
        $w.Write([uint32]$images[$i].Length); $w.Write([uint32]$offset)
        $offset += $images[$i].Length
    }
    foreach ($img in $images) { $w.Write($img) }
    [IO.File]::WriteAllBytes($out, $ms.ToArray())
}

New-Item -ItemType Directory -Force (Join-Path $root 'build') | Out-Null
New-Icon 512 (Join-Path $root 'build\icon.png') $true
New-Ico (Join-Path $root 'build\icon.ico')
New-Icon 256 (Join-Path $root 'electron\icon.png') $true
# For phones: Android home-screen app (manifest) and iPhone/iPad "Add to Home Screen".
New-Icon 192 (Join-Path $root 'public\icon-192.png') $true
New-Icon 512 (Join-Path $root 'public\icon-512.png') $true
New-Icon 180 (Join-Path $root 'public\apple-touch-icon.png') $true
New-Icon 32 (Join-Path $root 'electron\tray.png') $false
# macOS menu bar: 18 pt, plus @2x for Retina screens.
New-Icon 18 (Join-Path $root 'electron\trayTemplate.png') $false $true
New-Icon 36 (Join-Path $root 'electron\trayTemplate@2x.png') $false $true
"Icons written."
