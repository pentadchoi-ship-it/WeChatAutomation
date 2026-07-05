[CmdletBinding()]
param(
    [string]$ManifestPath = "",
    [int]$ImageCount = 2,
    [int]$AfterFileSelectWaitMilliseconds = 3000,
    [int]$BeforeTextPasteWaitMilliseconds = 500,
    [switch]$SkipText,
    [switch]$DryRun
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

function Read-MockManifest {
    param([string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path)) {
        $repoRoot = Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")
        $Path = Join-Path $repoRoot "windows-probe\mock-data\mock_manifest.json"
    }

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Mock manifest not found: $Path. Run tools/generate_windows_mock_data.ps1 first."
    }

    return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
}

function Send-Keys {
    param([Parameter(Mandatory = $true)][string]$Keys)

    $wshell = New-Object -ComObject WScript.Shell
    $wshell.SendKeys($Keys)
}

function Set-ClipboardValue {
    param([Parameter(Mandatory = $true)][string]$Value)

    Set-Clipboard -Value $Value
    Start-Sleep -Milliseconds 250
}

function Format-FilePickerValue {
    param([Parameter(Mandatory = $true)]$Paths)

    return (($Paths | ForEach-Object { '"' + [string]$_ + '"' }) -join " ")
}

$manifest = Read-MockManifest $ManifestPath
$images = @($manifest.images | Select-Object -First $ImageCount)
if ($images.Count -eq 0) {
    throw "No mock images found in manifest."
}

foreach ($image in $images) {
    if (-not (Test-Path -LiteralPath $image)) {
        throw "Mock image not found: $image"
    }
}

$text = ""
if (-not $SkipText) {
    if (-not (Test-Path -LiteralPath $manifest.textFile)) {
        throw "Mock text file not found: $($manifest.textFile)"
    }
    $text = Get-Content -LiteralPath $manifest.textFile -Raw
}

Write-Host "Mock compose chain."
Write-Host ("Images: {0}" -f ($images -join "; "))
Write-Host ("Text enabled: {0}" -f (-not $SkipText))
Write-Host "Publish guard: this script never clicks the publish button."

if ($DryRun) {
    Write-Host "DryRun complete. No WeChat actions were sent."
    exit 0
}

$stage1 = Join-Path $PSScriptRoot "windows_wechat_compose_stage1.ps1"
& $stage1

$filePickerValue = Format-FilePickerValue $images
Set-ClipboardValue $filePickerValue
Send-Keys "^v"
Start-Sleep -Milliseconds 300
Send-Keys "{ENTER}"

Start-Sleep -Milliseconds $AfterFileSelectWaitMilliseconds

if (-not $SkipText) {
    Set-ClipboardValue $text
    Start-Sleep -Milliseconds $BeforeTextPasteWaitMilliseconds
    Send-Keys "^v"
}

Write-Host "Mock compose chain complete. Stop here: review or cancel manually; do not publish unless intentionally testing with a safe account."
