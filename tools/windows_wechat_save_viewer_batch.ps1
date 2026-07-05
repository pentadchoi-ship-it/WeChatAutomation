[CmdletBinding()]
param(
    [string]$OutputDir = "",
    [int]$MaxItems = 9,
    [int]$StartIndex = 1,
    [string]$BaseName = "moment_image",
    [string]$Extension = "jpg",
    [int]$DialogWaitMilliseconds = 1000,
    [int]$SaveWaitMilliseconds = 2000,
    [int]$AfterNavigateWaitMilliseconds = 700,
    [switch]$StopOnDuplicate = $true,
    [switch]$KeepDuplicate,
    [switch]$DryRun
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

function Add-AutomationAssemblies {
    Add-Type -AssemblyName UIAutomationClient
    Add-Type -AssemblyName UIAutomationTypes
    Add-Type -AssemblyName System.Drawing
    Add-Type -AssemblyName System.Windows.Forms

    if (-not ([System.Management.Automation.PSTypeName]"WechatBatchSaveWin32").Type) {
        Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

public static class WechatBatchSaveWin32 {
    [DllImport("user32.dll")]
    public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, UIntPtr dwExtraInfo);

    public const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    public const uint MOUSEEVENTF_LEFTUP = 0x0004;
}
"@
    }
}

function Send-Keys {
    param([Parameter(Mandatory = $true)][string]$Keys)

    $wshell = New-Object -ComObject WScript.Shell
    $wshell.SendKeys($Keys)
}

