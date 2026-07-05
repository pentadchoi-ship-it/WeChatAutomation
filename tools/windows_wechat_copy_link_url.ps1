[CmdletBinding()]
param(
    [int]$X = -1,
    [int]$Y = -1,
    [int]$MenuItemOffsetX = 38,
    [int]$MenuItemOffsetY = 28,
    [ValidateSet("unknown", "video_account", "article", "link", "mini_program", "music", "product", "location")]
    [string]$ProviderHint = "unknown",
    [string]$Title = "",
    [string]$OutputDir = "",
    [int]$MenuWaitMilliseconds = 350,
    [int]$CopyWaitMilliseconds = 500,
    [switch]$ClipboardOnly,
    [switch]$KeepClipboard,
    [switch]$IncludeRawUrlInSummary,
    [switch]$DryRun
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

function Add-ProbeAssemblies {
    Add-Type -AssemblyName UIAutomationClient
    Add-Type -AssemblyName UIAutomationTypes
    Add-Type -AssemblyName System.Drawing
    Add-Type -AssemblyName System.Windows.Forms

    if (-not ([System.Management.Automation.PSTypeName]"WechatUrlCopyWin32").Type) {
        Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

public static class WechatUrlCopyWin32 {
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
        [WechatUrlCopyWin32]::mouse_event([WechatUrlCopyWin32]::MOUSEEVENTF_RIGHTDOWN, 0, 0, 0, [UIntPtr]::Zero)
        Start-Sleep -Milliseconds 80
        [WechatUrlCopyWin32]::mouse_event([WechatUrlCopyWin32]::MOUSEEVENTF_RIGHTUP, 0, 0, 0, [UIntPtr]::Zero)
    }
    else {
        [WechatUrlCopyWin32]::mouse_event([WechatUrlCopyWin32]::MOUSEEVENTF_LEFTDOWN, 0, 0, 0, [UIntPtr]::Zero)
        Start-Sleep -Milliseconds 80
        [WechatUrlCopyWin32]::mouse_event([WechatUrlCopyWin32]::MOUSEEVENTF_LEFTUP, 0, 0, 0, [UIntPtr]::Zero)
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

function Get-UrlsFromText {
    param([AllowNull()][string]$Text)

    if ([string]::IsNullOrWhiteSpace($Text)) {
        return @()
    }

    $pattern = '(?i)(https?://[^\s<>"'']+|weixin://[^\s<>"'']+|wx[a-z0-9]+://[^\s<>"'']+)'
    $trimChars = @(
        [char]0x002e, # .
        [char]0x002c, # ,
        [char]0x003b, # ;
        [char]0x003a, # :
        [char]0x0029, # )
        [char]0x005d, # ]
        [char]0x007d, # }
        [char]0x3002,
        [char]0xff0c,
        [char]0xff1b,
        [char]0xff1a
    )
    $urls = New-Object System.Collections.ArrayList
    foreach ($match in [regex]::Matches($Text, $pattern)) {
        $url = $match.Value.Trim()
        $url = $url.TrimEnd($trimChars)
        if (-not [string]::IsNullOrWhiteSpace($url) -and -not $urls.Contains($url)) {
            [void]$urls.Add($url)
        }
    }

    return @($urls)
}

function Save-Json {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$Path,
        [int]$Depth = 10
    )

    $Value | ConvertTo-Json -Depth $Depth | Set-Content -LiteralPath $Path -Encoding UTF8
}

if (-not $ClipboardOnly -and ($X -lt 0 -or $Y -lt 0)) {
    throw "X and Y are required unless -ClipboardOnly is used."
}

Add-ProbeAssemblies

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $repoRoot = Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")
    $OutputDir = Join-Path $repoRoot ("windows-probe\url-runs\" + (Get-Date -Format "yyyyMMdd_HHmmss"))
}
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

$urlPath = Join-Path $OutputDir "url.txt"
$linkReferencePath = Join-Path $OutputDir "link_reference.json"
$summaryPath = Join-Path $OutputDir "summary.json"

$previousClipboard = ""
try {
    $previousClipboard = Get-Clipboard -Raw -Format Text -ErrorAction SilentlyContinue
}
catch {
    $previousClipboard = ""
}

$bounds = $null
$rightClick = $null
$copyClick = $null
$copiedText = ""

if ($DryRun) {
    $copiedText = ""
}
elseif ($ClipboardOnly) {
    try {
        $copiedText = Get-Clipboard -Raw -Format Text -ErrorAction SilentlyContinue
    }
    catch {
        $copiedText = ""
    }
}
else {
    $process = Get-WeixinProcess
    [void][WechatUrlCopyWin32]::ShowWindowAsync($process.MainWindowHandle, 9)
    [void][WechatUrlCopyWin32]::SetForegroundWindow($process.MainWindowHandle)
    Start-Sleep -Milliseconds 300

    $bounds = Get-WindowBounds $process.MainWindowHandle
    $rightClickScreenX = $bounds.left + $X
    $rightClickScreenY = $bounds.top + $Y
    $copyScreenX = $bounds.left + $X + $MenuItemOffsetX
    $copyScreenY = $bounds.top + $Y + $MenuItemOffsetY

    $rightClick = [pscustomobject][ordered]@{
        windowX = $X
        windowY = $Y
        screenX = $rightClickScreenX
        screenY = $rightClickScreenY
    }
    $copyClick = [pscustomobject][ordered]@{
        offsetX = $MenuItemOffsetX
        offsetY = $MenuItemOffsetY
        screenX = $copyScreenX
        screenY = $copyScreenY
    }

    $sentinel = "WECHAT_URL_COPY_SENTINEL_" + [guid]::NewGuid().ToString("N")
    Set-Clipboard -Value $sentinel

    Invoke-MouseClick -ScreenX $rightClickScreenX -ScreenY $rightClickScreenY -Button "right"
    Start-Sleep -Milliseconds $MenuWaitMilliseconds
    Invoke-MouseClick -ScreenX $copyScreenX -ScreenY $copyScreenY -Button "left"
    Start-Sleep -Milliseconds $CopyWaitMilliseconds

    try {
        $copiedText = Get-Clipboard -Raw -Format Text -ErrorAction SilentlyContinue
    }
    catch {
        $copiedText = ""
    }
}

[object[]]$urls = @()
if (-not $DryRun) {
    $urls = @(Get-UrlsFromText $copiedText)
}
$urlCount = @($urls).Count
$primaryUrl = if ($urlCount -gt 0) { [string]$urls[0] } else { "" }
$status = if ($DryRun) {
    "dry_run"
}
elseif ($urlCount -gt 0) {
    "url_copied"
}
else {
    "no_url_found"
}

Set-Content -LiteralPath $urlPath -Value $primaryUrl -Encoding UTF8

if (-not $KeepClipboard -and -not $DryRun -and -not $ClipboardOnly) {
    if ([string]::IsNullOrEmpty($previousClipboard)) {
        Clear-Clipboard
    }
    else {
        Set-Clipboard -Value $previousClipboard
    }
}

$reference = [ordered]@{
    schemaVersion = 1
    kind = "external_url"
    providerHint = $ProviderHint
    title = $Title
    status = $status
    capturedAt = (Get-Date).ToString("o")
    captureMethod = if ($ClipboardOnly) { "clipboard_only_url_extract" } else { "wechat_moments_right_click_copy_link_menu" }
    urlFile = $urlPath
    urlLength = $primaryUrl.Length
    urlSha256 = Get-TextHash $primaryUrl
    sourceClipboardLength = if ($null -eq $copiedText) { 0 } else { $copiedText.Length }
    sourceClipboardSha256 = Get-TextHash $copiedText
}
if ($IncludeRawUrlInSummary) {
    $reference["url"] = $primaryUrl
}
Save-Json -Value ([pscustomobject]$reference) -Path $linkReferencePath -Depth 10

$summary = [ordered]@{
    createdAt = (Get-Date).ToString("o")
    method = "wechat_moments_copy_link_url"
    status = $status
    dryRun = [bool]$DryRun
    clipboardOnly = [bool]$ClipboardOnly
    keepClipboard = [bool]$KeepClipboard
    providerHint = $ProviderHint
    outputDir = $OutputDir
    urlFile = $urlPath
    linkReferenceFile = $linkReferencePath
    urlCount = $urlCount
    urlLength = $primaryUrl.Length
    urlSha256 = Get-TextHash $primaryUrl
    sourceClipboardLength = if ($null -eq $copiedText) { 0 } else { $copiedText.Length }
    sourceClipboardSha256 = Get-TextHash $copiedText
    windowBounds = $bounds
    rightClick = $rightClick
    copyClick = $copyClick
}
if ($IncludeRawUrlInSummary) {
    $summary["url"] = $primaryUrl
}

Save-Json -Value ([pscustomobject]$summary) -Path $summaryPath -Depth 10
([pscustomobject]$summary) | ConvertTo-Json -Depth 10
