[CmdletBinding()]
param(
    [string]$OutputRoot = "",
    [string]$SessionId = "",
    [string]$DisplayName = "",
    [string]$ProfileId = "",
    [string]$RemarkName = "",
    [string]$Alias = "",
    [ValidateSet("unknown", "visible", "no_moments", "permission_denied", "load_failed")]
    [string]$VisibilityStatus = "unknown",
    [string]$MomentId = "",
    [ValidateSet("unknown", "pure_text", "image_set", "video", "external_url", "mixed")]
    [string]$MomentType = "unknown",
    [string]$PostedAtText = "",
    [switch]$DryRun
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

function ConvertTo-SafePathSegment {
    param([AllowNull()][string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return "profile"
    }

    $safe = $Value -replace "[^A-Za-z0-9_.-]", "_"
    $safe = $safe.Trim("_", ".", "-")
    if ([string]::IsNullOrWhiteSpace($safe)) {
        return "profile"
    }

    if ($safe.Length -gt 48) {
        return $safe.Substring(0, 48)
    }

    return $safe
}

function Save-Json {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$Path,
        [int]$Depth = 12
    )

    $Value | ConvertTo-Json -Depth $Depth | Set-Content -LiteralPath $Path -Encoding UTF8
}

function New-EmptyMoment {
    param(
        [Parameter(Mandatory = $true)][string]$Id,
        [Parameter(Mandatory = $true)][string]$Directory,
        [Parameter(Mandatory = $true)][string]$CaptureSessionId
    )

    return [pscustomobject][ordered]@{
        schemaVersion = 1
        source = "wechat_windows_moments"
        capturedAt = (Get-Date).ToString("o")
        captureSessionId = $CaptureSessionId
        directory = $Directory
        postId = $Id
        status = "draft"
        postType = $MomentType
        author = [pscustomobject][ordered]@{
            displayName = $DisplayName
            profileId = $ProfileId
        }
        postedAtText = $PostedAtText
        text = $null
        location = $null
        interactions = [pscustomobject][ordered]@{
            status = "not_attempted"
            likes = @()
            comments = @()
            likeCountText = ""
            commentCountText = ""
        }
        materials = @()
        uiEvidence = [pscustomobject][ordered]@{
            screenshots = @()
            probeRun = ""
        }
    }
}

$repoRoot = Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repoRoot "windows-probe\capture-runs"
}

$capturedAt = (Get-Date).ToString("o")
if ([string]::IsNullOrWhiteSpace($SessionId)) {
    $namePart = ConvertTo-SafePathSegment $(if (-not [string]::IsNullOrWhiteSpace($ProfileId)) { $ProfileId } else { $DisplayName })
    $SessionId = "{0}_{1}" -f $namePart, (Get-Date -Format "yyyyMMdd_HHmmss")
}

$sessionDir = Join-Path $OutputRoot $SessionId
$momentsDir = Join-Path $sessionDir "moments"
$assetsDir = Join-Path $sessionDir "profile-assets"
$profilePath = Join-Path $sessionDir "profile.json"
$indexPath = Join-Path $sessionDir "moments_index.jsonl"

$momentFile = ""
$momentDir = ""
if (-not [string]::IsNullOrWhiteSpace($MomentId)) {
    $safeMomentId = ConvertTo-SafePathSegment $MomentId
    $momentDir = Join-Path $momentsDir $safeMomentId
    $momentFile = Join-Path $momentDir "moment.json"
}

$profile = [pscustomobject][ordered]@{
    schemaVersion = 1
    source = "wechat_windows_moments"
    capturedAt = $capturedAt
    captureSessionId = $SessionId
    client = [pscustomobject][ordered]@{
        app = "Weixin"
        version = ""
        platform = "Windows"
    }
    profile = [pscustomobject][ordered]@{
        displayName = $DisplayName
        profileId = $ProfileId
        remarkName = $RemarkName
        alias = $Alias
        profileUrlFile = ""
        profileUrlLength = 0
        profileUrlSha256 = ""
        visibilityStatus = $VisibilityStatus
        avatar = $null
        cover = $null
    }
    moments = [pscustomobject][ordered]@{
        directory = "moments"
        indexFile = "moments_index.jsonl"
        count = if ([string]::IsNullOrWhiteSpace($MomentId)) { 0 } else { 1 }
        capturedCount = 0
        skippedCount = 0
        items = @()
    }
    capture = [pscustomobject][ordered]@{
        method = "windows_probe_manual_ui_automation"
        operator = $env:USERNAME
        notes = ""
    }
}

if (-not [string]::IsNullOrWhiteSpace($MomentId)) {
    $profile.moments.items = @([pscustomobject][ordered]@{
        postId = $MomentId
        momentFile = ("moments/{0}/moment.json" -f (ConvertTo-SafePathSegment $MomentId))
        status = "draft"
        postedAtText = $PostedAtText
    })
}

$summary = [pscustomobject][ordered]@{
    createdAt = $capturedAt
    dryRun = [bool]$DryRun
    schemaVersion = 1
    captureSessionId = $SessionId
    outputDir = $sessionDir
    profileFile = $profilePath
    momentsDir = $momentsDir
    momentsIndexFile = $indexPath
    profileAssetsDir = $assetsDir
    momentFile = $momentFile
}

if ($DryRun) {
    $summary | ConvertTo-Json -Depth 12
    return
}

New-Item -ItemType Directory -Path $sessionDir -Force | Out-Null
New-Item -ItemType Directory -Path $momentsDir -Force | Out-Null
New-Item -ItemType Directory -Path $assetsDir -Force | Out-Null
Save-Json -Value $profile -Path $profilePath -Depth 12
Set-Content -LiteralPath $indexPath -Value "" -Encoding UTF8

if (-not [string]::IsNullOrWhiteSpace($MomentId)) {
    New-Item -ItemType Directory -Path $momentDir -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $momentDir "text") -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $momentDir "media") -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $momentDir "links") -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $momentDir "evidence") -Force | Out-Null
    $moment = New-EmptyMoment -Id $MomentId -Directory ("moments/{0}" -f (ConvertTo-SafePathSegment $MomentId)) -CaptureSessionId $SessionId
    Save-Json -Value $moment -Path $momentFile -Depth 12
    ($profile.moments.items[0] | ConvertTo-Json -Depth 8 -Compress) | Set-Content -LiteralPath $indexPath -Encoding UTF8
}

$summaryPath = Join-Path $sessionDir "capture_session_summary.json"
Save-Json -Value $summary -Path $summaryPath -Depth 12
$summary | ConvertTo-Json -Depth 12