function Invoke-ScreenClick {
    param(
        [Parameter(Mandatory = $true)][int]$ScreenX,
        [Parameter(Mandatory = $true)][int]$ScreenY
    )

    [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point($ScreenX, $ScreenY)
    Start-Sleep -Milliseconds 80
    [WechatBatchSaveWin32]::mouse_event([WechatBatchSaveWin32]::MOUSEEVENTF_LEFTDOWN, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 80
    [WechatBatchSaveWin32]::mouse_event([WechatBatchSaveWin32]::MOUSEEVENTF_LEFTUP, 0, 0, 0, [UIntPtr]::Zero)
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

function Get-WeixinRootElement {
    $process = Get-WeixinProcess
    $root = [System.Windows.Automation.AutomationElement]::FromHandle($process.MainWindowHandle)
    if ($null -eq $root) {
        throw "Could not locate Weixin UI Automation root."
    }
    return $root
}

function Find-SaveDialogElement {
    $root = Get-WeixinRootElement
    $elements = $root.FindAll(
        [System.Windows.Automation.TreeScope]::Descendants,
        [System.Windows.Automation.Condition]::TrueCondition
    )

    for ($i = 0; $i -lt $elements.Count; $i++) {
        $element = $elements.Item($i)
        $current = $element.Current
        if ($current.ControlType -eq [System.Windows.Automation.ControlType]::Window -and
            $current.ClassName -eq "#32770") {
            return $element
        }
    }

    return $null
}

function Find-SaveDialogChild {
    param(
        [Parameter(Mandatory = $true)][System.Windows.Automation.AutomationElement]$Dialog,
        [Parameter(Mandatory = $true)][string]$Kind
    )

    $elements = $Dialog.FindAll(
        [System.Windows.Automation.TreeScope]::Descendants,
        [System.Windows.Automation.Condition]::TrueCondition
    )

    for ($i = 0; $i -lt $elements.Count; $i++) {
        $element = $elements.Item($i)
        $current = $element.Current
        $rect = $current.BoundingRectangle
        if ($rect.IsEmpty) {
            continue
        }

        if ($Kind -eq "filename" -and
            $current.ClassName -eq "Edit" -and
            $current.AutomationId -eq "1001" -and
            $rect.Width -gt 200) {
            return $element
        }

        if ($Kind -eq "saveButton" -and
            $current.ClassName -eq "Button" -and
            $current.AutomationId -eq "1") {
            return $element
        }
    }

    return $null
}

function Invoke-SaveDialogSaveAs {
    param(
        [Parameter(Mandatory = $true)][string]$TargetDirectory,
        [Parameter(Mandatory = $true)][string]$TargetFileName
    )

    $dialog = $null
    for ($attempt = 1; $attempt -le 10; $attempt++) {
        $dialog = Find-SaveDialogElement
        if ($null -ne $dialog) {
            break
        }
        Start-Sleep -Milliseconds 250
    }
    if ($null -eq $dialog) {
        throw "Save dialog was not found."
    }

    Set-ClipboardTextWithRetry -Text $TargetDirectory
    Start-Sleep -Milliseconds 250
    Send-Keys "%d"
    Start-Sleep -Milliseconds 250
    Send-Keys "^a"
    Start-Sleep -Milliseconds 150
    Send-Keys "^v"
    Start-Sleep -Milliseconds 250
    Send-Keys "{ENTER}"
    Start-Sleep -Milliseconds 700

    $dialog = Find-SaveDialogElement
    if ($null -eq $dialog) {
        throw "Save dialog was not found after changing target directory."
    }

    $fileNameElement = Find-SaveDialogChild -Dialog $dialog -Kind "filename"
    if ($null -eq $fileNameElement) {
        throw "Save dialog filename field was not found."
    }
    $fileNameRect = $fileNameElement.Current.BoundingRectangle
    Invoke-ScreenClick -ScreenX ([int]($fileNameRect.Left + ($fileNameRect.Width / 2))) -ScreenY ([int]($fileNameRect.Top + ($fileNameRect.Height / 2)))

    Set-ClipboardTextWithRetry -Text $TargetFileName
    Start-Sleep -Milliseconds 250
    Send-Keys "^a"
    Start-Sleep -Milliseconds 150
    Send-Keys "^v"
    Start-Sleep -Milliseconds 250

    $saveButton = Find-SaveDialogChild -Dialog $dialog -Kind "saveButton"
    if ($null -eq $saveButton) {
        throw "Save dialog Save button was not found."
    }
    $buttonRect = $saveButton.Current.BoundingRectangle
    Invoke-ScreenClick -ScreenX ([int]($buttonRect.Left + ($buttonRect.Width / 2))) -ScreenY ([int]($buttonRect.Top + ($buttonRect.Height / 2)))
}

function Set-ClipboardTextWithRetry {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [int]$Attempts = 5,
        [int]$DelayMilliseconds = 250
    )

    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            Set-Clipboard -Value $Text
            return
        }
        catch {
            if ($attempt -eq $Attempts) {
                throw
            }
            Start-Sleep -Milliseconds $DelayMilliseconds
        }
    }
}

function New-SessionDir {
    param([string]$RequestedDir)

    if (-not [string]::IsNullOrWhiteSpace($RequestedDir)) {
        New-Item -ItemType Directory -Path $RequestedDir -Force | Out-Null
        return (Resolve-Path -LiteralPath $RequestedDir).Path
    }

    $repoRoot = Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")
    $sessionDir = Join-Path $repoRoot ("windows-probe\save-runs\batch_" + (Get-Date -Format "yyyyMMdd_HHmmss"))
    New-Item -ItemType Directory -Path $sessionDir -Force | Out-Null
    return $sessionDir
}

