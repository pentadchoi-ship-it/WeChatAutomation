[CmdletBinding()]
param(
    [ValidateSet("chat", "contacts", "box", "aperture", "butterfly", "star", "diamond", "mini", "phone", "menu")]
    [string]$Target = "aperture",
    [string]$OutputDir = "",
    [int]$SettleMilliseconds = 900,
    [switch]$DryRun
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

function Add-ProbeAssemblies {
    Add-Type -AssemblyName UIAutomationClient
    Add-Type -AssemblyName UIAutomationTypes
    Add-Type -AssemblyName System.Drawing
    Add-Type -AssemblyName System.Windows.Forms

    if (-not ([System.Management.Automation.PSTypeName]"WechatNavProbeWin32").Type) {
        Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

public static class WechatNavProbeWin32 {
    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    public static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow);

    [DllImport("user32.dll")]
    public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, UIntPtr dwExtraInfo);

    public const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    public const uint MOUSEEVENTF_LEFTUP = 0x0004;
}
"@
    }
}

function Get-WeixinProcess {
    $processes = @(Get-Process -ErrorAction SilentlyContinue |
        Where-Object { $_.ProcessName -in @("Weixin", "WeChat") })

    if ($processes.Count -eq 0) {
        throw "Weixin is not running."
    }

    $windowedProcesses = @($processes | Where-Object { $_.MainWindowHandle -ne 0 })
    if ($windowedProcesses.Count -gt 0) {
        return ($windowedProcesses | Sort-Object StartTime -Descending | Select-Object -First 1)
    }

    throw "Weixin is running, but no main window handle was found."
}

function Get-WindowBounds {
    param([IntPtr]$Handle)

    $element = [System.Windows.Automation.AutomationElement]::FromHandle($Handle)
    if ($null -eq $element) {
        throw "AutomationElement.FromHandle failed."
    }

    $rect = $element.Current.BoundingRectangle
    if ($rect.IsEmpty) {
        throw "UI Automation bounding rectangle is empty."
    }

    return [pscustomobject][ordered]@{
        left = [int][Math]::Round($rect.Left)
        top = [int][Math]::Round($rect.Top)
        right = [int][Math]::Round($rect.Right)
        bottom = [int][Math]::Round($rect.Bottom)
        width = [int][Math]::Round($rect.Width)
        height = [int][Math]::Round($rect.Height)
    }
}

function Save-WindowScreenshot {
    param(
        [Parameter(Mandatory = $true)]$Bounds,
        [Parameter(Mandatory = $true)][string]$Path
    )

    if ($Bounds.width -le 0 -or $Bounds.height -le 0) {
        throw "Window bounds are empty."
    }

    $bitmap = New-Object System.Drawing.Bitmap($Bounds.width, $Bounds.height)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.CopyFromScreen($Bounds.left, $Bounds.top, 0, 0, (New-Object System.Drawing.Size($Bounds.width, $Bounds.height)))
        $bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    }
    finally {
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

function Invoke-Click {
    param(
        [int]$ScreenX,
        [int]$ScreenY
    )

    [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point($ScreenX, $ScreenY)
    Start-Sleep -Milliseconds 80
    [WechatNavProbeWin32]::mouse_event([WechatNavProbeWin32]::MOUSEEVENTF_LEFTDOWN, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 80
    [WechatNavProbeWin32]::mouse_event([WechatNavProbeWin32]::MOUSEEVENTF_LEFTUP, 0, 0, 0, [UIntPtr]::Zero)
}

function Save-Json {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$Path
    )

    $Value | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Path -Encoding UTF8
}

$Candidates = @{
    chat = @{ x = 47; y = 142; note = "left nav chat icon" }
    contacts = @{ x = 47; y = 203; note = "left nav contacts icon" }
    box = @{ x = 47; y = 263; note = "left nav cube icon" }
    aperture = @{ x = 47; y = 322; note = "left nav aperture-like icon; likely Moments candidate" }
    butterfly = @{ x = 47; y = 383; note = "left nav butterfly-like icon" }
    star = @{ x = 47; y = 443; note = "left nav star-like icon" }
    diamond = @{ x = 47; y = 504; note = "left nav diamond-like icon" }
    mini = @{ x = 47; y = 564; note = "left nav mini-program-like icon" }
    phone = @{ x = 47; y = 698; note = "left nav phone icon" }
    menu = @{ x = 47; y = 759; note = "left nav menu icon" }
}

Add-ProbeAssemblies
$process = Get-WeixinProcess
[void][WechatNavProbeWin32]::ShowWindowAsync($process.MainWindowHandle, 9)
[void][WechatNavProbeWin32]::SetForegroundWindow($process.MainWindowHandle)
Start-Sleep -Milliseconds 350

$bounds = Get-WindowBounds $process.MainWindowHandle

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $repoRoot = Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")
    $OutputDir = Join-Path $repoRoot ("windows-probe\nav-runs\" + (Get-Date -Format "yyyyMMdd_HHmmss"))
}
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

$candidate = $Candidates[$Target]
$screenX = [int]($bounds.left + $candidate.x)
$screenY = [int]($bounds.top + $candidate.y)

$beforePath = Join-Path $OutputDir "before.png"
$afterPath = Join-Path $OutputDir "after_$Target.png"
$summaryPath = Join-Path $OutputDir "summary.json"

Save-WindowScreenshot -Bounds $bounds -Path $beforePath

if (-not $DryRun) {
    Invoke-Click -ScreenX $screenX -ScreenY $screenY
    Start-Sleep -Milliseconds $SettleMilliseconds
}

$afterBounds = Get-WindowBounds $process.MainWindowHandle
Save-WindowScreenshot -Bounds $afterBounds -Path $afterPath

$summary = [pscustomobject][ordered]@{
    createdAt = (Get-Date).ToString("o")
    processId = $process.Id
    target = $Target
    dryRun = [bool]$DryRun
    targetNote = $candidate.note
    windowBoundsBefore = $bounds
    windowBoundsAfter = $afterBounds
    click = [pscustomobject][ordered]@{
        windowX = $candidate.x
        windowY = $candidate.y
        screenX = $screenX
        screenY = $screenY
    }
    files = [pscustomobject][ordered]@{
        before = $beforePath
        after = $afterPath
    }
}

Save-Json -Value $summary -Path $summaryPath
Write-Host "WeChat nav probe complete."
Write-Host "Summary: $summaryPath"
Write-Host "Before: $beforePath"
Write-Host "After: $afterPath"
