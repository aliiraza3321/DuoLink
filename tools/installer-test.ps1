param([string]$Out = "installer-new", [string]$Exe = "")

Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class WCap {
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
}
public class WInput {
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int X, int Y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, uint data, int extra);
}
"@

function Get-SetupProc {
  Get-Process | Where-Object { $_.MainWindowHandle -ne 0 -and $_.ProcessName -like 'DuoLink*' } |
    Sort-Object { if ($_.ProcessName -like '*Setup*') { 0 } elseif ($_.ProcessName -like '*Uninstall*') { 1 } else { 2 } } |
    Select-Object -First 1
}

function Save-Window($proc, $path) {
  $r = New-Object WCap+RECT
  [void][WCap]::GetWindowRect($proc.MainWindowHandle, [ref]$r)
  $w = $r.Right - $r.Left; $h = $r.Bottom - $r.Top
  $bmp = New-Object System.Drawing.Bitmap($w, $h)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $hdc = $g.GetHdc()
  [void][WCap]::PrintWindow($proc.MainWindowHandle, $hdc, 2)
  $g.ReleaseHdc($hdc); $g.Dispose()
  $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()
  Write-Host ("SHOT {0} {1}x{2}" -f $path, $w, $h)
  return $r
}

# Close any running copy so the installer can replace it
Get-Process DuoLink -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Milliseconds 600
if ($Exe) {
  $p = Start-Process -FilePath $Exe -PassThru
  Start-Sleep -Seconds 3
  $win = Get-SetupProc
  if (-not $win) { Write-Output "NO_WINDOW"; exit 1 }
  [void][WCap]::SetForegroundWindow($win.MainWindowHandle)
  $rect = Save-Window $win "$PWD\$Out.png"

  # Click the gold pill in the artwork (client 143..353 x 196..236)
  [void][WInput]::SetCursorPos($rect.Left + 248, $rect.Top + 272)
  [WInput]::mouse_event(2, 0, 0, 0, 0)
  Start-Sleep -Milliseconds 60
  [WInput]::mouse_event(4, 0, 0, 0, 0)
  Start-Sleep -Seconds 3
  $win2 = Get-SetupProc
  if ($win2) { Save-Window $win2 "$PWD\$Out-2.png" | Out-Null }
  Write-Output "CLICKED"
}