function Save-CurrentViewerImage {
    param(
        [Parameter(Mandatory = $true)][string]$TargetPath,
        [Parameter(Mandatory = $true)][string]$TargetDirectory,
        [Parameter(Mandatory = $true)][string]$TargetFileName
    )

    if ($DryRun) {
        return [pscustomobject][ordered]@{
            saved = $false
            path = $TargetPath
            length = 0
            sha256 = ""
            status = "dry_run"
            error = ""
        }
    }

    Send-Keys "^s"
    Start-Sleep -Milliseconds $DialogWaitMilliseconds

    try {
        Invoke-SaveDialogSaveAs -TargetDirectory $TargetDirectory -TargetFileName $TargetFileName
    }
    catch {
        return [pscustomobject][ordered]@{
            saved = $false
            path = $TargetPath
            length = 0
            sha256 = ""
            status = "save_failed"
            error = $_.Exception.Message
        }
    }
    Start-Sleep -Milliseconds $SaveWaitMilliseconds

    if (-not (Test-Path -LiteralPath $TargetPath)) {
        return [pscustomobject][ordered]@{
            saved = $false
            path = $TargetPath
            length = 0
            sha256 = ""
            status = "save_failed"
            error = "target file was not created"
        }
    }

    $item = Get-Item -LiteralPath $TargetPath
    $hash = Get-FileHash -LiteralPath $TargetPath -Algorithm SHA256
    return [pscustomobject][ordered]@{
        saved = $true
        path = $item.FullName
        length = $item.Length
        sha256 = $hash.Hash.ToLowerInvariant()
        status = "saved"
        error = ""
    }
}

function Write-Jsonl {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$Path
    )

    ($Value | ConvertTo-Json -Depth 12 -Compress) | Add-Content -LiteralPath $Path -Encoding UTF8
}

if ($MaxItems -lt 1) {
    throw "MaxItems must be >= 1."
}
if ($StartIndex -lt 1) {
    throw "StartIndex must be >= 1."
}
if ($StartIndex -gt $MaxItems) {
    throw "StartIndex must be <= MaxItems."
}

Add-AutomationAssemblies

$safeExtension = $Extension.TrimStart(".")
if ([string]::IsNullOrWhiteSpace($safeExtension)) {
    $safeExtension = "jpg"
}

$sessionDir = New-SessionDir $OutputDir
$mediaDir = Join-Path $sessionDir "media"
New-Item -ItemType Directory -Path $mediaDir -Force | Out-Null

$eventsPath = Join-Path $sessionDir "events.jsonl"
$manifestPath = Join-Path $sessionDir "manifest.json"
$seenHashes = @{}
$events = New-Object System.Collections.ArrayList
$stoppedReason = "max_items_reached"

Write-Host "WeChat viewer batch save."
Write-Host "Precondition: a Moments image viewer is active."
Write-Host "Output: $sessionDir"
Write-Host "MaxItems: $MaxItems"
Write-Host "StartIndex: $StartIndex"
Write-Host "Publish-safe: this script only saves current viewer images and presses Right; it never publishes or sends messages."

if ($StartIndex -gt 1) {
    $existing = @(Get-ChildItem -LiteralPath $mediaDir -File -Filter ("{0}_*.{1}" -f $BaseName, $safeExtension) -ErrorAction SilentlyContinue)
    foreach ($file in $existing) {
        if ($file.BaseName -match [regex]::Escape($BaseName) + "_([0-9]+)$") {
            $existingIndex = [int]$Matches[1]
            if ($existingIndex -lt $StartIndex) {
                $hash = Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256
                $seenHashes[$hash.Hash.ToLowerInvariant()] = $existingIndex
            }
        }
    }
    Write-Host ("Loaded {0} existing hashes before StartIndex." -f $seenHashes.Count)
}

