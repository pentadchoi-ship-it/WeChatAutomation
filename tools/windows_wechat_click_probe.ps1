[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][int]$X,
    [Parameter(Mandatory = $true)][int]$Y,
    [string]$Label = "click",
    [ValidateSet("left", "right")]
    [string]$Button = "left",
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

    if (-not ([System.Management.Automation.PSTypeName]"WechatClickProbeWin32").Type) {
        Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

public static class WechatClickProbeWin32 {
    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    public static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow);

    [DllImport("user32.dll")]
    public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, UIntPtr dwExtraInfo);

    public const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    public const uint MOUSEEVENTF_LEFTUP = 0x0004;
    public const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
    public const uint MOUSEEVENTF_RIGHTUP = 0x0010;
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
        [int]$ScreenY,
        [string]$MouseButton
    )

    [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point($ScreenX, $ScreenY)
    Start-Sleep -Milliseconds 80
    if ($MouseButton -eq "right") {
        [WechatClickProbeWin32]::mouse_event([WechatClickProbeWin32]::MOUSEEVENTF_RIGHTDOWN, 0, 0, 0, [UIntPtr]::Zero)
        Start-Sleep -Milliseconds 80
        [WechatClickProbeWin32]::mouse_event([WechatClickProbeWin32]::MOUSEEVENTF_RIGHTUP, 0, 0, 0, [UIntPtr]::Zero)
    }
    else {
        [WechatClickProbeWin32]::mouse_event([WechatClickProbeWin32]::MOUSEEVENTF_LEFTDOWN, 0, 0, 0, [UIntPtr]::Zero)
        Start-Sleep -Milliseconds 80
        [WechatClickProbeWin32]::mouse_event([WechatClickProbeWin32]::MOUSEEVENTF_LEFTUP, 0, 0, 0, [UIntPtr]::Zero)
    }
}

function Save-Json {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$Path
    )

    $Value | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Path -Encoding UTF8
}

$safeLabel = ($Label -replace "[^A-Za-z0-9_.-]", "_")
if ([string]::IsNullOrWhiteSpace($safeLabel)) {
    $safeLabel = "click"
}

Add-ProbeAssemblies
$process = Get-WeixinProcess
[void][WechatClickProbeWin32]::ShowWindowAsync($process.MainWindowHandle, 9)
[void][WechatClickProbeWin32]::SetForegroundWindow($process.MainWindowHandle)
Start-Sleep -Milliseconds 350

$bounds = Get-WindowBounds $process.MainWindowHandle

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $repoRoot = Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")
    $OutputDir = Join-Path $repoRoot ("windows-probe\click-runs\" + (Get-Date -Format "yyyyMMdd_HHmmss"))
}
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

$screenX = [int]($bounds.left + $X)
$screenY = [int]($bounds.top + $Y)
$beforePath = Join-Path $OutputDir "before.png"
$afterPath = Join-Path $OutputDir ("after_" + $safeLabel + ".png")
$summaryPath = Join-Path $OutputDir "summary.json"

Save-WindowScreenshot -Bounds $bounds -Path $beforePath

if (-not $DryRun) {
    Invoke-Click -ScreenX $screenX -ScreenY $screenY -MouseButton $Button
    Start-Sleep -Milliseconds $SettleMilliseconds
}

$afterBounds = Get-WindowBounds $process.MainWindowHandle
Save-WindowScreenshot -Bounds $afterBounds -Path $afterPath

$summary = [pscustomobject][ordered]@{
    createdAt = (Get-Date).ToString("o")
    processId = $process.Id
    label = $Label
    button = $Button
    dryRun = [bool]$DryRun
    windowBoundsBefore = $bounds
    windowBoundsAfter = $afterBounds
    click = [pscustomobject][ordered]@{
        windowX = $X
        windowY = $Y
        screenX = $screenX
        screenY = $screenY
    }
    files = [pscustomobject][ordered]@{
        before = $beforePath
        after = $afterPath
    }
}

Save-Json -Value $summary -Path $summaryPath
Write-Host "WeChat click probe complete."
Write-Host "Summary: $summaryPath"
Write-Host "Before: $beforePath"
Write-Host "After: $afterPath"
