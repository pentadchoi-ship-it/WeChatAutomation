[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][int]$X,
    [Parameter(Mandatory = $true)][int]$Y,
    [int]$CopyMenuOffsetX = 32,
    [int]$CopyMenuOffsetY = 28,
    [string]$OutputDir = "",
    [int]$MenuWaitMilliseconds = 350,
    [int]$CopyWaitMilliseconds = 500,
    [switch]$KeepClipboard,
    [switch]$DryRun
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

function Add-ProbeAssemblies {
    Add-Type -AssemblyName UIAutomationClient
    Add-Type -AssemblyName UIAutomationTypes
    Add-Type -AssemblyName System.Drawing
    Add-Type -AssemblyName System.Windows.Forms

    if (-not ([System.Management.Automation.PSTypeName]"WechatTextCopyWin32").Type) {
        Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

public static class WechatTextCopyWin32 {
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

function Invoke-MouseClick {
    param(
        [Parameter(Mandatory = $true)][int]$ScreenX,
        [Parameter(Mandatory = $true)][int]$ScreenY,
        [ValidateSet("left", "right")][string]$Button = "left"
    )

    [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point($ScreenX, $ScreenY)
    Start-Sleep -Milliseconds 80
    if ($Button -eq "right") {
        [WechatTextCopyWin32]::mouse_event([WechatTextCopyWin32]::MOUSEEVENTF_RIGHTDOWN, 0, 0, 0, [UIntPtr]::Zero)
        Start-Sleep -Milliseconds 80
        [WechatTextCopyWin32]::mouse_event([WechatTextCopyWin32]::MOUSEEVENTF_RIGHTUP, 0, 0, 0, [UIntPtr]::Zero)
    }
    else {
        [WechatTextCopyWin32]::mouse_event([WechatTextCopyWin32]::MOUSEEVENTF_LEFTDOWN, 0, 0, 0, [UIntPtr]::Zero)
        Start-Sleep -Milliseconds 80
        [WechatTextCopyWin32]::mouse_event([WechatTextCopyWin32]::MOUSEEVENTF_LEFTUP, 0, 0, 0, [UIntPtr]::Zero)
    }
}

function Get-TextHash {
    param([AllowNull()][string]$Text)

    if ([string]::IsNullOrEmpty($Text)) {
        return ""
    }

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
        return (($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString("x2") }) -join "")
    }
    finally {
        $sha.Dispose()
    }
}

function Save-Json {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$Path
    )

    $Value | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Path -Encoding UTF8
}

Add-ProbeAssemblies

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $repoRoot = Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")
    $OutputDir = Join-Path $repoRoot ("windows-probe\text-runs\" + (Get-Date -Format "yyyyMMdd_HHmmss"))
}
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

$process = Get-WeixinProcess
[void][WechatTextCopyWin32]::ShowWindowAsync($process.MainWindowHandle, 9)
[void][WechatTextCopyWin32]::SetForegroundWindow($process.MainWindowHandle)
Start-Sleep -Milliseconds 300

$bounds = Get-WindowBounds $process.MainWindowHandle
$rightClickScreenX = $bounds.left + $X
$rightClickScreenY = $bounds.top + $Y
$copyScreenX = $bounds.left + $X + $CopyMenuOffsetX
$copyScreenY = $bounds.top + $Y + $CopyMenuOffsetY

$previousClipboard = ""
try {
    $previousClipboard = Get-Clipboard -Raw -Format Text -ErrorAction SilentlyContinue
}
catch {
    $previousClipboard = ""
}

$sentinel = "WECHAT_COPY_SENTINEL_" + [guid]::NewGuid().ToString("N")
Set-Clipboard -Value $sentinel

if (-not $DryRun) {
    Invoke-MouseClick -ScreenX $rightClickScreenX -ScreenY $rightClickScreenY -Button "right"
    Start-Sleep -Milliseconds $MenuWaitMilliseconds
    Invoke-MouseClick -ScreenX $copyScreenX -ScreenY $copyScreenY -Button "left"
    Start-Sleep -Milliseconds $CopyWaitMilliseconds
}

$copiedText = ""
try {
    $copiedText = Get-Clipboard -Raw -Format Text -ErrorAction SilentlyContinue
}
catch {
    $copiedText = ""
}

$textPath = Join-Path $OutputDir "copied_text.txt"
if (-not $DryRun -and $copiedText -ne $sentinel -and -not [string]::IsNullOrEmpty($copiedText)) {
    Set-Content -LiteralPath $textPath -Value $copiedText -Encoding UTF8
}
else {
    Set-Content -LiteralPath $textPath -Value "" -Encoding UTF8
}

if (-not $KeepClipboard) {
    if ([string]::IsNullOrEmpty($previousClipboard)) {
        Clear-Clipboard
    }
    else {
        Set-Clipboard -Value $previousClipboard
    }
}

$status = if ($DryRun) {
    "dry_run"
}
elseif ($copiedText -eq $sentinel -or [string]::IsNullOrEmpty($copiedText)) {
    "copy_failed"
}
else {
    "copied"
}

$summaryPath = Join-Path $OutputDir "summary.json"
$summary = [pscustomobject][ordered]@{
    createdAt = (Get-Date).ToString("o")
    method = "wechat_moments_right_click_text_copy_menu"
    status = $status
    dryRun = [bool]$DryRun
    keepClipboard = [bool]$KeepClipboard
    outputDir = $OutputDir
    textFile = $textPath
    windowBounds = $bounds
    rightClick = [pscustomobject][ordered]@{
        windowX = $X
        windowY = $Y
        screenX = $rightClickScreenX
        screenY = $rightClickScreenY
    }
    copyClick = [pscustomobject][ordered]@{
        offsetX = $CopyMenuOffsetX
        offsetY = $CopyMenuOffsetY
        screenX = $copyScreenX
        screenY = $copyScreenY
    }
    copiedTextLength = if ($status -eq "copied") { $copiedText.Length } else { 0 }
    copiedTextSha256 = if ($status -eq "copied") { Get-TextHash $copiedText } else { "" }
}

Save-Json -Value $summary -Path $summaryPath
$summary | ConvertTo-Json -Depth 8
