[CmdletBinding()]
param(
    [string]$PlanPath = "",
    [string]$OutputDir = "",
    [int]$MaxPages = 20,
    [int]$MaxMoments = 50,
    [switch]$CreatePlanTemplate,
    [switch]$CaptureViewportScreenshot,
    [switch]$DryRun
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

function Add-CaptureAssemblies {
    Add-Type -AssemblyName UIAutomationClient
    Add-Type -AssemblyName UIAutomationTypes
    Add-Type -AssemblyName System.Drawing
    Add-Type -AssemblyName System.Windows.Forms

    if (-not ([System.Management.Automation.PSTypeName]"WechatFriendCaptureWin32").Type) {
        Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

public static class WechatFriendCaptureWin32 {
    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    public static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow);

    [DllImport("user32.dll")]
    public static extern void mouse_event(uint dwFlags, uint dx, uint dy, int dwData, UIntPtr dwExtraInfo);

    public const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    public const uint MOUSEEVENTF_LEFTUP = 0x0004;
    public const uint MOUSEEVENTF_WHEEL = 0x0800;
}
"@
    }
}

function Get-ObjectProperty {
    param(
        [AllowNull()]$InputObject,
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowNull()]$Default = $null
    )

    if ($null -eq $InputObject) {
        return $Default
    }

    $property = $InputObject.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) {
        return $Default
    }

    return $property.Value
}

function Get-StringProperty {
    param(
        [AllowNull()]$InputObject,
        [Parameter(Mandatory = $true)][string]$Name,
        [string]$Default = ""
    )

    $value = Get-ObjectProperty -InputObject $InputObject -Name $Name -Default $Default
    if ($null -eq $value) {
        return $Default
    }
    return [string]$value
}

function Get-IntProperty {
    param(
        [AllowNull()]$InputObject,
        [Parameter(Mandatory = $true)][string]$Name,
        [int]$Default = 0
    )

    $value = Get-ObjectProperty -InputObject $InputObject -Name $Name -Default $Default
    if ($null -eq $value -or [string]::IsNullOrWhiteSpace([string]$value)) {
        return $Default
    }
    return [int]$value
}

function Get-BoolProperty {
    param(
        [AllowNull()]$InputObject,
        [Parameter(Mandatory = $true)][string]$Name,
        [bool]$Default = $false
    )

    $value = Get-ObjectProperty -InputObject $InputObject -Name $Name -Default $Default
    if ($null -eq $value) {
        return $Default
    }
    return [bool]$value
}

function Get-ArrayProperty {
    param(
        [AllowNull()]$InputObject,
        [Parameter(Mandatory = $true)][string]$Name
    )

    $value = Get-ObjectProperty -InputObject $InputObject -Name $Name -Default @()
    if ($null -eq $value) {
        return @()
    }
    return @($value)
}

function Add-ArrayPropertyItem {
    param(
        [Parameter(Mandatory = $true)]$InputObject,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)]$Item
    )

    $items = @(Get-ObjectProperty -InputObject $InputObject -Name $Name -Default @())
    if ($null -eq $InputObject.PSObject.Properties[$Name]) {
        $InputObject | Add-Member -MemberType NoteProperty -Name $Name -Value @($items + $Item)
    }
    else {
        $InputObject.PSObject.Properties[$Name].Value = @($items + $Item)
    }
}

function Test-PointSpec {
    param([AllowNull()]$Point)

    return ($null -ne $Point -and
        $null -ne (Get-ObjectProperty -InputObject $Point -Name "x") -and
        $null -ne (Get-ObjectProperty -InputObject $Point -Name "y"))
}

