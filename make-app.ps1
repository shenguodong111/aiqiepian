$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = 'C:\Users\25436\Documents\Qoder\2026-10-09\ab87a17e'
$ico  = Join-Path $root 'aiqiepian.ico'

# shortcut name "AI 切片" built from unicode code points (script kept ASCII to avoid GBK parse issues)
$cn = [string]([char]0x5207) + [string]([char]0x7247)   # qie pian
$appName = 'AI ' + $cn

# 1) draw a 256x256 icon (teal gradient + "AI"), save PNG, wrap into a .ico
$size = 256
$bmp  = New-Object System.Drawing.Bitmap($size, $size)
$g    = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = 'AntiAlias'
$g.TextRenderingHint = 'AntiAliasGridFit'
$rect = New-Object System.Drawing.Rectangle(0,0,$size,$size)
$brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, `
    [System.Drawing.Color]::FromArgb(255,18,168,186), `
    [System.Drawing.Color]::FromArgb(255,0,90,84), 45)
$g.FillRectangle($brush, $rect)
$font = New-Object System.Drawing.Font('Segoe UI', 128, [System.Drawing.FontStyle]::Bold)
$sf = New-Object System.Drawing.StringFormat
$sf.Alignment = 'Center'; $sf.LineAlignment = 'Center'
$g.DrawString('AI', $font, [System.Drawing.Brushes]::White, [System.Drawing.RectangleF]::op_Implicit($rect), $sf)
$g.Dispose()
$ms = New-Object System.IO.MemoryStream
$bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
$png = $ms.ToArray()
$bmp.Dispose()

$fs = [System.IO.File]::Create($ico)
$bw = New-Object System.IO.BinaryWriter($fs)
$bw.Write([UInt16]0); $bw.Write([UInt16]1); $bw.Write([UInt16]1)
$bw.Write([Byte]0); $bw.Write([Byte]0); $bw.Write([Byte]0); $bw.Write([Byte]0)
$bw.Write([UInt16]1); $bw.Write([UInt16]32)
$bw.Write([UInt32]$png.Length); $bw.Write([UInt32]22)
$bw.Write($png); $bw.Flush(); $bw.Close()
Write-Output ("ICO_OK " + $ico)

# 2) create Desktop shortcut -> wscript hidden launcher vbs
$desktop = [Environment]::GetFolderPath('Desktop')
$lnkPath = Join-Path $desktop ($appName + '.lnk')
$ws  = New-Object -ComObject WScript.Shell
$lnk = $ws.CreateShortcut($lnkPath)
$lnk.TargetPath = 'C:\Windows\System32\wscript.exe'
$lnk.Arguments  = '"' + (Join-Path $root 'aiqiepian-open-hidden.vbs') + '"'
$lnk.WorkingDirectory = $root
$lnk.IconLocation = $ico + ',0'
$lnk.Description = 'AI Clip - live auto-clipping tool (double-click to open)'
$lnk.Save()
Write-Output ("LNK_OK " + $lnkPath)
