[CmdletBinding()]
param(
    [string]$ScreenshotPath = "",
    [string]$OutputDir = "",
    [string]$LanguageTag = "zh-Hans-CN",
    [int]$TargetMoments = 20,
    [int]$MinY = -1,
    [switch]$CaptureCurrentWindow,
    [switch]$ValidateOnly
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

function Add-OcrLocatorAssemblies {
    Add-Type -AssemblyName UIAutomationClient
    Add-Type -AssemblyName UIAutomationTypes
    Add-Type -AssemblyName System.Drawing
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Runtime.WindowsRuntime

    [void][Windows.Storage.StorageFile, Windows.Storage, ContentType=WindowsRuntime]
    [void][Windows.Storage.FileAccessMode, Windows.Storage, ContentType=WindowsRuntime]
    [void][Windows.Storage.Streams.IRandomAccessStream, Windows.Storage.Streams, ContentType=WindowsRuntime]
    [void][Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType=WindowsRuntime]
    [void][Windows.Graphics.Imaging.SoftwareBitmap, Windows.Graphics.Imaging, ContentType=WindowsRuntime]
    [void][Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType=WindowsRuntime]
    [void][Windows.Media.Ocr.OcrResult, Windows.Foundation, ContentType=WindowsRuntime]
    [void][Windows.Globalization.Language, Windows.Globalization, ContentType=WindowsRuntime]

    if (-not ([System.Management.Automation.PSTypeName]"WechatOcrLocatorWin32").Type) {
        Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

public static class WechatOcrLocatorWin32 {
    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    public static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow);
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

    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
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

function Invoke-CaptureCurrentWindow {
    param([Parameter(Mandatory = $true)][string]$Path)

    $process = Get-WeixinProcess
    [void][WechatOcrLocatorWin32]::ShowWindowAsync($process.MainWindowHandle, 9)
    [void][WechatOcrLocatorWin32]::SetForegroundWindow($process.MainWindowHandle)
    Start-Sleep -Milliseconds 300
    $bounds = Get-WindowBounds $process.MainWindowHandle
    Save-WindowScreenshot -Bounds $bounds -Path $Path
    return $bounds
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

function ConvertTo-SafePathSegment {
    param([AllowNull()][string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return "item"
    }

    $safe = $Value -replace "[^A-Za-z0-9_.-]", "_"
    $safe = $safe.Trim("_", ".", "-")
    if (-not [string]::IsNullOrWhiteSpace($safe)) {
        if ($safe.Length -gt 64) {
            return $safe.Substring(0, 64)
        }
        return $safe
    }

    $hash = Get-TextHash -Text $Value
    return "item_" + $hash.Substring(0, 10)
}

function Invoke-WinRtAsync {
    param(
        [Parameter(Mandatory = $true)]$AsyncOperation,
        [Parameter(Mandatory = $true)][Type]$ResultType
    )

    $method = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq "AsTask" -and
        $_.IsGenericMethodDefinition -and
        ($_.GetParameters()).Count -eq 1 -and
        $_.ToString().StartsWith("System.Threading.Tasks.Task``1")
    } | Select-Object -First 1

    if ($null -eq $method) {
        throw "Could not bind WinRT AsTask<TResult> helper."
    }

    $task = $method.MakeGenericMethod($ResultType).Invoke($null, @($AsyncOperation))
    $task.Wait() | Out-Null
    return $task.Result
}

function Get-OcrEngine {
    param([string]$RequestedLanguageTag)

    $engine = $null
    if (-not [string]::IsNullOrWhiteSpace($RequestedLanguageTag)) {
        try {
            $language = [Windows.Globalization.Language]::new($RequestedLanguageTag)
            $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage($language)
        }
        catch {
            $engine = $null
        }
    }

    if ($null -eq $engine) {
        $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
    }
    if ($null -eq $engine) {
        throw "Windows OCR engine is not available. Install a Windows OCR language pack first."
    }

    return $engine
}

function Invoke-WindowsOcr {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$RequestedLanguageTag
    )

    $file = Invoke-WinRtAsync -AsyncOperation ([Windows.Storage.StorageFile]::GetFileFromPathAsync($Path)) -ResultType ([Windows.Storage.StorageFile])
    $stream = Invoke-WinRtAsync -AsyncOperation ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) -ResultType ([Windows.Storage.Streams.IRandomAccessStream])
    try {
        $decoder = Invoke-WinRtAsync -AsyncOperation ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) -ResultType ([Windows.Graphics.Imaging.BitmapDecoder])
        $bitmap = Invoke-WinRtAsync -AsyncOperation ($decoder.GetSoftwareBitmapAsync()) -ResultType ([Windows.Graphics.Imaging.SoftwareBitmap])
        $engine = Get-OcrEngine -RequestedLanguageTag $RequestedLanguageTag
        $result = Invoke-WinRtAsync -AsyncOperation ($engine.RecognizeAsync($bitmap)) -ResultType ([Windows.Media.Ocr.OcrResult])
        return [pscustomobject][ordered]@{
            result = $result
            language = $engine.RecognizerLanguage.LanguageTag
        }
    }
    finally {
        if ($null -ne $stream) {
            $stream.Dispose()
        }
    }
}

function Get-LineBounds {
    param([Parameter(Mandatory = $true)]$Words)

    $rects = @($Words | ForEach-Object { $_.BoundingRect })
    if ($rects.Count -eq 0) {
        return $null
    }

    $left = ($rects | Measure-Object Left -Minimum).Minimum
    $top = ($rects | Measure-Object Top -Minimum).Minimum
    $right = ($rects | ForEach-Object { $_.Left + $_.Width } | Measure-Object -Maximum).Maximum
    $bottom = ($rects | ForEach-Object { $_.Top + $_.Height } | Measure-Object -Maximum).Maximum

    return [pscustomobject][ordered]@{
        left = [int][Math]::Floor($left)
        top = [int][Math]::Floor($top)
        right = [int][Math]::Ceiling($right)
        bottom = [int][Math]::Ceiling($bottom)
        width = [int][Math]::Ceiling($right - $left)
        height = [int][Math]::Ceiling($bottom - $top)
        centerX = [int][Math]::Round(($left + $right) / 2)
        centerY = [int][Math]::Round(($top + $bottom) / 2)
    }
}

function Convert-OcrResultToLines {
    param([Parameter(Mandatory = $true)]$OcrResult)

    $lines = New-Object System.Collections.ArrayList
    $index = 0
    foreach ($line in $OcrResult.Lines) {
        $words = @($line.Words)
        if ($words.Count -eq 0) {
            continue
        }

        $text = ($words | ForEach-Object { $_.Text }) -join ""
        if ([string]::IsNullOrWhiteSpace($text)) {
            continue
        }

        $bounds = Get-LineBounds -Words $words
        if ($null -eq $bounds) {
            continue
        }

        $index += 1
        [void]$lines.Add([pscustomobject][ordered]@{
            lineId = "line_{0:D3}" -f $index
            text = $text.Trim()
            textLength = $text.Trim().Length
            textSha256 = Get-TextHash -Text $text.Trim()
            bounds = $bounds
            words = @($words | ForEach-Object {
                $wordBounds = Get-LineBounds -Words @($_)
                [pscustomobject][ordered]@{
                    text = $_.Text
                    bounds = $wordBounds
                }
            })
        })
    }

    return @($lines | Sort-Object { $_.bounds.top }, { $_.bounds.left })
}

function Test-DateAnchorText {
    param([AllowNull()][string]$Text)

    if ([string]::IsNullOrWhiteSpace($Text)) {
        return $false
    }

    $value = $Text.Trim()
    return ($value -match "^(今天|昨天|前天|刚刚|[0-9一二三四五六七八九十]{1,3}分钟前|[0-9一二三四五六七八九十]{1,2}小时前|[0-9]{1,2}月[0-9]{1,2}日|[0-9]{4}年|[0-9]{1,2}/[0-9]{1,2})$")
}

function Test-LikelyContentLine {
    param(
        [Parameter(Mandatory = $true)]$Line,
        [int]$ImageWidth
    )

    if ($Line.bounds.left -lt [int]($ImageWidth * 0.18)) {
        return $false
    }
    if ($Line.textLength -lt 2) {
        return $false
    }
    if (Test-DateAnchorText -Text $Line.text) {
        return $false
    }
    return $true
}

function New-PointObject {
    param([int]$X, [int]$Y)
    return [pscustomobject][ordered]@{ x = $X; y = $Y }
}

function Get-GroupBounds {
    param([Parameter(Mandatory = $true)]$Lines)

    $lineArray = @($Lines)
    if ($lineArray.Count -eq 0) {
        return $null
    }

    $left = ($lineArray | ForEach-Object { $_.bounds.left } | Measure-Object -Minimum).Minimum
    $top = ($lineArray | ForEach-Object { $_.bounds.top } | Measure-Object -Minimum).Minimum
    $right = ($lineArray | ForEach-Object { $_.bounds.right } | Measure-Object -Maximum).Maximum
    $bottom = ($lineArray | ForEach-Object { $_.bounds.bottom } | Measure-Object -Maximum).Maximum

    return [pscustomobject][ordered]@{
        left = [int]$left
        top = [int]$top
        right = [int]$right
        bottom = [int]$bottom
        width = [int]($right - $left)
        height = [int]($bottom - $top)
    }
}

function New-MomentCandidatesFromOcr {
    param(
        [Parameter(Mandatory = $true)]$Lines,
        [Parameter(Mandatory = $true)][int]$ImageWidth,
        [Parameter(Mandatory = $true)][int]$ImageHeight,
        [int]$MinimumY,
        [int]$Limit = 20
    )

    if ($MinimumY -lt 0) {
        $MinimumY = [int]([Math]::Max(110, $ImageHeight * 0.14))
    }

    $usable = @($Lines | Where-Object {
        $_.bounds.top -ge $MinimumY -and
        $_.bounds.top -lt ($ImageHeight - 35) -and
        $_.bounds.height -ge 8 -and
        $_.bounds.width -ge 10
    } | Sort-Object { $_.bounds.top }, { $_.bounds.left })

    $groups = New-Object System.Collections.ArrayList
    $current = New-Object System.Collections.ArrayList
    $previousBottom = -1
    foreach ($line in $usable) {
        $isAnchor = ($line.bounds.left -lt [int]($ImageWidth * 0.28) -and (Test-DateAnchorText -Text $line.text))
        $largeGap = ($previousBottom -gt 0 -and ($line.bounds.top - $previousBottom) -gt 58)
        if (($isAnchor -or $largeGap) -and $current.Count -gt 0) {
            [void]$groups.Add(@($current))
            $current = New-Object System.Collections.ArrayList
        }

        [void]$current.Add($line)
        $previousBottom = [Math]::Max($previousBottom, [int]$line.bounds.bottom)
    }
    if ($current.Count -gt 0) {
        [void]$groups.Add(@($current))
    }

    $candidates = New-Object System.Collections.ArrayList
    $candidateIndex = 0
    foreach ($group in $groups) {
        $groupLines = @($group)
        $contentLines = @($groupLines | Where-Object { Test-LikelyContentLine -Line $_ -ImageWidth $ImageWidth })
        $anchorLines = @($groupLines | Where-Object { $_.bounds.left -lt [int]($ImageWidth * 0.28) -and (Test-DateAnchorText -Text $_.text) })
        if ($contentLines.Count -eq 0 -and $anchorLines.Count -eq 0) {
            continue
        }

        $groupBounds = Get-GroupBounds -Lines $groupLines
        if ($anchorLines.Count -eq 0 -and
            $groupBounds.width -gt [int]($ImageWidth * 0.72) -and
            $groupBounds.left -lt [int]($ImageWidth * 0.20)) {
            continue
        }

        $firstContentLine = if ($contentLines.Count -gt 0) { $contentLines[0] } else { $null }
        $textPoint = $null
        if ($null -ne $firstContentLine) {
            $textPoint = New-PointObject -X ([int]$firstContentLine.bounds.centerX) -Y ([int]$firstContentLine.bounds.centerY)
        }

        $materialPoint = $null
        $materialPointReason = ""
        if ($null -ne $firstContentLine -and $firstContentLine.bounds.left -gt [int]($ImageWidth * 0.42)) {
            $x = [int]($firstContentLine.bounds.left - 55)
            if ($x -lt [int]($ImageWidth * 0.18)) {
                $x = [int]($ImageWidth * 0.26)
            }
            $y = [int]([Math]::Max($groupBounds.top + 22, $firstContentLine.bounds.centerY))
            $materialPoint = New-PointObject -X $x -Y $y
            $materialPointReason = "right_side_card_text"
        }
        elseif ($anchorLines.Count -gt 0) {
            $anchor = $anchorLines[0]
            $materialPoint = New-PointObject -X ([int]($ImageWidth * 0.38)) -Y ([int]($anchor.bounds.bottom + 42))
            $materialPointReason = "date_anchor_without_readable_content"
        }

        $candidateIndex += 1
        [void]$candidates.Add([pscustomobject][ordered]@{
            candidateId = "ocr_moment_{0:D3}" -f $candidateIndex
            bounds = $groupBounds
            anchorText = if ($anchorLines.Count -gt 0) { [string]$anchorLines[0].text } else { "" }
            textPoint = $textPoint
            materialPoint = $materialPoint
            materialPointReason = $materialPointReason
            lineIds = @($groupLines | ForEach-Object { $_.lineId })
            textLineIds = @($contentLines | ForEach-Object { $_.lineId })
            textLength = (@($contentLines | ForEach-Object { $_.textLength }) | Measure-Object -Sum).Sum
            locatorConfidence = if ($anchorLines.Count -gt 0 -and $contentLines.Count -gt 0) { "high" } elseif ($contentLines.Count -gt 0) { "medium" } else { "low" }
        })

        if ($candidates.Count -ge $Limit) {
            break
        }
    }

    return @($candidates)
}

Add-OcrLocatorAssemblies

if ($ValidateOnly) {
    [pscustomobject][ordered]@{
        ok = $true
        availableLanguages = @([Windows.Media.Ocr.OcrEngine]::AvailableRecognizerLanguages | ForEach-Object {
            [pscustomobject][ordered]@{
                languageTag = $_.LanguageTag
                displayName = $_.DisplayName
            }
        })
    } | ConvertTo-Json -Depth 8
    return
}

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $repoRoot = Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")
    $OutputDir = Join-Path $repoRoot ("windows-probe\ocr-runs\" + (Get-Date -Format "yyyyMMdd_HHmmss"))
}
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

$windowBounds = $null
if ([string]::IsNullOrWhiteSpace($ScreenshotPath)) {
    $ScreenshotPath = Join-Path $OutputDir "window.png"
    $CaptureCurrentWindow = $true
}

if ($CaptureCurrentWindow) {
    $windowBounds = Invoke-CaptureCurrentWindow -Path $ScreenshotPath
}
elseif (-not (Test-Path -LiteralPath $ScreenshotPath)) {
    throw "ScreenshotPath not found: $ScreenshotPath"
}

$resolvedScreenshotPath = (Resolve-Path -LiteralPath $ScreenshotPath).Path
$bitmapInfo = [System.Drawing.Image]::FromFile($resolvedScreenshotPath)
try {
    $imageWidth = [int]$bitmapInfo.Width
    $imageHeight = [int]$bitmapInfo.Height
}
finally {
    $bitmapInfo.Dispose()
}

$ocr = Invoke-WindowsOcr -Path $resolvedScreenshotPath -RequestedLanguageTag $LanguageTag
$lines = @(Convert-OcrResultToLines -OcrResult $ocr.result)
$candidates = @(New-MomentCandidatesFromOcr -Lines $lines -ImageWidth $imageWidth -ImageHeight $imageHeight -MinimumY $MinY -Limit $TargetMoments)

$rawLinesPath = Join-Path $OutputDir "ocr_lines.json"
$resultPath = Join-Path $OutputDir "locator_result.json"

Save-Json -Value ([pscustomobject][ordered]@{
    createdAt = (Get-Date).ToString("o")
    screenshot = $resolvedScreenshotPath
    languageTag = $ocr.language
    image = [pscustomobject][ordered]@{
        width = $imageWidth
        height = $imageHeight
    }
    windowBounds = $windowBounds
    lineCount = $lines.Count
    lines = @($lines)
}) -Path $rawLinesPath -Depth 14

$result = [pscustomobject][ordered]@{
    createdAt = (Get-Date).ToString("o")
    method = "windows_media_ocr_moments_locator_v1"
    screenshot = $resolvedScreenshotPath
    languageTag = $ocr.language
    image = [pscustomobject][ordered]@{
        width = $imageWidth
        height = $imageHeight
    }
    minY = if ($MinY -lt 0) { [int]([Math]::Max(110, $imageHeight * 0.14)) } else { $MinY }
    lineCount = $lines.Count
    candidateCount = $candidates.Count
    ocrLinesFile = $rawLinesPath
    candidates = @($candidates)
}

Save-Json -Value $result -Path $resultPath -Depth 14
$result | ConvertTo-Json -Depth 14
