[CmdletBinding()]
param(
    [string]$ProfilePath = "",
    [int]$SettleMilliseconds = 900,
    [switch]$RefuseIfComposeEditorLikelyOpen,
    [switch]$DryRun
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

function New-Utf16String {
    param([Parameter(Mandatory = $true)][int[]]$CodePoints)

    return -join ($CodePoints | ForEach-Object { [char]$_ })
}

$MomentsTitle = New-Utf16String @(0x670b, 0x53cb, 0x5708)

function Add-ProbeAssemblies {
    Add-Type -AssemblyName UIAutomationClient
    Add-Type -AssemblyName UIAutomationTypes
    Add-Type -AssemblyName System.Drawing
    Add-Type -AssemblyName System.Windows.Forms

    if (-not ([System.Management.Automation.PSTypeName]"WechatComposeStage1Win32").Type) {
        Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

public static class WechatComposeStage1Win32 {
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
        throw "Weixin is not running. Start Weixin and log in before running this script."
    }

    $windowedProcesses = @($processes | Where-Object { $_.MainWindowHandle -ne 0 })
    if ($windowedProcesses.Count -gt 0) {
        return ($windowedProcesses | Sort-Object StartTime -Descending | Select-Object -First 1)
    }

    throw "Weixin is running, but no main window handle was found."
}

function Get-WindowElement {
    param([System.Diagnostics.Process]$Process)

    $element = [System.Windows.Automation.AutomationElement]::FromHandle($Process.MainWindowHandle)
    if ($null -eq $element) {
        throw "AutomationElement.FromHandle failed."
    }
    return $element
}

function Get-WindowBounds {
    param([System.Windows.Automation.AutomationElement]$Element)

    $rect = $Element.Current.BoundingRectangle
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

function Invoke-WindowClick {
    param(
        [Parameter(Mandatory = $true)]$Bounds,
        [Parameter(Mandatory = $true)][int]$X,
        [Parameter(Mandatory = $true)][int]$Y,
        [string]$Label = "click"
    )

    $screenX = [int]($Bounds.left + $X)
    $screenY = [int]($Bounds.top + $Y)
    Write-Host ("{0}: window=({1},{2}) screen=({3},{4})" -f $Label, $X, $Y, $screenX, $screenY)

    if ($DryRun) {
        return
    }

    [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point($screenX, $screenY)
    Start-Sleep -Milliseconds 80
    [WechatComposeStage1Win32]::mouse_event([WechatComposeStage1Win32]::MOUSEEVENTF_LEFTDOWN, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 80
    [WechatComposeStage1Win32]::mouse_event([WechatComposeStage1Win32]::MOUSEEVENTF_LEFTUP, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds $SettleMilliseconds
}

function Get-ScreenPixel {
    param(
        [Parameter(Mandatory = $true)]$Bounds,
        [Parameter(Mandatory = $true)][int]$X,
        [Parameter(Mandatory = $true)][int]$Y
    )

    $bitmap = New-Object System.Drawing.Bitmap(1, 1)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.CopyFromScreen($Bounds.left + $X, $Bounds.top + $Y, 0, 0, (New-Object System.Drawing.Size(1, 1)))
        return $bitmap.GetPixel(0, 0)
    }
    finally {
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

function Test-ComposeEditorLikelyOpen {
    param([Parameter(Mandatory = $true)]$Bounds)

    if ($Bounds.width -lt 400 -or $Bounds.height -lt 400) {
        return $false
    }

    $sample = Get-ScreenPixel -Bounds $Bounds -X 100 -Y 100
    return ($sample.R -gt 235 -and $sample.G -gt 235 -and $sample.B -gt 235)
}

function Read-Profile {
    param([string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path)) {
        $repoRoot = Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")
        $Path = Join-Path $repoRoot "windows-probe\profiles\weixin_4.1.10.53_default.json"
    }

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Profile not found: $Path"
    }

    return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
}

function Wait-ForTitle {
    param(
        [System.Diagnostics.Process]$Process,
        [string]$ExpectedTitle,
        [int]$TimeoutMilliseconds = 5000
    )

    $deadline = (Get-Date).AddMilliseconds($TimeoutMilliseconds)
    do {
        $element = Get-WindowElement $Process
        $title = $element.Current.Name
        if ($title -like "*$ExpectedTitle*") {
            return $element
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)

    return (Get-WindowElement $Process)
}

Add-ProbeAssemblies
$profile = Read-Profile $ProfilePath
$process = Get-WeixinProcess

[void][WechatComposeStage1Win32]::ShowWindowAsync($process.MainWindowHandle, 9)
[void][WechatComposeStage1Win32]::SetForegroundWindow($process.MainWindowHandle)
Start-Sleep -Milliseconds 350

$element = Get-WindowElement $process
$title = $element.Current.Name
$bounds = Get-WindowBounds $element
Write-Host ("Current Weixin title: {0}" -f $title)
Write-Host ("Current bounds: {0}x{1}" -f $bounds.width, $bounds.height)

if ($title -notlike "*$MomentsTitle*") {
    Invoke-WindowClick `
        -Bounds $bounds `
        -X ([int]$profile.points.main_moments_nav.x) `
        -Y ([int]$profile.points.main_moments_nav.y) `
        -Label "open_moments"

    $element = Wait-ForTitle -Process $process -ExpectedTitle $MomentsTitle
    $title = $element.Current.Name
    $bounds = Get-WindowBounds $element
    Write-Host ("After open_moments title: {0}" -f $title)
    Write-Host ("After open_moments bounds: {0}x{1}" -f $bounds.width, $bounds.height)
}

if ($title -notlike "*$MomentsTitle*") {
    throw "Moments window was not detected. Current title: $title"
}

if ($RefuseIfComposeEditorLikelyOpen -and (Test-ComposeEditorLikelyOpen -Bounds $bounds)) {
    throw "A Moments compose editor appears to already be open. Review or cancel the current draft before running stage1 again."
}

Invoke-WindowClick `
    -Bounds $bounds `
    -X ([int]$profile.points.moments_camera.x) `
    -Y ([int]$profile.points.moments_camera.y) `
    -Label "open_file_picker"

if ($DryRun) {
    Write-Host "DryRun complete. No clicks were sent."
}
else {
    Write-Host "Stage1 complete. The Windows file picker should now be open."
}