for ($index = $StartIndex; $index -le $MaxItems; $index++) {
    $fileName = "{0}_{1:D3}.{2}" -f $BaseName, $index, $safeExtension
    $targetPath = Join-Path $mediaDir $fileName
    Write-Host ("Saving item {0}/{1}: {2}" -f $index, $MaxItems, $fileName)

    if ($DryRun) {
        $event = [pscustomobject][ordered]@{
            type = "save_attempt"
            index = $index
            capturedAt = (Get-Date).ToString("o")
            path = $targetPath
            saved = $false
            length = 0
            sha256 = ""
            status = "dry_run"
            duplicateOf = $null
            error = ""
        }
        [void]$events.Add($event)
        Write-Jsonl -Value $event -Path $eventsPath

        if ($index -lt $MaxItems) {
            $navEvent = [pscustomobject][ordered]@{
                type = "navigate_next"
                index = $index
                capturedAt = (Get-Date).ToString("o")
                key = "Right"
                status = "dry_run"
            }
            [void]$events.Add($navEvent)
            Write-Jsonl -Value $navEvent -Path $eventsPath
        }
        $stoppedReason = "dry_run_complete"
        continue
    }

    $result = Save-CurrentViewerImage -TargetPath $targetPath -TargetDirectory $mediaDir -TargetFileName $fileName
    $event = [pscustomobject][ordered]@{
        type = "save_attempt"
        index = $index
        capturedAt = (Get-Date).ToString("o")
        path = $result.path
        saved = $result.saved
        length = $result.length
        sha256 = $result.sha256
        status = $result.status
        duplicateOf = $null
        error = $result.error
    }

    if (-not $result.saved) {
        $stoppedReason = "save_failed"
        [void]$events.Add($event)
        Write-Jsonl -Value $event -Path $eventsPath
        break
    }

    if ($seenHashes.ContainsKey($result.sha256)) {
        $event.status = "duplicate"
        $event.duplicateOf = $seenHashes[$result.sha256]
        $stoppedReason = "duplicate_detected"
        if (-not $KeepDuplicate) {
            Remove-Item -LiteralPath $targetPath -Force -ErrorAction SilentlyContinue
            $event.path = ""
        }
        [void]$events.Add($event)
        Write-Jsonl -Value $event -Path $eventsPath
        if ($StopOnDuplicate) {
            break
        }
    }
    else {
        $seenHashes[$result.sha256] = $index
        [void]$events.Add($event)
        Write-Jsonl -Value $event -Path $eventsPath
    }

    if ($index -lt $MaxItems) {
        $navEvent = [pscustomobject][ordered]@{
            type = "navigate_next"
            index = $index
            capturedAt = (Get-Date).ToString("o")
            key = "Right"
            status = "sent"
        }
        Write-Host "Navigating to next image with Right arrow."
        Send-Keys "{RIGHT}"
        Start-Sleep -Milliseconds $AfterNavigateWaitMilliseconds
        [void]$events.Add($navEvent)
        Write-Jsonl -Value $navEvent -Path $eventsPath
    }
}

$savedEvents = @($events | Where-Object { $_.type -eq "save_attempt" -and $_.status -eq "saved" })
$duplicateEvents = @($events | Where-Object { $_.type -eq "save_attempt" -and $_.status -eq "duplicate" })
$failedEvents = @($events | Where-Object { $_.type -eq "save_attempt" -and $_.status -eq "save_failed" })
$allMedia = @(Get-ChildItem -LiteralPath $mediaDir -File -Filter ("{0}_*.{1}" -f $BaseName, $safeExtension) -ErrorAction SilentlyContinue |
    Sort-Object Name |
    ForEach-Object {
        $hash = Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256
        [pscustomobject][ordered]@{
            path = $_.FullName
            length = $_.Length
            sha256 = $hash.Hash.ToLowerInvariant()
        }
    })
$allUniqueHashes = @($allMedia | Select-Object -ExpandProperty sha256 -Unique)

$manifest = [pscustomobject][ordered]@{
    createdAt = (Get-Date).ToString("o")
    method = "wechat_viewer_ctrl_s_address_bar_directory_filename_right_arrow"
    precondition = "WeChat Moments image viewer is active"
    dryRun = [bool]$DryRun
    outputDir = $sessionDir
    mediaDir = $mediaDir
    maxItems = $MaxItems
    startIndex = $StartIndex
    stopOnDuplicate = [bool]$StopOnDuplicate
    keepDuplicate = [bool]$KeepDuplicate
    stoppedReason = $stoppedReason
    savedCount = $allMedia.Count
    runSavedCount = $savedEvents.Count
    duplicateCount = $duplicateEvents.Count
    failedCount = $failedEvents.Count
    uniqueSha256Count = $allUniqueHashes.Count
    eventsFile = $eventsPath
    media = @($allMedia | ForEach-Object {
        [pscustomobject][ordered]@{
            path = $_.path
            length = $_.length
            sha256 = $_.sha256
        }
    })
}

$manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
$manifest | ConvertTo-Json -Depth 12
