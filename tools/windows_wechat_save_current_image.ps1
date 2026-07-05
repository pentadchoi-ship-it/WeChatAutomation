[CmdletBinding()]
param(
    [string]$OutputDir = "",
    [string]$FileName = "moment_save_probe.jpg",
    [int]$DialogWaitMilliseconds = 1000,
    [int]$SaveWaitMilliseconds = 2000,
    [switch]$DryRun
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

function Send-Keys {
    param([Parameter(Mandatory = $true)][string]$Keys)

    $wshell = New-Object -ComObject WScript.Shell
    $wshell.SendKeys($Keys)
}

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $repoRoot = Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")
    $OutputDir = Join-Path $repoRoot ("windows-probe\save-runs\" + (Get-Date -Format "yyyyMMdd_HHmmss"))
}

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

if ([System.IO.Path]::IsPathRooted($FileName)) {
    $targetPath = $FileName
}
else {
    $targetPath = Join-Path $OutputDir $FileName
}

$summaryPath = Join-Path $OutputDir "summary.json"

Write-Host "Save current WeChat image viewer media."
Write-Host "Precondition: a Moments image viewer is active."
Write-Host "Target: $targetPath"

if (-not $DryRun) {
    Send-Keys "^s"
    Start-Sleep -Milliseconds $DialogWaitMilliseconds

    Set-Clipboard -Value $targetPath
    Start-Sleep -Milliseconds 250
    Send-Keys "%n"
    Start-Sleep -Milliseconds 250
    Send-Keys "^a"
    Start-Sleep -Milliseconds 150
    Send-Keys "^v"
    Start-Sleep -Milliseconds 250
    Send-Keys "%s"
    Start-Sleep -Milliseconds $SaveWaitMilliseconds
}

$saved = Test-Path -LiteralPath $targetPath
$length = 0
$lastWriteTime = $null
if ($saved) {
    $item = Get-Item -LiteralPath $targetPath
    $length = $item.Length
    $lastWriteTime = $item.LastWriteTime.ToString("o")
}

$summary = [pscustomobject][ordered]@{
    createdAt = (Get-Date).ToString("o")
    dryRun = [bool]$DryRun
    targetPath = $targetPath
    saved = $saved
    length = $length
    lastWriteTime = $lastWriteTime
    method = "image_viewer_ctrl_s_alt_n_alt_s"
    precondition = "WeChat Moments image viewer is active"
}

$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
$summary | ConvertTo-Json -Depth 6
