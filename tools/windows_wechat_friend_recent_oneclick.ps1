[CmdletBinding()]
param(
    [string]$DisplayName = "friend-name",
    [int]$TargetMoments = 5,
    [string]$OutputDir = "",
    [int]$MaxPages = 8,
    [string]$OcrLanguageTag = "zh-Hans-CN",
    [switch]$RunNow,
    [switch]$DryRun,
    [switch]$ValidateOnly
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"
$script:LogListBox = $null

function Add-OneClickAssemblies {
    Add-Type -AssemblyName UIAutomationClient
    Add-Type -AssemblyName UIAutomationTypes
    Add-Type -AssemblyName System.Drawing
    Add-Type -AssemblyName System.Windows.Forms

    if (-not ([System.Management.Automation.PSTypeName]"WechatFriendOneClickWin32").Type) {
        Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

public static class WechatFriendOneClickWin32 {
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

function Add-Log {
    param([string]$Text)

    $line = "{0:HH:mm:ss} {1}" -f (Get-Date), $Text
    if ($null -ne $script:LogListBox) {
        [void]$script:LogListBox.Items.Add($line)
        $script:LogListBox.TopIndex = [Math]::Max(0, $script:LogListBox.Items.Count - 1)
        [System.Windows.Forms.Application]::DoEvents()
    }
    else {
        Write-Host $line
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

function Focus-WeixinWindow {
    $process = Get-WeixinProcess
    [void][WechatFriendOneClickWin32]::ShowWindowAsync($process.MainWindowHandle, 9)
    [void][WechatFriendOneClickWin32]::SetForegroundWindow($process.MainWindowHandle)
    Start-Sleep -Milliseconds 300
    return [pscustomobject][ordered]@{
        process = $process
        bounds = Get-WindowBounds $process.MainWindowHandle
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

function Save-Json {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$Path,
        [int]$Depth = 16
    )

    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    $Value | ConvertTo-Json -Depth $Depth | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Write-Jsonl {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$Path
    )

    ($Value | ConvertTo-Json -Depth 14 -Compress) | Add-Content -LiteralPath $Path -Encoding UTF8
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

function New-PointObject {
    param([int]$X, [int]$Y)
    return [pscustomobject][ordered]@{ x = $X; y = $Y }
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

function Add-CaptureEvent {
    param(
        [System.Collections.ArrayList]$Events,
        [Parameter(Mandatory = $true)][string]$Type,
        [string]$PostId = "",
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

function Invoke-WeixinScroll {
    param(
        [Parameter(Mandatory = $true)]$Bounds,
        [int]$WheelCount = 4,
        [int]$WheelDelta = -360,
        [int]$WaitMilliseconds = 1200
    )

    if ($DryRun) {
        return
    }

    $windowX = [int]($Bounds.width / 2)
    $windowY = [int]($Bounds.height * 0.72)
    [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point(($Bounds.left + $windowX), ($Bounds.top + $windowY))
    Start-Sleep -Milliseconds 100
    for ($i = 0; $i -lt $WheelCount; $i++) {
        [WechatFriendOneClickWin32]::mouse_event([WechatFriendOneClickWin32]::MOUSEEVENTF_WHEEL, 0, 0, $WheelDelta, [UIntPtr]::Zero)
        Start-Sleep -Milliseconds 180
    }
    Start-Sleep -Milliseconds $WaitMilliseconds
}

function Send-Keys {
    param([Parameter(Mandatory = $true)][string]$Keys)

    $wshell = New-Object -ComObject WScript.Shell
    $wshell.SendKeys($Keys)
}

function Test-TextPixel {
    param([Parameter(Mandatory = $true)][System.Drawing.Color]$Color)

    $max = [Math]::Max($Color.R, [Math]::Max($Color.G, $Color.B))
    $min = [Math]::Min($Color.R, [Math]::Min($Color.G, $Color.B))
    $brightness = ($Color.R + $Color.G + $Color.B) / 765.0
    $chroma = ($max - $min) / 255.0
    return ($brightness -lt 0.42 -and $chroma -lt 0.42)
}

function Test-MaterialPixel {
    param([Parameter(Mandatory = $true)][System.Drawing.Color]$Color)

    $max = [Math]::Max($Color.R, [Math]::Max($Color.G, $Color.B))
    $min = [Math]::Min($Color.R, [Math]::Min($Color.G, $Color.B))
    $brightness = ($Color.R + $Color.G + $Color.B) / 765.0
    $chroma = ($max - $min) / 255.0

    if ($brightness -gt 0.94) {
        return $false
    }
    if ($brightness -lt 0.15) {
        return $false
    }
    return ($chroma -gt 0.06 -or $brightness -lt 0.78)
}

function Get-BlockMaterialScore {
    param(
        [Parameter(Mandatory = $true)][System.Drawing.Bitmap]$Bitmap,
        [int]$X,
        [int]$Y,
        [int]$Size = 44
    )

    $count = 0
    $total = 0
    $maxX = [Math]::Min($Bitmap.Width - 1, $X + $Size)
    $maxY = [Math]::Min($Bitmap.Height - 1, $Y + $Size)
    for ($yy = $Y; $yy -le $maxY; $yy += 4) {
        for ($xx = $X; $xx -le $maxX; $xx += 4) {
            $total += 1
            if (Test-MaterialPixel -Color $Bitmap.GetPixel($xx, $yy)) {
                $count += 1
            }
        }
    }

    if ($total -eq 0) {
        return 0
    }
    return ($count / [double]$total)
}

function Get-BitmapRegionHash {
    param(
        [Parameter(Mandatory = $true)][System.Drawing.Bitmap]$Bitmap,
        [int]$CenterX,
        [int]$CenterY,
        [int]$Size = 72
    )

    $xStart = [Math]::Max(0, $CenterX - [int]($Size / 2))
    $yStart = [Math]::Max(0, $CenterY - [int]($Size / 2))
    $xEnd = [Math]::Min($Bitmap.Width - 1, $CenterX + [int]($Size / 2))
    $yEnd = [Math]::Min($Bitmap.Height - 1, $CenterY + [int]($Size / 2))
    $builder = New-Object System.Text.StringBuilder

    for ($y = $yStart; $y -le $yEnd; $y += 8) {
        for ($x = $xStart; $x -le $xEnd; $x += 8) {
            $c = $Bitmap.GetPixel($x, $y)
            [void]$builder.AppendFormat("{0:x2}{1:x2}{2:x2}", $c.R, $c.G, $c.B)
        }
    }

    return Get-TextHash -Text $builder.ToString()
}

function Find-TextProbeCandidates {
    param(
        [Parameter(Mandatory = $true)][string]$ScreenshotPath,
        [int]$MinY,
        [int]$MaxCandidates = 28
    )

    $bitmap = [System.Drawing.Bitmap]::FromFile($ScreenshotPath)
    try {
        $xStart = [int]([Math]::Max(70, $bitmap.Width * 0.24))
        $xEnd = [int]([Math]::Min($bitmap.Width - 60, $bitmap.Width * 0.84))
        $yStart = [int]([Math]::Max($MinY, $bitmap.Height * 0.16))
        $yEnd = [int]([Math]::Min($bitmap.Height - 65, $bitmap.Height * 0.94))
        $runs = New-Object System.Collections.ArrayList
        $active = $null

        for ($y = $yStart; $y -le $yEnd; $y += 3) {
            $darkCount = 0
            $firstDark = $null
            $lastDark = $null
            for ($x = $xStart; $x -le $xEnd; $x += 4) {
                if (Test-TextPixel -Color $bitmap.GetPixel($x, $y)) {
                    $darkCount += 1
                    if ($null -eq $firstDark) {
                        $firstDark = $x
                    }
                    $lastDark = $x
                }
            }

            if ($darkCount -ge 3 -and $null -ne $firstDark -and $null -ne $lastDark) {
                if ($null -eq $active) {
                    $active = [pscustomobject][ordered]@{
                        top = $y
                        bottom = $y
                        first = $firstDark
                        last = $lastDark
                    }
                }
                else {
                    $active.bottom = $y
                    $active.first = [Math]::Min([int]$active.first, [int]$firstDark)
                    $active.last = [Math]::Max([int]$active.last, [int]$lastDark)
                }
            }
            elseif ($null -ne $active) {
                [void]$runs.Add($active)
                $active = $null
            }
        }
        if ($null -ne $active) {
            [void]$runs.Add($active)
        }

        $points = New-Object System.Collections.ArrayList
        foreach ($run in $runs) {
            $height = [int]$run.bottom - [int]$run.top
            $width = [int]$run.last - [int]$run.first
            if ($height -lt 3 -or $height -gt 90 -or $width -lt 10) {
                continue
            }

            $x = [int](([int]$run.first + [int]$run.last) / 2)
            $y = [int](([int]$run.top + [int]$run.bottom) / 2)
            $tooClose = $false
            foreach ($existing in $points) {
                if ([Math]::Abs([int]$existing.y - $y) -lt 26) {
                    $tooClose = $true
                    break
                }
            }
            if (-not $tooClose) {
                [void]$points.Add([pscustomobject][ordered]@{ x = $x; y = $y; source = "dark_text_rows" })
            }
        }

        if ($points.Count -lt 8) {
            $fallbackXs = @([int]($bitmap.Width * 0.34), [int]($bitmap.Width * 0.48), [int]($bitmap.Width * 0.62))
            for ($y = $yStart; $y -le $yEnd; $y += 54) {
                foreach ($x in $fallbackXs) {
                    [void]$points.Add([pscustomobject][ordered]@{ x = $x; y = $y; source = "fallback_grid" })
                }
            }
        }

        return @($points | Sort-Object y, x | Select-Object -First $MaxCandidates)
    }
    finally {
        $bitmap.Dispose()
    }
}

function Find-MaterialCandidate {
    param(
        [Parameter(Mandatory = $true)][string]$ScreenshotPath,
        [Parameter(Mandatory = $true)][int]$AnchorY,
        [Parameter(Mandatory = $true)][int]$NextAnchorY,
        [int]$MinY
    )

    $bitmap = [System.Drawing.Bitmap]::FromFile($ScreenshotPath)
    try {
        $xStart = [int]([Math]::Max(55, $bitmap.Width * 0.12))
        $xEnd = [int]([Math]::Min($bitmap.Width - 70, $bitmap.Width * 0.86))
        $yStart = [int]([Math]::Max($MinY, $AnchorY + 32))
        $yEnd = [int]([Math]::Min($bitmap.Height - 65, [Math]::Min($NextAnchorY - 12, $AnchorY + 260)))
        if ($yEnd -le $yStart) {
            return $null
        }

        $best = $null
        for ($y = $yStart; $y -le $yEnd; $y += 14) {
            for ($x = $xStart; $x -le $xEnd; $x += 14) {
                $score = Get-BlockMaterialScore -Bitmap $bitmap -X $x -Y $y -Size 44
                if ($score -gt 0.24 -and ($null -eq $best -or $score -gt $best.score)) {
                    $best = [pscustomobject][ordered]@{
                        x = $x
                        y = $y
                        score = $score
                    }
                }
            }
        }

        if ($null -eq $best) {
            return $null
        }

        $centerX = [int]($best.x + 22)
        $centerY = [int]($best.y + 22)
        return [pscustomobject][ordered]@{
            point = New-PointObject -X $centerX -Y $centerY
            score = $best.score
            fingerprint = Get-BitmapRegionHash -Bitmap $bitmap -CenterX $centerX -CenterY $centerY
        }
    }
    finally {
        $bitmap.Dispose()
    }
}

function Find-AllMaterialCandidates {
    param(
        [Parameter(Mandatory = $true)][string]$ScreenshotPath,
        [int]$MinY,
        [int]$MaxCandidates = 10
    )

    $bitmap = [System.Drawing.Bitmap]::FromFile($ScreenshotPath)
    try {
        $xStart = [int]([Math]::Max(55, $bitmap.Width * 0.12))
        $xEnd = [int]([Math]::Min($bitmap.Width - 70, $bitmap.Width * 0.86))
        $yStart = [int]([Math]::Max($MinY, $bitmap.Height * 0.16))
        $yEnd = [int]([Math]::Min($bitmap.Height - 65, $bitmap.Height * 0.94))
        $raw = New-Object System.Collections.ArrayList

        for ($y = $yStart; $y -le $yEnd; $y += 20) {
            for ($x = $xStart; $x -le $xEnd; $x += 20) {
                $score = Get-BlockMaterialScore -Bitmap $bitmap -X $x -Y $y -Size 52
                if ($score -gt 0.32) {
                    [void]$raw.Add([pscustomobject][ordered]@{
                        x = [int]($x + 26)
                        y = [int]($y + 26)
                        score = $score
                    })
                }
            }
        }

        $picked = New-Object System.Collections.ArrayList
        foreach ($candidate in @($raw | Sort-Object score -Descending)) {
            $tooClose = $false
            foreach ($existing in $picked) {
                $dx = [Math]::Abs([int]$existing.point.x - [int]$candidate.x)
                $dy = [Math]::Abs([int]$existing.point.y - [int]$candidate.y)
                if ($dx -lt 95 -and $dy -lt 95) {
                    $tooClose = $true
                    break
                }
            }
            if (-not $tooClose) {
                [void]$picked.Add([pscustomobject][ordered]@{
                    point = New-PointObject -X ([int]$candidate.x) -Y ([int]$candidate.y)
                    score = $candidate.score
                    fingerprint = Get-BitmapRegionHash -Bitmap $bitmap -CenterX ([int]$candidate.x) -CenterY ([int]$candidate.y)
                })
            }
            if ($picked.Count -ge $MaxCandidates) {
                break
            }
        }

        return @($picked | Sort-Object { $_.point.y })
    }
    finally {
        $bitmap.Dispose()
    }
}

function Invoke-TextProbe {
    param(
        [Parameter(Mandatory = $true)][int]$X,
        [Parameter(Mandatory = $true)][int]$Y,
        [Parameter(Mandatory = $true)][string]$ProbeDir
    )

    $textScript = Join-Path $PSScriptRoot "windows_wechat_copy_visible_text.ps1"
    $safeDir = Join-Path $ProbeDir ("text_{0}_{1}" -f $X, $Y)
    & $textScript -X $X -Y $Y -OutputDir $safeDir | Out-Null

    $summaryPath = Join-Path $safeDir "summary.json"
    if (-not (Test-Path -LiteralPath $summaryPath)) {
        return $null
    }

    $summary = Get-Content -LiteralPath $summaryPath -Encoding UTF8 -Raw | ConvertFrom-Json
    $status = [string](Get-ObjectProperty -InputObject $summary -Name "status" -Default "")
    $length = [int](Get-ObjectProperty -InputObject $summary -Name "copiedTextLength" -Default 0)
    $hash = [string](Get-ObjectProperty -InputObject $summary -Name "copiedTextSha256" -Default "")
    if ($status -ne "copied" -or $length -lt 2 -or [string]::IsNullOrWhiteSpace($hash)) {
        return $null
    }

    $textFile = [string](Get-ObjectProperty -InputObject $summary -Name "textFile" -Default "")
    $text = ""
    if (-not [string]::IsNullOrWhiteSpace($textFile) -and (Test-Path -LiteralPath $textFile)) {
        $text = Get-Content -LiteralPath $textFile -Encoding UTF8 -Raw
    }

    return [pscustomobject][ordered]@{
        x = $X
        y = $Y
        length = $length
        sha256 = $hash
        textFile = $textFile
        text = $text
    }
}

function Test-UsefulMomentText {
    param(
        [AllowNull()][string]$Text,
        [string]$FriendName
    )

    if ([string]::IsNullOrWhiteSpace($Text)) {
        return $false
    }
    $trimmed = $Text.Trim()
    if ($trimmed.Length -lt 2) {
        return $false
    }
    if (-not [string]::IsNullOrWhiteSpace($FriendName) -and $trimmed -eq $FriendName.Trim()) {
        return $false
    }
    if ($trimmed -match "^(https?://|weixin://|wx[a-z0-9]+://)") {
        return $false
    }
    if ($trimmed -match "^\d{1,2}:\d{2}$") {
        return $false
    }
    return $true
}

function Test-PointObject {
    param([AllowNull()]$Point)

    return ($null -ne $Point -and
        $null -ne (Get-ObjectProperty -InputObject $Point -Name "x") -and
        $null -ne (Get-ObjectProperty -InputObject $Point -Name "y"))
}

function Invoke-OcrLocator {
    param(
        [Parameter(Mandatory = $true)][string]$ScreenshotPath,
        [Parameter(Mandatory = $true)][string]$PageDir,
        [Parameter(Mandatory = $true)][int]$TargetRemaining,
        [Parameter(Mandatory = $true)][string]$LanguageTag
    )

    $locatorScript = Join-Path $PSScriptRoot "windows_wechat_ocr_locator.ps1"
    $ocrDir = Join-Path $PageDir "ocr"
    $locatorParams = @{
        ScreenshotPath = $ScreenshotPath
        OutputDir = $ocrDir
        TargetMoments = [Math]::Max(1, $TargetRemaining)
        LanguageTag = $LanguageTag
    }
    & $locatorScript @locatorParams | Out-Null

    $resultPath = Join-Path $ocrDir "locator_result.json"
    if (-not (Test-Path -LiteralPath $resultPath)) {
        throw "OCR locator did not produce locator_result.json."
    }

    return Get-Content -LiteralPath $resultPath -Encoding UTF8 -Raw | ConvertFrom-Json
}

function New-OcrMaterialCandidate {
    param(
        [Parameter(Mandatory = $true)]$Point,
        [Parameter(Mandatory = $true)][string]$ScreenshotPath
    )

    if (-not (Test-PointObject -Point $Point)) {
        return $null
    }

    $bitmap = [System.Drawing.Bitmap]::FromFile($ScreenshotPath)
    try {
        $x = [int](Get-IntProperty -InputObject $Point -Name "x")
        $y = [int](Get-IntProperty -InputObject $Point -Name "y")
        if ($x -lt 0 -or $y -lt 0 -or $x -ge $bitmap.Width -or $y -ge $bitmap.Height) {
            return $null
        }

        return [pscustomobject][ordered]@{
            point = New-PointObject -X $x -Y $y
            score = 1.0
            fingerprint = Get-BitmapRegionHash -Bitmap $bitmap -CenterX $x -CenterY $y
            source = "ocr_locator"
        }
    }
    finally {
        $bitmap.Dispose()
    }
}

function Get-VisibleMomentCandidates {
    param(
        [Parameter(Mandatory = $true)][string]$FriendName,
        [Parameter(Mandatory = $true)][string]$PageDir,
        [Parameter(Mandatory = $true)][string]$ScreenshotPath,
        [Parameter(Mandatory = $true)]$Bounds,
        [Parameter(Mandatory = $true)]$SeenTextHashes,
        [Parameter(Mandatory = $true)]$SeenMaterialHashes,
        [Parameter(Mandatory = $true)][int]$TargetRemaining,
        [Parameter(Mandatory = $true)][string]$LanguageTag
    )

    $locator = Invoke-OcrLocator -ScreenshotPath $ScreenshotPath -PageDir $PageDir -TargetRemaining $TargetRemaining -LanguageTag $LanguageTag
    $ocrCandidates = @(Get-ArrayProperty -InputObject $locator -Name "candidates")
    $moments = New-Object System.Collections.ArrayList

    Add-Log ("OCR locator found {0} candidates." -f $ocrCandidates.Count)
    foreach ($ocrCandidate in $ocrCandidates) {
        $textProbe = $null
        $textAttempted = $false
        $textAttemptStatus = "not_attempted"
        $textDuplicate = $false
        $material = $null
        $materialDuplicate = $false
        $textPoint = Get-ObjectProperty -InputObject $ocrCandidate -Name "textPoint"
        $materialPoint = Get-ObjectProperty -InputObject $ocrCandidate -Name "materialPoint"

        if (Test-PointObject -Point $materialPoint) {
            $material = New-OcrMaterialCandidate -Point $materialPoint -ScreenshotPath $ScreenshotPath
            if ($null -ne $material -and -not [string]::IsNullOrWhiteSpace($material.fingerprint)) {
                if ($SeenMaterialHashes.ContainsKey($material.fingerprint)) {
                    $materialDuplicate = $true
                }
                else {
                    $SeenMaterialHashes[$material.fingerprint] = $true
                }
            }
        }

        if (Test-PointObject -Point $textPoint) {
            $textAttempted = $true
            if ($DryRun) {
                $textAttemptStatus = "dry_run"
                $textProbe = [pscustomobject][ordered]@{
                    x = Get-IntProperty -InputObject $textPoint -Name "x"
                    y = Get-IntProperty -InputObject $textPoint -Name "y"
                    length = Get-IntProperty -InputObject $ocrCandidate -Name "textLength"
                    sha256 = ""
                    textFile = ""
                    text = ""
                }
            }
            else {
                try {
                    $probe = Invoke-TextProbe -X (Get-IntProperty -InputObject $textPoint -Name "x") -Y (Get-IntProperty -InputObject $textPoint -Name "y") -ProbeDir $PageDir
                    if ($null -eq $probe) {
                        $textAttemptStatus = "copy_failed"
                    }
                    elseif (-not (Test-UsefulMomentText -Text $probe.text -FriendName $FriendName)) {
                        $textAttemptStatus = "not_moment_text"
                    }
                    elseif ($SeenTextHashes.ContainsKey($probe.sha256)) {
                        $textDuplicate = $true
                        $textProbe = $probe
                        $textAttemptStatus = "copied_duplicate"
                    }
                    else {
                        $SeenTextHashes[$probe.sha256] = $true
                        $textProbe = $probe
                        $textAttemptStatus = "copied"
                    }
                }
                catch {
                    $textAttemptStatus = "failed: $($_.Exception.Message)"
                    Add-Log ("OCR text point failed at y={0}: {1}" -f (Get-IntProperty -InputObject $textPoint -Name "y"), $_.Exception.Message)
                }
            }
        }

        if ($null -eq $textProbe -and $null -eq $material) {
            continue
        }

        $candidateY = if ($null -ne $textProbe) {
            [int]$textProbe.y
        }
        elseif ($null -ne $material) {
            [int]$material.point.y
        }
        else {
            0
        }

        [void]$moments.Add([pscustomobject][ordered]@{
            y = $candidateY
            textProbe = $textProbe
            textAttempted = $textAttempted
            textAttemptStatus = $textAttemptStatus
            textDuplicate = $textDuplicate
            material = $material
            materialDuplicate = $materialDuplicate
            detection = "ocr_locator"
            ocrCandidateId = Get-StringProperty -InputObject $ocrCandidate -Name "candidateId"
            locatorConfidence = Get-StringProperty -InputObject $ocrCandidate -Name "locatorConfidence"
        })
    }

    return @($moments | Sort-Object y)
}

function New-ProfileObject {
    param(
        [Parameter(Mandatory = $true)][string]$FriendName,
        [Parameter(Mandatory = $true)][string]$SessionId,
        [Parameter(Mandatory = $true)][string]$CapturedAt
    )

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
            displayName = $FriendName
            profileId = ""
            remarkName = ""
            alias = ""
            profileUrlFile = ""
            profileUrlLength = 0
            profileUrlSha256 = ""
            visibilityStatus = "visible"
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
            method = "windows_friend_recent_oneclick_v1"
            operator = $env:USERNAME
            notes = "User manually opened the target friend's Moments page before starting."
        }
    }
}

function New-MomentObject {
    param(
        [Parameter(Mandatory = $true)][string]$PostId,
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
        postId = $PostId
        status = "partial"
        postType = "unknown"
        author = [pscustomobject][ordered]@{
            displayName = [string]$Profile.profile.displayName
            profileId = [string]$Profile.profile.profileId
        }
        postedAtText = ""
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

function Set-MomentTextFromProbe {
    param(
        [Parameter(Mandatory = $true)]$Moment,
        [Parameter(Mandatory = $true)]$Probe,
        [Parameter(Mandatory = $true)][string]$MomentDir,
        [Parameter(Mandatory = $true)][string]$RootDir
    )

    $textDir = Join-Path $MomentDir "text"
    New-Item -ItemType Directory -Path $textDir -Force | Out-Null
    $textPath = Join-Path $textDir "copied_text.txt"
    if (-not [string]::IsNullOrWhiteSpace($Probe.textFile) -and (Test-Path -LiteralPath $Probe.textFile)) {
        Copy-Item -LiteralPath $Probe.textFile -Destination $textPath -Force
    }
    else {
        Set-Content -LiteralPath $textPath -Value $Probe.text -Encoding UTF8
    }

    $Moment.text = [pscustomobject][ordered]@{
        textFile = ConvertTo-CapturePath -Path $textPath -RootDir $RootDir
        length = [int]$Probe.length
        sha256 = [string]$Probe.sha256
        captureMethod = "wechat_moments_right_click_text_copy_menu"
        isFoldedAtCapture = $false
    }
}

function Invoke-LinkCaptureFromPoint {
    param(
        [Parameter(Mandatory = $true)]$Point,
        [Parameter(Mandatory = $true)][string]$LinkDir,
        [Parameter(Mandatory = $true)]$Moment,
        [Parameter(Mandatory = $true)][string]$RootDir
    )

    if ($DryRun) {
        return [pscustomobject][ordered]@{ status = "dry_run"; urlCaptured = $false }
    }

    New-Item -ItemType Directory -Path $LinkDir -Force | Out-Null
    $scriptPath = Join-Path $PSScriptRoot "windows_wechat_copy_link_url.ps1"
    & $scriptPath -X (Get-IntProperty -InputObject $Point -Name "x") -Y (Get-IntProperty -InputObject $Point -Name "y") -ProviderHint "unknown" -OutputDir $LinkDir | Out-Null

    $referencePath = Join-Path $LinkDir "link_reference.json"
    if (-not (Test-Path -LiteralPath $referencePath)) {
        return [pscustomobject][ordered]@{ status = "summary_missing"; urlCaptured = $false }
    }

    $reference = Get-Content -LiteralPath $referencePath -Encoding UTF8 -Raw | ConvertFrom-Json
    $status = Get-StringProperty -InputObject $reference -Name "status" -Default "unknown"
    if ($status -eq "url_copied") {
        $urlFile = Get-StringProperty -InputObject $reference -Name "urlFile"
        Add-ArrayPropertyItem -InputObject $Moment -Name "materials" -Item ([pscustomobject][ordered]@{
            kind = "external_url"
            providerHint = Get-StringProperty -InputObject $reference -Name "providerHint" -Default "unknown"
            title = Get-StringProperty -InputObject $reference -Name "title"
            urlFile = ConvertTo-CapturePath -Path $urlFile -RootDir $RootDir
            urlLength = Get-IntProperty -InputObject $reference -Name "urlLength"
            urlSha256 = Get-StringProperty -InputObject $reference -Name "urlSha256"
            captureMethod = Get-StringProperty -InputObject $reference -Name "captureMethod"
        })
        return [pscustomobject][ordered]@{ status = $status; urlCaptured = $true }
    }

    return [pscustomobject][ordered]@{ status = $status; urlCaptured = $false }
}

function Invoke-WindowClick {
    param(
        [Parameter(Mandatory = $true)]$Bounds,
        [Parameter(Mandatory = $true)]$Point,
        [int]$SettleMilliseconds = 1000
    )

    if ($DryRun) {
        return
    }

    $screenX = [int]($Bounds.left + (Get-IntProperty -InputObject $Point -Name "x"))
    $screenY = [int]($Bounds.top + (Get-IntProperty -InputObject $Point -Name "y"))
    [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point($screenX, $screenY)
    Start-Sleep -Milliseconds 80
    [WechatFriendOneClickWin32]::mouse_event([WechatFriendOneClickWin32]::MOUSEEVENTF_LEFTDOWN, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 80
    [WechatFriendOneClickWin32]::mouse_event([WechatFriendOneClickWin32]::MOUSEEVENTF_LEFTUP, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds $SettleMilliseconds
}

function Import-MediaManifest {
    param(
        [Parameter(Mandatory = $true)][string]$ManifestPath,
        [Parameter(Mandatory = $true)]$Moment,
        [Parameter(Mandatory = $true)][string]$RootDir
    )

    if (-not (Test-Path -LiteralPath $ManifestPath)) {
        return [pscustomobject][ordered]@{ status = "manifest_missing"; savedCount = 0 }
    }

    $manifest = Get-Content -LiteralPath $ManifestPath -Encoding UTF8 -Raw | ConvertFrom-Json
    $savedCount = 0
    foreach ($media in Get-ArrayProperty -InputObject $manifest -Name "media") {
        $mediaPath = Get-StringProperty -InputObject $media -Name "path"
        if ([string]::IsNullOrWhiteSpace($mediaPath) -or -not (Test-Path -LiteralPath $mediaPath)) {
            continue
        }
        $extension = [System.IO.Path]::GetExtension($mediaPath).ToLowerInvariant()
        $kind = if ($extension -in @(".mp4", ".mov", ".m4v")) { "video" } else { "image" }
        Add-ArrayPropertyItem -InputObject $Moment -Name "materials" -Item (Get-FileMaterial -Path $mediaPath -Kind $kind -Role "moment_media" -CaptureMethod "wechat_viewer_ctrl_s" -RootDir $RootDir)
        $savedCount += 1
    }

    return [pscustomobject][ordered]@{
        status = Get-StringProperty -InputObject $manifest -Name "stoppedReason" -Default "captured"
        savedCount = $savedCount
    }
}

function Invoke-MediaCaptureFromPoint {
    param(
        [Parameter(Mandatory = $true)]$Point,
        [Parameter(Mandatory = $true)]$Bounds,
        [Parameter(Mandatory = $true)][string]$MomentDir,
        [Parameter(Mandatory = $true)]$Moment,
        [Parameter(Mandatory = $true)][string]$RootDir
    )

    if ($DryRun) {
        return [pscustomobject][ordered]@{ status = "dry_run"; savedCount = 0 }
    }

    Invoke-WindowClick -Bounds $Bounds -Point $Point -SettleMilliseconds 1200

    $saveScript = Join-Path $PSScriptRoot "windows_wechat_save_viewer_batch.ps1"
    & $saveScript -OutputDir $MomentDir -MaxItems 9 -BaseName "image" -Extension "jpg" | Out-Null
    $imageResult = Import-MediaManifest -ManifestPath (Join-Path $MomentDir "manifest.json") -Moment $Moment -RootDir $RootDir
    if ($imageResult.savedCount -gt 0) {
        Send-Keys "{ESC}"
        Start-Sleep -Milliseconds 700
        return $imageResult
    }

    & $saveScript -OutputDir $MomentDir -MaxItems 1 -BaseName "video" -Extension "mp4" | Out-Null
    $videoResult = Import-MediaManifest -ManifestPath (Join-Path $MomentDir "manifest.json") -Moment $Moment -RootDir $RootDir
    Send-Keys "{ESC}"
    Start-Sleep -Milliseconds 700
    return $videoResult
}

function Set-MomentPostTypeAndStatus {
    param(
        [Parameter(Mandatory = $true)]$Moment,
        [int]$ActionCount,
        [int]$HardFailureCount,
        [bool]$HadMaterialCandidate
    )

    $materials = @(Get-ObjectProperty -InputObject $Moment -Name "materials" -Default @())
    $urlCount = @($materials | Where-Object { $_.kind -eq "external_url" }).Count
    $localCount = @($materials | Where-Object { $_.kind -ne "external_url" }).Count
    $hasText = ($null -ne $Moment.text)
    $hasAny = ($hasText -or $materials.Count -gt 0)

    if ($localCount -gt 0 -and ($hasText -or $urlCount -gt 0)) {
        $Moment.postType = "mixed"
    }
    elseif ($localCount -gt 0) {
        $Moment.postType = if (@($materials | Where-Object { $_.kind -eq "video" }).Count -gt 0) { "video" } else { "image_set" }
    }
    elseif ($urlCount -gt 0) {
        $Moment.postType = "external_url"
    }
    elseif ($hasText) {
        $Moment.postType = "pure_text"
    }
    else {
        $Moment.postType = "unknown"
    }

    if ($hasAny) {
        if ($HardFailureCount -gt 0 -or ($HadMaterialCandidate -and $materials.Count -eq 0)) {
            $Moment.status = "partial"
        }
        else {
            $Moment.status = "captured"
        }
    }
    elseif ($ActionCount -gt 0) {
        $Moment.status = "failed"
    }
    else {
        $Moment.status = "skipped"
    }
}

function Save-DetectedMoment {
    param(
        [Parameter(Mandatory = $true)]$Candidate,
        [Parameter(Mandatory = $true)][int]$Index,
        [Parameter(Mandatory = $true)][string]$SessionId,
        [Parameter(Mandatory = $true)][string]$OutputRoot,
        [Parameter(Mandatory = $true)]$Profile,
        [Parameter(Mandatory = $true)]$Bounds,
        [Parameter(Mandatory = $true)][System.Collections.ArrayList]$Events,
        [Parameter(Mandatory = $true)][string]$IndexPath
    )

    $postId = "moment_{0:D3}" -f $Index
    $safePostId = ConvertTo-SafePathSegment $postId
    $momentDir = Join-Path (Join-Path $OutputRoot "moments") $safePostId
    $momentDirRelative = "moments/$safePostId"
    $momentPath = Join-Path $momentDir "moment.json"
    $linksRoot = Join-Path $momentDir "links"
    if (-not $DryRun) {
        New-Item -ItemType Directory -Path $momentDir -Force | Out-Null
        New-Item -ItemType Directory -Path $linksRoot -Force | Out-Null
    }

    $moment = New-MomentObject -PostId $postId -SessionId $SessionId -MomentDirRelative $momentDirRelative -Profile $Profile
    $actionCount = 0
    $hardFailureCount = 0
    $hadMaterialCandidate = ($null -ne $Candidate.material)
    $isTextDuplicate = [bool](Get-ObjectProperty -InputObject $Candidate -Name "textDuplicate" -Default $false)
    $isMaterialDuplicate = [bool](Get-ObjectProperty -InputObject $Candidate -Name "materialDuplicate" -Default $false)

    Add-CaptureEvent -Events $Events -Type "moment_detected" -PostId $postId -Status $Candidate.detection -Detail ("y={0}; textDuplicate={1}; materialDuplicate={2}" -f $Candidate.y, $isTextDuplicate, $isMaterialDuplicate)

    if ($null -ne $Candidate.textProbe) {
        $actionCount += 1
        try {
            if (-not $DryRun) {
                Set-MomentTextFromProbe -Moment $moment -Probe $Candidate.textProbe -MomentDir $momentDir -RootDir $OutputRoot
            }
            $copiedStatus = if ($DryRun) {
                "dry_run"
            }
            else {
                $candidateTextStatus = Get-StringProperty -InputObject $Candidate -Name "textAttemptStatus" -Default "copied"
                if ($candidateTextStatus -in @("copied", "copied_duplicate")) { $candidateTextStatus } else { "copied" }
            }
            Add-CaptureEvent -Events $Events -Type "text" -PostId $postId -Status $copiedStatus
        }
        catch {
            $hardFailureCount += 1
            Add-CaptureEvent -Events $Events -Type "text" -PostId $postId -Status "failed" -Detail $_.Exception.Message
        }
    }
    elseif ([bool](Get-ObjectProperty -InputObject $Candidate -Name "textAttempted" -Default $false)) {
        $actionCount += 1
        $textAttemptStatus = Get-StringProperty -InputObject $Candidate -Name "textAttemptStatus" -Default "copy_failed"
        if ($textAttemptStatus -notin @("duplicate", "copied_duplicate", "not_moment_text")) {
            $hardFailureCount += 1
        }
        Add-CaptureEvent -Events $Events -Type "text" -PostId $postId -Status $textAttemptStatus
    }

    if ($hadMaterialCandidate) {
        $point = $Candidate.material.point
        $linkDir = Join-Path $linksRoot "link_001"
        $linkResult = $null
        try {
            $linkResult = Invoke-LinkCaptureFromPoint -Point $point -LinkDir $linkDir -Moment $moment -RootDir $OutputRoot
            Add-CaptureEvent -Events $Events -Type "link" -PostId $postId -Status $linkResult.status
        }
        catch {
            Add-CaptureEvent -Events $Events -Type "link" -PostId $postId -Status "failed" -Detail $_.Exception.Message
        }

        if ($null -eq $linkResult -or -not $linkResult.urlCaptured) {
            $actionCount += 1
            try {
                $mediaResult = Invoke-MediaCaptureFromPoint -Point $point -Bounds $Bounds -MomentDir $momentDir -Moment $moment -RootDir $OutputRoot
                Add-CaptureEvent -Events $Events -Type "media" -PostId $postId -Status $mediaResult.status -Detail ("saved={0}" -f $mediaResult.savedCount)
                if ($mediaResult.savedCount -eq 0 -and -not $DryRun) {
                    $hardFailureCount += 1
                }
            }
            catch {
                $hardFailureCount += 1
                Add-CaptureEvent -Events $Events -Type "media" -PostId $postId -Status "failed" -Detail $_.Exception.Message
                if (-not $DryRun) {
                    Send-Keys "{ESC}"
                    Start-Sleep -Milliseconds 700
                }
            }
        }
    }

    Set-MomentPostTypeAndStatus -Moment $moment -ActionCount $actionCount -HardFailureCount $hardFailureCount -HadMaterialCandidate $hadMaterialCandidate

    $indexItem = [pscustomobject][ordered]@{
        postId = $postId
        momentFile = "$momentDirRelative/moment.json"
        status = if ($DryRun) { "dry_run" } else { $moment.status }
        postedAtText = $moment.postedAtText
    }

    if (-not $DryRun) {
        Save-Json -Value $moment -Path $momentPath -Depth 14
        Write-Jsonl -Value $indexItem -Path $IndexPath
        Add-ArrayPropertyItem -InputObject $Profile.moments -Name "items" -Item $indexItem
    }

    Add-CaptureEvent -Events $Events -Type "moment" -PostId $postId -Status $indexItem.status -Detail ("materials={0}; text={1}" -f @(Get-ObjectProperty -InputObject $moment -Name "materials" -Default @()).Count, ($null -ne $moment.text))
    return $indexItem
}

function Update-ProfileCounts {
    param([Parameter(Mandatory = $true)]$Profile)

    $items = @(Get-ObjectProperty -InputObject $Profile.moments -Name "items" -Default @())
    $Profile.moments.count = $items.Count
    $Profile.moments.capturedCount = @($items | Where-Object { $_.status -eq "captured" }).Count
    $Profile.moments.partialCount = @($items | Where-Object { $_.status -eq "partial" }).Count
    $Profile.moments.failedCount = @($items | Where-Object { $_.status -eq "failed" }).Count
    $Profile.moments.skippedCount = @($items | Where-Object { $_.status -eq "skipped" }).Count
}

function Invoke-OneClickCapture {
    param(
        [Parameter(Mandatory = $true)][string]$FriendName,
        [Parameter(Mandatory = $true)][int]$Count,
        [string]$RequestedOutputDir = ""
    )

    $safeName = ConvertTo-SafePathSegment $FriendName
    $repoRoot = Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")
    $sessionId = "{0}_{1}" -f $safeName, (Get-Date -Format "yyyyMMdd_HHmmss")
    $workDir = Join-Path $repoRoot ("windows-probe\oneclick-runs\{0}" -f $sessionId)
    New-Item -ItemType Directory -Path $workDir -Force | Out-Null

    if ([string]::IsNullOrWhiteSpace($RequestedOutputDir)) {
        $captureRoot = Join-Path $repoRoot ("windows-probe\capture-runs\{0}" -f $sessionId)
    }
    else {
        $captureRoot = $RequestedOutputDir
    }

    $capturedAt = (Get-Date).ToString("o")
    $profile = New-ProfileObject -FriendName $FriendName -SessionId $sessionId -CapturedAt $capturedAt
    $indexPath = Join-Path $captureRoot "moments_index.jsonl"
    $profilePath = Join-Path $captureRoot "profile.json"
    $summaryPath = Join-Path $captureRoot "capture_session_summary.json"
    $events = New-Object System.Collections.ArrayList
    $seenTextHashes = @{}
    $seenMaterialHashes = @{}
    $savedCount = 0
    $pageIndex = 0
    $emptyPages = 0

    if (-not $DryRun) {
        New-Item -ItemType Directory -Path $captureRoot -Force | Out-Null
        New-Item -ItemType Directory -Path (Join-Path $captureRoot "moments") -Force | Out-Null
        New-Item -ItemType Directory -Path (Join-Path $captureRoot "profile-assets") -Force | Out-Null
        if (Test-Path -LiteralPath $indexPath) {
            Remove-Item -LiteralPath $indexPath -Force
        }
        New-Item -ItemType File -Path $indexPath -Force | Out-Null
        Save-Json -Value $profile -Path $profilePath -Depth 14
    }

    Add-Log "Make sure WeChat is already on the target friend's Moments page."
    Add-Log ("Output: {0}" -f $captureRoot)

    while ($savedCount -lt $Count -and $pageIndex -lt $MaxPages) {
        $pageIndex += 1
        $focus = Focus-WeixinWindow
        $bounds = $focus.bounds
        $pageId = "page_{0:D3}" -f $pageIndex
        $pageDir = Join-Path $workDir $pageId
        New-Item -ItemType Directory -Path $pageDir -Force | Out-Null
        $screenshotPath = Join-Path $pageDir "window.png"
        Save-WindowScreenshot -Bounds $bounds -Path $screenshotPath

        Add-Log ("Scanning {0}..." -f $pageId)
        $candidates = @(Get-VisibleMomentCandidates -FriendName $FriendName -PageDir $pageDir -ScreenshotPath $screenshotPath -Bounds $bounds -SeenTextHashes $seenTextHashes -SeenMaterialHashes $seenMaterialHashes -TargetRemaining ($Count - $savedCount) -LanguageTag $OcrLanguageTag)
        Save-Json -Value ([pscustomobject][ordered]@{
            pageId = $pageId
            screenshot = $screenshotPath
            candidateCount = $candidates.Count
            candidates = @($candidates | ForEach-Object {
                [pscustomobject][ordered]@{
                    y = $_.y
                    detection = $_.detection
                    hasText = ($null -ne $_.textProbe)
                    hasMaterial = ($null -ne $_.material)
                    textDuplicate = [bool](Get-ObjectProperty -InputObject $_ -Name "textDuplicate" -Default $false)
                    materialDuplicate = [bool](Get-ObjectProperty -InputObject $_ -Name "materialDuplicate" -Default $false)
                }
            })
        }) -Path (Join-Path $pageDir "detected_candidates.json") -Depth 12

        Add-CaptureEvent -Events $events -Type "page" -Status "detected" -Detail ("{0}: candidates={1}" -f $pageId, $candidates.Count)
        if ($candidates.Count -eq 0) {
            $emptyPages += 1
        }
        else {
            $emptyPages = 0
        }

        foreach ($candidate in $candidates) {
            if ($savedCount -ge $Count) {
                break
            }
            $savedCount += 1
            Add-Log ("Saving post {0}/{1}..." -f $savedCount, $Count)
            [void](Save-DetectedMoment -Candidate $candidate -Index $savedCount -SessionId $sessionId -OutputRoot $captureRoot -Profile $profile -Bounds $bounds -Events $events -IndexPath $indexPath)
            if (-not $DryRun) {
                Update-ProfileCounts -Profile $profile
                Save-Json -Value $profile -Path $profilePath -Depth 14
            }
        }

        if ($savedCount -ge $Count -or $pageIndex -ge $MaxPages -or $emptyPages -ge 2) {
            break
        }

        Add-Log "Scrolling for more posts..."
        Invoke-WeixinScroll -Bounds $bounds
        Add-CaptureEvent -Events $events -Type "scroll" -Status $(if ($DryRun) { "dry_run" } else { "sent" }) -Detail $pageId
    }

    Update-ProfileCounts -Profile $profile
    $summary = [pscustomobject][ordered]@{
        createdAt = (Get-Date).ToString("o")
        dryRun = [bool]$DryRun
        outputDir = $captureRoot
        oneClickWorkDir = $workDir
        captureSessionId = $sessionId
        requestedCount = $Count
        processedCount = $savedCount
        pageCount = $pageIndex
        profileFile = $profilePath
        indexFile = $indexPath
        events = @($events)
    }

    if (-not $DryRun) {
        Save-Json -Value $profile -Path $profilePath -Depth 14
        Save-Json -Value $summary -Path $summaryPath -Depth 14
    }
    else {
        Save-Json -Value $summary -Path (Join-Path $workDir "dry_run_summary.json") -Depth 14
    }

    Add-Log ("Done. Processed {0}/{1} posts." -f $savedCount, $Count)
    return $summary
}

Add-OneClickAssemblies

if ($TargetMoments -lt 1) {
    throw "TargetMoments must be >= 1."
}
if ($MaxPages -lt 1) {
    throw "MaxPages must be >= 1."
}

if ($ValidateOnly) {
    $process = $null
    $bounds = $null
    $weixinRunning = $false
    $ocrAvailable = $false
    $ocrLanguages = @()
    $ocrError = ""
    try {
        $process = Get-WeixinProcess
        $bounds = Get-WindowBounds $process.MainWindowHandle
        $weixinRunning = $true
    }
    catch {
        $weixinRunning = $false
    }
    try {
        $ocrScript = Join-Path $PSScriptRoot "windows_wechat_ocr_locator.ps1"
        $ocrValidation = (& $ocrScript -ValidateOnly | ConvertFrom-Json)
        $ocrAvailable = [bool]$ocrValidation.ok
        $ocrLanguages = @($ocrValidation.availableLanguages)
    }
    catch {
        $ocrAvailable = $false
        $ocrError = $_.Exception.Message
    }
    [pscustomobject][ordered]@{
        ok = $true
        canLoadWinForms = $true
        weixinRunning = $weixinRunning
        windowBounds = $bounds
        ocrAvailable = $ocrAvailable
        ocrLanguages = $ocrLanguages
        ocrError = $ocrError
    } | ConvertTo-Json -Depth 8
    return
}

if ($RunNow) {
    $summary = Invoke-OneClickCapture -FriendName $DisplayName -Count $TargetMoments -RequestedOutputDir $OutputDir
    $summary | ConvertTo-Json -Depth 14
    return
}

$form = New-Object System.Windows.Forms.Form
$form.Text = "WeChat Moments Saver"
$form.Width = 520
$form.Height = 390
$form.MinimumSize = New-Object System.Drawing.Size(500, 360)
$form.StartPosition = "CenterScreen"
$form.ShowInTaskbar = $true
$form.TopMost = $true

$mainPanel = New-Object System.Windows.Forms.Panel
$mainPanel.Dock = "Fill"
$mainPanel.Padding = New-Object System.Windows.Forms.Padding(16)
$form.Controls.Add($mainPanel)

$titleLabel = New-Object System.Windows.Forms.Label
$titleLabel.Left = 16
$titleLabel.Top = 16
$titleLabel.Width = 460
$titleLabel.Height = 52
$titleLabel.Text = "Open the target friend's Moments page in WeChat, then click Start. The app will keep going and record partial or failed posts."
$mainPanel.Controls.Add($titleLabel)

$friendLabel = New-Object System.Windows.Forms.Label
$friendLabel.Left = 16
$friendLabel.Top = 84
$friendLabel.Width = 130
$friendLabel.Height = 22
$friendLabel.Text = "Friend name"
$mainPanel.Controls.Add($friendLabel)

$friendTextBox = New-Object System.Windows.Forms.TextBox
$friendTextBox.Left = 150
$friendTextBox.Top = 80
$friendTextBox.Width = 300
$friendTextBox.Text = $DisplayName
$mainPanel.Controls.Add($friendTextBox)

$countLabel = New-Object System.Windows.Forms.Label
$countLabel.Left = 16
$countLabel.Top = 122
$countLabel.Width = 130
$countLabel.Height = 22
$countLabel.Text = "Recent posts"
$mainPanel.Controls.Add($countLabel)

$countInput = New-Object System.Windows.Forms.NumericUpDown
$countInput.Left = 150
$countInput.Top = 118
$countInput.Width = 90
$countInput.Minimum = 1
$countInput.Maximum = 50
$countInput.Value = $TargetMoments
$mainPanel.Controls.Add($countInput)

$startButton = New-Object System.Windows.Forms.Button
$startButton.Left = 16
$startButton.Top = 164
$startButton.Width = 434
$startButton.Height = 46
$startButton.Text = "Start saving"
$mainPanel.Controls.Add($startButton)

$logListBox = New-Object System.Windows.Forms.ListBox
$logListBox.Left = 16
$logListBox.Top = 226
$logListBox.Width = 434
$logListBox.Height = 96
$mainPanel.Controls.Add($logListBox)
$script:LogListBox = $logListBox

$startButton.Add_Click({
    try {
        $startButton.Enabled = $false
        $summary = Invoke-OneClickCapture -FriendName $friendTextBox.Text -Count ([int]$countInput.Value) -RequestedOutputDir $OutputDir
        [void][System.Windows.Forms.MessageBox]::Show(("Finished. Output:`n{0}" -f $summary.outputDir), "Done", "OK", "Information")
    }
    catch {
        Add-Log ("Failed: {0}" -f $_.Exception.Message)
        [void][System.Windows.Forms.MessageBox]::Show($_.Exception.Message, "Failed", "OK", "Error")
    }
    finally {
        $startButton.Enabled = $true
    }
})

$form.Add_Shown({
    $form.Activate()
    $form.BringToFront()
})

[void]$form.ShowDialog()