function New-ZeroBounds {
    return [pscustomobject][ordered]@{
        left = 0
        top = 0
        right = 0
        bottom = 0
        width = 0
        height = 0
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

function ConvertTo-SafePathSegment {
    param([AllowNull()][string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return "item"
    }

    $safe = $Value -replace "[^A-Za-z0-9_.-]", "_"
    $safe = $safe.Trim("_", ".", "-")
    if ([string]::IsNullOrWhiteSpace($safe)) {
        $hash = Get-TextHash -Text $Value
        return "item_" + $hash.Substring(0, 10)
    }
    if ($safe.Length -gt 64) {
        return $safe.Substring(0, 64)
    }
    return $safe
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

function ConvertTo-CapturePath {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$RootDir
    )

    try {
        $rootFull = [System.IO.Path]::GetFullPath($RootDir)
        if (-not $rootFull.EndsWith([System.IO.Path]::DirectorySeparatorChar)) {
            $rootFull += [System.IO.Path]::DirectorySeparatorChar
        }
        $pathFull = [System.IO.Path]::GetFullPath($Path)
        $rootUri = New-Object System.Uri($rootFull)
        $pathUri = New-Object System.Uri($pathFull)
        $relative = [System.Uri]::UnescapeDataString($rootUri.MakeRelativeUri($pathUri).ToString())
        return ($relative -replace "\\", "/")
    }
    catch {
        return $Path
    }
}

function Get-FileMaterial {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Kind,
        [string]$Role = "",
        [string]$CaptureMethod = "",
        [string]$RootDir = ""
    )

    $item = Get-Item -LiteralPath $Path
    $hash = Get-FileHash -LiteralPath $Path -Algorithm SHA256
    $materialPath = $item.FullName
    if (-not [string]::IsNullOrWhiteSpace($RootDir)) {
        $materialPath = ConvertTo-CapturePath -Path $item.FullName -RootDir $RootDir
    }

    return [pscustomobject][ordered]@{
        kind = $Kind
        role = $Role
        path = $materialPath
        length = $item.Length
        sha256 = $hash.Hash.ToLowerInvariant()
        captureMethod = $CaptureMethod
    }
}

function Save-Json {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$Path,
        [int]$Depth = 14
    )

    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    $Value | ConvertTo-Json -Depth $Depth | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Save-WindowScreenshot {
    param(
        [Parameter(Mandatory = $true)]$Bounds,
        [Parameter(Mandatory = $true)][string]$Path
    )

    if ($Bounds.width -le 0 -or $Bounds.height -le 0) {
        throw "Window bounds are empty; cannot capture screenshot."
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

function Invoke-WindowClick {
    param(
        [Parameter(Mandatory = $true)]$Bounds,
        [Parameter(Mandatory = $true)]$Point,
        [int]$SettleMilliseconds = 900
    )

    $windowX = Get-IntProperty -InputObject $Point -Name "x"
    $windowY = Get-IntProperty -InputObject $Point -Name "y"
    $screenX = [int]($Bounds.left + $windowX)
    $screenY = [int]($Bounds.top + $windowY)
    Write-Host ("click window=({0},{1}) screen=({2},{3})" -f $windowX, $windowY, $screenX, $screenY)
    if ($DryRun) {
        return
    }

    [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point($screenX, $screenY)
    Start-Sleep -Milliseconds 80
    [WechatFriendCaptureWin32]::mouse_event([WechatFriendCaptureWin32]::MOUSEEVENTF_LEFTDOWN, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 80
    [WechatFriendCaptureWin32]::mouse_event([WechatFriendCaptureWin32]::MOUSEEVENTF_LEFTUP, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds $SettleMilliseconds
}

function Invoke-WindowWheel {
    param(
        [Parameter(Mandatory = $true)]$Bounds,
        [Parameter(Mandatory = $true)]$ScrollSpec,
        [int]$DefaultWaitMilliseconds = 1200
    )

    $x = Get-IntProperty -InputObject $ScrollSpec -Name "x" -Default ([int]($Bounds.width / 2))
    $y = Get-IntProperty -InputObject $ScrollSpec -Name "y" -Default ([int]($Bounds.height * 0.65))
    $count = Get-IntProperty -InputObject $ScrollSpec -Name "wheelCount" -Default 4
    $delta = Get-IntProperty -InputObject $ScrollSpec -Name "wheelDelta" -Default -360
    $wait = Get-IntProperty -InputObject $ScrollSpec -Name "waitMilliseconds" -Default $DefaultWaitMilliseconds
    $screenX = [int]($Bounds.left + $x)
    $screenY = [int]($Bounds.top + $y)
    Write-Host ("scroll window=({0},{1}) count={2} delta={3}" -f $x, $y, $count, $delta)
    if ($DryRun) {
        return
    }

    [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point($screenX, $screenY)
    for ($i = 0; $i -lt $count; $i++) {
        [WechatFriendCaptureWin32]::mouse_event([WechatFriendCaptureWin32]::MOUSEEVENTF_WHEEL, 0, 0, $delta, [UIntPtr]::Zero)
        Start-Sleep -Milliseconds 180
    }
    Start-Sleep -Milliseconds $wait
}

function Send-Keys {
    param([Parameter(Mandatory = $true)][string]$Keys)

    if ($DryRun) {
        return
    }
    $wshell = New-Object -ComObject WScript.Shell
    $wshell.SendKeys($Keys)
}

function New-PlanTemplate {
    return [pscustomobject][ordered]@{
        schemaVersion = 1
        profile = [pscustomobject][ordered]@{
            displayName = "friend-name"
            profileId = ""
            remarkName = ""
            alias = ""
            visibilityStatus = "visible"
        }
        capture = [pscustomobject][ordered]@{
            sessionId = ""
            closeViewerAfterMedia = $true
            captureViewportScreenshot = $false
            itemWaitMilliseconds = 900
            scrollWaitMilliseconds = 1200
        }
        pages = @(
            [pscustomobject][ordered]@{
                pageId = "page_001"
                moments = @(
                    [pscustomobject][ordered]@{
                        postId = "moment_001"
                        postedAtText = "yesterday"
                        postType = "image_set"
                        textPoint = [pscustomobject][ordered]@{ x = 300; y = 545 }
                        media = @(
                            [pscustomobject][ordered]@{
                                id = "image_group_001"
                                kind = "image"
                                openPoint = [pscustomobject][ordered]@{ x = 180; y = 600 }
                                saveMode = "viewer_batch"
                                maxItems = 9
                                baseName = "image"
                                extension = "jpg"
                            }
                        )
                        links = @()
                    },
                    [pscustomobject][ordered]@{
                        postId = "moment_002"
                        postedAtText = "yesterday"
                        postType = "external_url"
                        textPoint = [pscustomobject][ordered]@{ x = 300; y = 735 }
                        media = @()
                        links = @(
                            [pscustomobject][ordered]@{
                                id = "link_001"
                                providerHint = "video_account"
                                title = ""
                                point = [pscustomobject][ordered]@{ x = 240; y = 790 }
                            }
                        )
                    }
                )
                afterScroll = [pscustomobject][ordered]@{
                    x = 280
                    y = 560
                    wheelCount = 4
                    wheelDelta = -360
                    waitMilliseconds = 1200
                }
            }
        )
    }
}

function New-ProfileObject {
    param(
        [Parameter(Mandatory = $true)]$Plan,
        [Parameter(Mandatory = $true)][string]$SessionId,
        [Parameter(Mandatory = $true)][string]$CapturedAt
    )

    $profileSpec = Get-ObjectProperty -InputObject $Plan -Name "profile" -Default ([pscustomobject]@{})
    return [pscustomobject][ordered]@{
        schemaVersion = 1
        source = "wechat_windows_moments"
        capturedAt = $CapturedAt
        captureSessionId = $SessionId
        client = [pscustomobject][ordered]@{
            app = "Weixin"
            version = ""
            platform = "Windows"
        }
        profile = [pscustomobject][ordered]@{
            displayName = Get-StringProperty -InputObject $profileSpec -Name "displayName"
            profileId = Get-StringProperty -InputObject $profileSpec -Name "profileId"
            remarkName = Get-StringProperty -InputObject $profileSpec -Name "remarkName"
            alias = Get-StringProperty -InputObject $profileSpec -Name "alias"
            profileUrlFile = ""
            profileUrlLength = 0
            profileUrlSha256 = ""
            visibilityStatus = Get-StringProperty -InputObject $profileSpec -Name "visibilityStatus" -Default "unknown"
            avatar = $null
            cover = $null
        }
        moments = [pscustomobject][ordered]@{
            directory = "moments"
            indexFile = "moments_index.jsonl"
            count = 0
            capturedCount = 0
            partialCount = 0
            failedCount = 0
            skippedCount = 0
            items = @()
        }
        capture = [pscustomobject][ordered]@{
            method = "windows_friend_recent_plan_v1"
            operator = $env:USERNAME
            notes = ""
        }
    }
}

function New-MomentObject {
    param(
        [Parameter(Mandatory = $true)]$MomentSpec,
        [Parameter(Mandatory = $true)][string]$SessionId,
        [Parameter(Mandatory = $true)][string]$MomentDirRelative,
        [Parameter(Mandatory = $true)]$Profile
    )

    return [pscustomobject][ordered]@{
        schemaVersion = 1
        source = "wechat_windows_moments"
        capturedAt = (Get-Date).ToString("o")
        captureSessionId = $SessionId
        directory = $MomentDirRelative
        postId = Get-StringProperty -InputObject $MomentSpec -Name "postId"
        status = "partial"
        postType = Get-StringProperty -InputObject $MomentSpec -Name "postType" -Default "unknown"
        author = [pscustomobject][ordered]@{
            displayName = [string]$Profile.profile.displayName
            profileId = [string]$Profile.profile.profileId
        }
        postedAtText = Get-StringProperty -InputObject $MomentSpec -Name "postedAtText"
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

function Invoke-TextCapture {
    param(
        [Parameter(Mandatory = $true)]$Point,
        [Parameter(Mandatory = $true)][string]$TextDir,
        [Parameter(Mandatory = $true)]$Moment,
        [Parameter(Mandatory = $true)][string]$RootDir
    )

    if ($DryRun) {
        Write-Host ("planned text copy at ({0},{1})" -f (Get-IntProperty $Point "x"), (Get-IntProperty $Point "y"))
        return "dry_run"
    }

    New-Item -ItemType Directory -Path $TextDir -Force | Out-Null
    $scriptPath = Join-Path $PSScriptRoot "windows_wechat_copy_visible_text.ps1"
    & $scriptPath -X (Get-IntProperty $Point "x") -Y (Get-IntProperty $Point "y") -OutputDir $TextDir | Out-Null

    $summaryPath = Join-Path $TextDir "summary.json"
    if (-not (Test-Path -LiteralPath $summaryPath)) {
        return "summary_missing"
    }

    $summary = Get-Content -LiteralPath $summaryPath -Encoding UTF8 -Raw | ConvertFrom-Json
    $status = Get-StringProperty -InputObject $summary -Name "status" -Default "unknown"
    if ($status -eq "copied") {
        $textFile = Get-StringProperty -InputObject $summary -Name "textFile"
        $Moment.text = [pscustomobject][ordered]@{
            textFile = ConvertTo-CapturePath -Path $textFile -RootDir $RootDir
            length = Get-IntProperty -InputObject $summary -Name "copiedTextLength"
            sha256 = Get-StringProperty -InputObject $summary -Name "copiedTextSha256"
            captureMethod = "wechat_moments_right_click_text_copy_menu"
            isFoldedAtCapture = $false
        }
    }
    return $status
}

function Invoke-LinkCapture {
    param(
        [Parameter(Mandatory = $true)]$LinkSpec,
        [Parameter(Mandatory = $true)][string]$LinkDir,
        [Parameter(Mandatory = $true)]$Moment,
        [Parameter(Mandatory = $true)][string]$RootDir
    )

    $point = Get-ObjectProperty -InputObject $LinkSpec -Name "point"
    $provider = Get-StringProperty -InputObject $LinkSpec -Name "providerHint" -Default "unknown"
    $title = Get-StringProperty -InputObject $LinkSpec -Name "title"

    if ($DryRun) {
        Write-Host ("planned link copy provider={0} at ({1},{2})" -f $provider, (Get-IntProperty $point "x"), (Get-IntProperty $point "y"))
        return "dry_run"
    }

    New-Item -ItemType Directory -Path $LinkDir -Force | Out-Null
    $scriptPath = Join-Path $PSScriptRoot "windows_wechat_copy_link_url.ps1"
    & $scriptPath -X (Get-IntProperty $point "x") -Y (Get-IntProperty $point "y") -ProviderHint $provider -Title $title -OutputDir $LinkDir | Out-Null

    $referencePath = Join-Path $LinkDir "link_reference.json"
    if (-not (Test-Path -LiteralPath $referencePath)) {
        return "summary_missing"
    }

    $reference = Get-Content -LiteralPath $referencePath -Encoding UTF8 -Raw | ConvertFrom-Json
    $status = Get-StringProperty -InputObject $reference -Name "status" -Default "unknown"
    if ($status -eq "url_copied") {
        $urlFile = Get-StringProperty -InputObject $reference -Name "urlFile"
        Add-ArrayPropertyItem -InputObject $Moment -Name "materials" -Item ([pscustomobject][ordered]@{
            kind = "external_url"
            providerHint = $provider
            title = $title
            urlFile = ConvertTo-CapturePath -Path $urlFile -RootDir $RootDir
            urlLength = Get-IntProperty -InputObject $reference -Name "urlLength"
            urlSha256 = Get-StringProperty -InputObject $reference -Name "urlSha256"
            captureMethod = Get-StringProperty -InputObject $reference -Name "captureMethod"
        })
    }
    return $status
}

function Invoke-MediaCapture {
    param(
        [Parameter(Mandatory = $true)]$MediaSpec,
        [Parameter(Mandatory = $true)]$Bounds,
        [Parameter(Mandatory = $true)][string]$MomentDir,
        [Parameter(Mandatory = $true)]$Moment,
        [Parameter(Mandatory = $true)][string]$RootDir,
        [bool]$CloseViewerAfterMedia
    )

    $openPoint = Get-ObjectProperty -InputObject $MediaSpec -Name "openPoint"
    if (-not (Test-PointSpec -Point $openPoint)) {
        return "open_point_missing"
    }

    $kind = Get-StringProperty -InputObject $MediaSpec -Name "kind" -Default "image"
    $saveMode = Get-StringProperty -InputObject $MediaSpec -Name "saveMode" -Default "viewer_batch"
    if ($saveMode -ne "viewer_batch") {
        return "unsupported_save_mode"
    }

    Invoke-WindowClick -Bounds $Bounds -Point $openPoint -SettleMilliseconds 1200

    $baseName = Get-StringProperty -InputObject $MediaSpec -Name "baseName" -Default $kind
    $extension = Get-StringProperty -InputObject $MediaSpec -Name "extension" -Default $(if ($kind -eq "video") { "mp4" } else { "jpg" })
    $maxItems = Get-IntProperty -InputObject $MediaSpec -Name "maxItems" -Default 1

    if ($DryRun) {
        Write-Host ("planned media save kind={0} maxItems={1}" -f $kind, $maxItems)
        return "dry_run"
    }

    $saveScript = Join-Path $PSScriptRoot "windows_wechat_save_viewer_batch.ps1"
    & $saveScript -OutputDir $MomentDir -MaxItems $maxItems -BaseName $baseName -Extension $extension | Out-Null

    $manifestPath = Join-Path $MomentDir "manifest.json"
    if (-not (Test-Path -LiteralPath $manifestPath)) {
        return "manifest_missing"
    }

    $manifest = Get-Content -LiteralPath $manifestPath -Encoding UTF8 -Raw | ConvertFrom-Json
    foreach ($media in Get-ArrayProperty -InputObject $manifest -Name "media") {
        $mediaPath = Get-StringProperty -InputObject $media -Name "path"
        if ([string]::IsNullOrWhiteSpace($mediaPath) -or -not (Test-Path -LiteralPath $mediaPath)) {
            continue
        }
        Add-ArrayPropertyItem -InputObject $Moment -Name "materials" -Item (Get-FileMaterial -Path $mediaPath -Kind $kind -Role "moment_media" -CaptureMethod "wechat_viewer_ctrl_s" -RootDir $RootDir)
    }

    if ($CloseViewerAfterMedia) {
        Send-Keys "{ESC}"
        Start-Sleep -Milliseconds 700
    }

    return Get-StringProperty -InputObject $manifest -Name "stoppedReason" -Default "captured"
}

function Add-Event {
    param(
        [System.Collections.ArrayList]$Events,
        [Parameter(Mandatory = $true)][string]$Type,
        [Parameter(Mandatory = $true)][string]$PostId,
        [Parameter(Mandatory = $true)][string]$Status,
        [string]$Detail = ""
    )

    [void]$Events.Add([pscustomobject][ordered]@{
        type = $Type
        postId = $PostId
        status = $Status
        detail = $Detail
        capturedAt = (Get-Date).ToString("o")
    })
}

if ($CreatePlanTemplate) {
    $template = New-PlanTemplate
    if ([string]::IsNullOrWhiteSpace($PlanPath)) {
        $repoRootForTemplate = Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")
        $PlanPath = Join-Path $repoRootForTemplate "windows-probe\profiles\friend_recent_capture_plan.example.json"
    }
    Save-Json -Value $template -Path $PlanPath -Depth 12
    Write-Host "Plan template written: $PlanPath"
    return
}

if ([string]::IsNullOrWhiteSpace($PlanPath)) {
    throw "PlanPath is required. Use -CreatePlanTemplate to generate a starter plan."
}
if (-not (Test-Path -LiteralPath $PlanPath)) {
    throw "PlanPath not found: $PlanPath"
}
if ($MaxPages -lt 1) {
    throw "MaxPages must be >= 1."
}
if ($MaxMoments -lt 1) {
    throw "MaxMoments must be >= 1."
}

$repoRoot = Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")
$plan = Get-Content -LiteralPath $PlanPath -Encoding UTF8 -Raw | ConvertFrom-Json
$pages = @(Get-ArrayProperty -InputObject $plan -Name "pages")
if ($pages.Count -eq 0) {
    throw "Plan contains no pages."
}
$totalPlannedPages = $pages.Count

$capturedAt = (Get-Date).ToString("o")
$profileSpec = Get-ObjectProperty -InputObject $plan -Name "profile" -Default ([pscustomobject]@{})
$captureSpec = Get-ObjectProperty -InputObject $plan -Name "capture" -Default ([pscustomobject]@{})
$profileName = Get-StringProperty -InputObject $profileSpec -Name "displayName" -Default "profile"
$sessionId = Get-StringProperty -InputObject $captureSpec -Name "sessionId"
if ([string]::IsNullOrWhiteSpace($sessionId)) {
    $sessionId = "{0}_{1}" -f (ConvertTo-SafePathSegment $profileName), (Get-Date -Format "yyyyMMdd_HHmmss")
}

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path $repoRoot ("windows-probe\capture-runs\" + $sessionId)
}

$momentsDir = Join-Path $OutputDir "moments"
$profileAssetsDir = Join-Path $OutputDir "profile-assets"
$indexPath = Join-Path $OutputDir "moments_index.jsonl"
$profilePath = Join-Path $OutputDir "profile.json"
$summaryPath = Join-Path $OutputDir "capture_session_summary.json"

if (-not $DryRun) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
    New-Item -ItemType Directory -Path $momentsDir -Force | Out-Null
    New-Item -ItemType Directory -Path $profileAssetsDir -Force | Out-Null
    if (Test-Path -LiteralPath $indexPath) {
        Remove-Item -LiteralPath $indexPath -Force
    }
    New-Item -ItemType File -Path $indexPath -Force | Out-Null
}

$profile = New-ProfileObject -Plan $plan -SessionId $sessionId -CapturedAt $capturedAt
$closeViewerAfterMedia = Get-BoolProperty -InputObject $captureSpec -Name "closeViewerAfterMedia" -Default $true
$captureScreenshots = [bool]$CaptureViewportScreenshot
if (Get-BoolProperty -InputObject $captureSpec -Name "captureViewportScreenshot" -Default $false) {
    $captureScreenshots = $true
}

$process = $null
$bounds = New-ZeroBounds
if (-not $DryRun) {
    Add-CaptureAssemblies
    $process = Get-WeixinProcess
    [void][WechatFriendCaptureWin32]::ShowWindowAsync($process.MainWindowHandle, 9)
    [void][WechatFriendCaptureWin32]::SetForegroundWindow($process.MainWindowHandle)
    Start-Sleep -Milliseconds 350
    $bounds = Get-WindowBounds $process.MainWindowHandle
}
else {
    Write-Host "DryRun: validating plan without touching WeChat."
}

$capturedMoments = 0
$pageCount = 0
$events = New-Object System.Collections.ArrayList

foreach ($page in $pages) {
    if ($pageCount -ge $MaxPages -or $capturedMoments -ge $MaxMoments) {
        break
    }
    $pageCount += 1
    $pageId = Get-StringProperty -InputObject $page -Name "pageId" -Default ("page_{0:D3}" -f $pageCount)
    Write-Host ("Processing page {0}" -f $pageId)

    foreach ($momentSpec in Get-ArrayProperty -InputObject $page -Name "moments") {
        if ($capturedMoments -ge $MaxMoments) {
            break
        }
        $capturedMoments += 1
        $postId = Get-StringProperty -InputObject $momentSpec -Name "postId"
        if ([string]::IsNullOrWhiteSpace($postId)) {
            $postId = "moment_{0:D3}" -f $capturedMoments
        }

        $safePostId = ConvertTo-SafePathSegment $postId
        $momentDir = Join-Path $momentsDir $safePostId
        $momentDirRelative = "moments/$safePostId"
        $momentPath = Join-Path $momentDir "moment.json"
        $textDir = Join-Path $momentDir "text"
        $linksRoot = Join-Path $momentDir "links"
        $evidenceDir = Join-Path $momentDir "evidence"
        $mediaDir = Join-Path $momentDir "media"

        Write-Host ("Processing moment {0}" -f $postId)
        if (-not $DryRun) {
            New-Item -ItemType Directory -Path $momentDir -Force | Out-Null
            New-Item -ItemType Directory -Path $textDir -Force | Out-Null
            New-Item -ItemType Directory -Path $linksRoot -Force | Out-Null
            New-Item -ItemType Directory -Path $evidenceDir -Force | Out-Null
            New-Item -ItemType Directory -Path $mediaDir -Force | Out-Null
        }

        $moment = New-MomentObject -MomentSpec $momentSpec -SessionId $sessionId -MomentDirRelative $momentDirRelative -Profile $profile
        $actionCount = 0
        $errorCount = 0

        if ($captureScreenshots -and -not $DryRun) {
            try {
                $screenshotPath = Join-Path $evidenceDir ("viewport_{0}.png" -f $pageId)
                Save-WindowScreenshot -Bounds $bounds -Path $screenshotPath
                Add-ArrayPropertyItem -InputObject $moment.uiEvidence -Name "screenshots" -Item (Get-FileMaterial -Path $screenshotPath -Kind "screenshot" -Role "viewport" -CaptureMethod "copy_from_screen" -RootDir $OutputDir)
                Add-Event -Events $events -Type "screenshot" -PostId $postId -Status "captured"
            }
            catch {
                $errorCount += 1
                Add-Event -Events $events -Type "screenshot" -PostId $postId -Status "failed" -Detail $_.Exception.Message
            }
        }

        $textPoint = Get-ObjectProperty -InputObject $momentSpec -Name "textPoint"
        if (Test-PointSpec -Point $textPoint) {
            $actionCount += 1
            try {
                $status = Invoke-TextCapture -Point $textPoint -TextDir $textDir -Moment $moment -RootDir $OutputDir
                Add-Event -Events $events -Type "text" -PostId $postId -Status $status
            }
            catch {
                $errorCount += 1
                Add-Event -Events $events -Type "text" -PostId $postId -Status "failed" -Detail $_.Exception.Message
            }
        }

        $linkIndex = 0
        foreach ($link in Get-ArrayProperty -InputObject $momentSpec -Name "links") {
            $linkIndex += 1
            $linkPoint = Get-ObjectProperty -InputObject $link -Name "point"
            if (-not (Test-PointSpec -Point $linkPoint)) {
                continue
            }
            $actionCount += 1
            $linkId = Get-StringProperty -InputObject $link -Name "id" -Default ("link_{0:D3}" -f $linkIndex)
            $linkDir = Join-Path $linksRoot (ConvertTo-SafePathSegment $linkId)
            try {
                $status = Invoke-LinkCapture -LinkSpec $link -LinkDir $linkDir -Moment $moment -RootDir $OutputDir
                Add-Event -Events $events -Type "link" -PostId $postId -Status $status
            }
            catch {
                $errorCount += 1
                Add-Event -Events $events -Type "link" -PostId $postId -Status "failed" -Detail $_.Exception.Message
            }
        }

        foreach ($media in Get-ArrayProperty -InputObject $momentSpec -Name "media") {
            $actionCount += 1
            try {
                $status = Invoke-MediaCapture -MediaSpec $media -Bounds $bounds -MomentDir $momentDir -Moment $moment -RootDir $OutputDir -CloseViewerAfterMedia $closeViewerAfterMedia
                Add-Event -Events $events -Type "media" -PostId $postId -Status $status
                if (-not $DryRun -and $null -ne $process) {
                    $bounds = Get-WindowBounds $process.MainWindowHandle
                }
            }
            catch {
                $errorCount += 1
                Add-Event -Events $events -Type "media" -PostId $postId -Status "failed" -Detail $_.Exception.Message
                if (-not $DryRun -and $closeViewerAfterMedia) {
                    Send-Keys "{ESC}"
                    Start-Sleep -Milliseconds 700
                }
            }
        }

        $materialCount = @(Get-ObjectProperty -InputObject $moment -Name "materials" -Default @()).Count
        if ($materialCount -gt 0 -or $null -ne $moment.text) {
            $moment.status = if ($errorCount -gt 0) { "partial" } else { "captured" }
        }
        elseif ($actionCount -eq 0) {
            $moment.status = "skipped"
        }
        else {
            $moment.status = "failed"
        }

        if (-not $DryRun) {
            Save-Json -Value $moment -Path $momentPath -Depth 14
            $indexItem = [pscustomobject][ordered]@{
                postId = $postId
                momentFile = "$momentDirRelative/moment.json"
                status = $moment.status
                postedAtText = $moment.postedAtText
            }
            ($indexItem | ConvertTo-Json -Depth 8 -Compress) | Add-Content -LiteralPath $indexPath -Encoding UTF8
            Add-ArrayPropertyItem -InputObject $profile.moments -Name "items" -Item $indexItem
        }

        [void]$events.Add([pscustomobject][ordered]@{
            type = "moment"
            postId = $postId
            status = if ($DryRun) { "dry_run" } else { $moment.status }
            materialCount = $materialCount
            hasText = ($null -ne $moment.text)
            actionCount = $actionCount
            errorCount = $errorCount
            capturedAt = (Get-Date).ToString("o")
        })
    }

    $afterScroll = Get-ObjectProperty -InputObject $page -Name "afterScroll"
    if ($null -ne $afterScroll -and $capturedMoments -lt $MaxMoments -and $pageCount -lt $MaxPages -and $pageCount -lt $totalPlannedPages) {
        try {
            Invoke-WindowWheel -Bounds $bounds -ScrollSpec $afterScroll
            Add-Event -Events $events -Type "scroll" -PostId "" -Status $(if ($DryRun) { "dry_run" } else { "sent" }) -Detail $pageId
            if (-not $DryRun -and $null -ne $process) {
                $bounds = Get-WindowBounds $process.MainWindowHandle
            }
        }
        catch {
            Add-Event -Events $events -Type "scroll" -PostId "" -Status "failed" -Detail $_.Exception.Message
        }
    }
}

$profileItems = @(Get-ObjectProperty -InputObject $profile.moments -Name "items" -Default @())
$profile.moments.count = $profileItems.Count
$profile.moments.capturedCount = @($profileItems | Where-Object { $_.status -eq "captured" }).Count
$profile.moments.partialCount = @($profileItems | Where-Object { $_.status -eq "partial" }).Count
$profile.moments.failedCount = @($profileItems | Where-Object { $_.status -eq "failed" }).Count
$profile.moments.skippedCount = @($profileItems | Where-Object { $_.status -eq "skipped" }).Count

$summary = [pscustomobject][ordered]@{
    createdAt = (Get-Date).ToString("o")
    dryRun = [bool]$DryRun
    planPath = (Resolve-Path -LiteralPath $PlanPath).Path
    outputDir = $OutputDir
    captureSessionId = $sessionId
    pageCount = $pageCount
    momentCount = $capturedMoments
    profileFile = $profilePath
    indexFile = $indexPath
    events = @($events)
}

if (-not $DryRun) {
    Save-Json -Value $profile -Path $profilePath -Depth 14
    Save-Json -Value $summary -Path $summaryPath -Depth 14
}

$summary | ConvertTo-Json -Depth 14
