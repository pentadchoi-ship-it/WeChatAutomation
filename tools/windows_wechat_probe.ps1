[CmdletBinding()]
param(
    [string]$WeixinPath = "",
    [string]$OutputDir = "",
    [int]$MaxDepth = 8,
    [int]$MaxNodes = 5000,
    [int]$SettleSeconds = 2,
    [switch]$Launch,
    [switch]$CaptureScreenshot,
    [switch]$IncludeText,
    [switch]$ValidateOnly
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

function New-Utf16String {
    param([Parameter(Mandatory = $true)][int[]]$CodePoints)

    return -join ($CodePoints | ForEach-Object { [char]$_ })
}

$WeixinDisplayName = New-Utf16String @(0x5fae, 0x4fe1)
$KnownKeywords = @(
    $WeixinDisplayName,
    (New-Utf16String @(0x670b, 0x53cb, 0x5708)),
    (New-Utf16String @(0x53d1, 0x73b0)),
    (New-Utf16String @(0x901a, 0x8baf, 0x5f55)),
    (New-Utf16String @(0x6211)),
    (New-Utf16String @(0x53d1, 0x8868)),
    (New-Utf16String @(0x53d1, 0x5e03)),
    (New-Utf16String @(0x5b8c, 0x6210)),
    (New-Utf16String @(0x53d6, 0x6d88)),
    (New-Utf16String @(0x8fd4, 0x56de)),
    (New-Utf16String @(0x56fe, 0x7247)),
    (New-Utf16String @(0x89c6, 0x9891)),
    (New-Utf16String @(0x4fdd, 0x5b58)),
    (New-Utf16String @(0x76f8, 0x518c)),
    (New-Utf16String @(0x9009, 0x62e9)),
    (New-Utf16String @(0x62cd, 0x6444)),
    (New-Utf16String @(0x4ece, 0x624b, 0x673a, 0x76f8, 0x518c, 0x9009, 0x62e9)),
    (New-Utf16String @(0x8c01, 0x53ef, 0x4ee5, 0x770b)),
    (New-Utf16String @(0x63d0, 0x9192, 0x8c01, 0x770b)),
    (New-Utf16String @(0x6240, 0x5728, 0x4f4d, 0x7f6e))
)

function Add-ProbeAssemblies {
    Add-Type -AssemblyName UIAutomationClient
    Add-Type -AssemblyName UIAutomationTypes
    Add-Type -AssemblyName System.Drawing
    Add-Type -AssemblyName System.Windows.Forms

    if (-not ([System.Management.Automation.PSTypeName]"WechatProbeWin32").Type) {
        Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

public static class WechatProbeWin32 {
    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    public static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow);
}
"@
    }
}

function Get-StringHashPrefix {
    param([AllowNull()][string]$Text)

    if ([string]::IsNullOrEmpty($Text)) {
        return ""
    }

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
        $hash = $sha.ComputeHash($bytes)
        return (($hash | ForEach-Object { $_.ToString("x2") }) -join "").Substring(0, 12)
    }
    finally {
        $sha.Dispose()
    }
}

function Convert-ProbeText {
    param([AllowNull()][string]$Text)

    if ([string]::IsNullOrWhiteSpace($Text)) {
        return [ordered]@{
            text = ""
            raw = ""
            length = 0
            hash = ""
            matchedKeywords = @()
        }
    }

    $matches = @()
    foreach ($keyword in $KnownKeywords) {
        if ($Text.Contains($keyword)) {
            $matches += $keyword
        }
    }

    $hash = Get-StringHashPrefix $Text
    if ($IncludeText) {
        return [ordered]@{
            text = $Text
            raw = $Text
            length = $Text.Length
            hash = $hash
            matchedKeywords = $matches
        }
    }

    $safeText = ""
    if ($matches.Count -gt 0) {
        $safeText = "[keywords:{0}; len:{1}; sha256:{2}]" -f (($matches | Select-Object -Unique) -join ","), $Text.Length, $hash
    }
    else {
        $safeText = "[redacted len:{0}; sha256:{1}]" -f $Text.Length, $hash
    }

    return [ordered]@{
        text = $safeText
        raw = ""
        length = $Text.Length
        hash = $hash
        matchedKeywords = ($matches | Select-Object -Unique)
    }
}

function Find-WeixinPath {
    param([string]$ExplicitPath)

    $candidates = New-Object System.Collections.Generic.List[string]
    if (-not [string]::IsNullOrWhiteSpace($ExplicitPath)) {
        $candidates.Add($ExplicitPath)
    }

    $uninstallRoots = @(
        "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*"
    )

    foreach ($root in $uninstallRoots) {
        $items = Get-ItemProperty $root -ErrorAction SilentlyContinue |
            Where-Object {
                $displayNameProperty = $_.PSObject.Properties["DisplayName"]
                $null -ne $displayNameProperty -and
                    [string]$displayNameProperty.Value -match "$WeixinDisplayName|WeChat"
            }
        foreach ($item in $items) {
            if ($item.InstallLocation) {
                $installLocation = [string]$item.InstallLocation
                $installLocation = $installLocation.Trim('"')
                $candidates.Add((Join-Path $installLocation "Weixin.exe"))
                $candidates.Add((Join-Path $installLocation "WeChat.exe"))
            }
        }
    }

    $candidates.Add("C:\Program Files\Tencent\Weixin\Weixin.exe")
    $candidates.Add("C:\Program Files (x86)\Tencent\Weixin\Weixin.exe")

    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath $candidate)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    return ""
}

function Get-WeixinProcess {
    $processes = @(Get-Process -ErrorAction SilentlyContinue |
        Where-Object { $_.ProcessName -in @("Weixin", "WeChat") })

    if ($processes.Count -eq 0) {
        return $null
    }

    $windowedProcesses = @($processes | Where-Object { $_.MainWindowHandle -ne 0 })
    if ($windowedProcesses.Count -gt 0) {
        return ($windowedProcesses | Sort-Object StartTime -Descending | Select-Object -First 1)
    }

    return ($processes | Sort-Object StartTime -Descending | Select-Object -First 1)
}

function Wait-WeixinProcess {
    param([int]$TimeoutSeconds)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $process = Get-WeixinProcess
        if ($null -ne $process -and $process.MainWindowHandle -ne 0) {
            return $process
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    return (Get-WeixinProcess)
}

function Get-AutomationRoot {
    param([System.Diagnostics.Process]$Process)

    if ($Process.MainWindowHandle -ne 0) {
        try {
            return [System.Windows.Automation.AutomationElement]::FromHandle($Process.MainWindowHandle)
        }
        catch {
            Write-Warning "AutomationElement.FromHandle failed: $($_.Exception.Message)"
        }
    }

    $root = [System.Windows.Automation.AutomationElement]::RootElement
    $children = $root.FindAll(
        [System.Windows.Automation.TreeScope]::Children,
        [System.Windows.Automation.Condition]::TrueCondition
    )

    for ($i = 0; $i -lt $children.Count; $i++) {
        $child = $children.Item($i)
        try {
            if ($child.Current.ProcessId -eq $Process.Id) {
                return $child
            }
        }
        catch {
            continue
        }
    }

    return $null
}

function Convert-BoundingRectangle {
    param($Rect)

    if ($null -eq $Rect -or $Rect.IsEmpty) {
        return [ordered]@{
            left = 0
            top = 0
            right = 0
            bottom = 0
            width = 0
            height = 0
        }
    }

    return [ordered]@{
        left = [double]$Rect.Left
        top = [double]$Rect.Top
        right = [double]$Rect.Right
        bottom = [double]$Rect.Bottom
        width = [double]$Rect.Width
        height = [double]$Rect.Height
    }
}

function Get-SupportedPatternNames {
    param([System.Windows.Automation.AutomationElement]$Element)

    try {
        $names = @($Element.GetSupportedPatterns() | ForEach-Object {
            $_.ProgrammaticName -replace "PatternIdentifiers\.Pattern$", "Pattern"
        })
        return $names
    }
    catch {
        return @()
    }
}

function Export-AutomationElement {
    param(
        [System.Windows.Automation.AutomationElement]$Element,
        [int]$Depth,
        [string]$Path
    )

    if ($script:ProbeNodeCount -ge $MaxNodes) {
        $script:ProbeWasTruncated = $true
        return $null
    }

    $script:ProbeNodeCount += 1
    $current = $Element.Current
    $name = Convert-ProbeText $current.Name
    $automationId = Convert-ProbeText $current.AutomationId
    $className = Convert-ProbeText $current.ClassName
    $helpText = Convert-ProbeText $current.HelpText
    $patterns = @(Get-SupportedPatternNames $Element)
    $bounds = Convert-BoundingRectangle $current.BoundingRectangle

    $node = [pscustomobject][ordered]@{
        id = $script:ProbeNodeCount
        path = $Path
        depth = $Depth
        name = $name.text
        nameHash = $name.hash
        automationId = $automationId.text
        className = $className.text
        helpText = $helpText.text
        keywordHits = @($name.matchedKeywords + $automationId.matchedKeywords + $className.matchedKeywords + $helpText.matchedKeywords | Select-Object -Unique)
        controlType = ($current.ControlType.ProgrammaticName -replace "^ControlType\.", "")
        localizedControlType = $current.LocalizedControlType
        frameworkId = $current.FrameworkId
        processId = $current.ProcessId
        isEnabled = $current.IsEnabled
        isOffscreen = $current.IsOffscreen
        hasKeyboardFocus = $current.HasKeyboardFocus
        isKeyboardFocusable = $current.IsKeyboardFocusable
        boundingRect = $bounds
        supportedPatterns = $patterns
        children = @()
    }

    [void]$script:ProbeFlatNodes.Add($node)

    if ($Depth -lt $MaxDepth) {
        try {
            $children = $Element.FindAll(
                [System.Windows.Automation.TreeScope]::Children,
                [System.Windows.Automation.Condition]::TrueCondition
            )
        }
        catch {
            $children = @()
        }

        for ($i = 0; $i -lt $children.Count; $i++) {
            if ($script:ProbeNodeCount -ge $MaxNodes) {
                $script:ProbeWasTruncated = $true
                break
            }

            $childPath = if ([string]::IsNullOrWhiteSpace($Path)) { "$i" } else { "$Path/$i" }
            $childNode = Export-AutomationElement -Element $children.Item($i) -Depth ($Depth + 1) -Path $childPath
            if ($null -ne $childNode) {
                $node.children += $childNode
            }
        }
    }

    return $node
}

function Save-Json {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$Path,
        [int]$Depth = 20
    )

    $json = $Value | ConvertTo-Json -Depth $Depth
    Set-Content -LiteralPath $Path -Value $json -Encoding UTF8
}

function Save-JsonLines {
    param(
        [Parameter(Mandatory = $true)]$Values,
        [Parameter(Mandatory = $true)][string]$Path
    )

    $lines = foreach ($value in $Values) {
        $value | ConvertTo-Json -Depth 10 -Compress
    }
    Set-Content -LiteralPath $Path -Value $lines -Encoding UTF8
}

function Save-WindowScreenshot {
    param(
        [Parameter(Mandatory = $true)]$Bounds,
        [Parameter(Mandatory = $true)][string]$Path
    )

    $width = [int][Math]::Ceiling($Bounds.width)
    $height = [int][Math]::Ceiling($Bounds.height)
    if ($width -le 0 -or $height -le 0) {
        throw "Window bounds are empty; cannot capture screenshot."
    }

    $x = [int][Math]::Floor($Bounds.left)
    $y = [int][Math]::Floor($Bounds.top)
    $bitmap = New-Object System.Drawing.Bitmap($width, $height)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.CopyFromScreen($x, $y, 0, 0, (New-Object System.Drawing.Size($width, $height)))
        $bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    }
    finally {
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

function Save-OverlayScreenshot {
    param(
        [Parameter(Mandatory = $true)][string]$ScreenshotPath,
        [Parameter(Mandatory = $true)]$Nodes,
        [Parameter(Mandatory = $true)]$WindowBounds,
        [Parameter(Mandatory = $true)][string]$OutputPath
    )

    $bitmap = [System.Drawing.Bitmap]::FromFile($ScreenshotPath)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $font = New-Object System.Drawing.Font("Consolas", 8)
    $keywordPen = New-Object System.Drawing.Pen([System.Drawing.Color]::Orange, 2)
    $actionPen = New-Object System.Drawing.Pen([System.Drawing.Color]::DeepSkyBlue, 1)
    $labelBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::Black)
    $labelBackBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(210, 255, 255, 255))

    try {
        $interesting = @($Nodes | Where-Object {
            $rect = $_.boundingRect
            $hasBounds = $rect.width -gt 3 -and $rect.height -gt 3
            $hasKeyword = $_.keywordHits.Count -gt 0
            $hasAction = $_.supportedPatterns -contains "InvokePattern" -or
                $_.supportedPatterns -contains "ValuePattern" -or
                $_.supportedPatterns -contains "SelectionItemPattern" -or
                $_.supportedPatterns -contains "ScrollItemPattern"
            $hasBounds -and -not $_.isOffscreen -and ($hasKeyword -or $hasAction)
        } | Select-Object -First 250)

        foreach ($node in $interesting) {
            $rect = $node.boundingRect
            $x = [int][Math]::Round($rect.left - $WindowBounds.left)
            $y = [int][Math]::Round($rect.top - $WindowBounds.top)
            $w = [int][Math]::Round($rect.width)
            $h = [int][Math]::Round($rect.height)
            if ($w -le 0 -or $h -le 0) {
                continue
            }

            $pen = if ($node.keywordHits.Count -gt 0) { $keywordPen } else { $actionPen }
            $graphics.DrawRectangle($pen, $x, $y, $w, $h)

            if ($node.keywordHits.Count -gt 0) {
                $label = "#{0} {1}" -f $node.id, (($node.keywordHits | Select-Object -First 2) -join ",")
                $labelSize = $graphics.MeasureString($label, $font)
                $labelX = [Math]::Max(0, $x)
                $labelY = [Math]::Max(0, $y - [int]$labelSize.Height)
                $graphics.FillRectangle(
                    $labelBackBrush,
                    $labelX,
                    $labelY,
                    [int]$labelSize.Width + 4,
                    [int]$labelSize.Height + 2
                )
                $graphics.DrawString($label, $font, $labelBrush, $labelX + 2, $labelY + 1)
            }
        }

        $bitmap.Save($OutputPath, [System.Drawing.Imaging.ImageFormat]::Png)
    }
    finally {
        $keywordPen.Dispose()
        $actionPen.Dispose()
        $labelBrush.Dispose()
        $labelBackBrush.Dispose()
        $font.Dispose()
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

Add-ProbeAssemblies
$resolvedWeixinPath = Find-WeixinPath $WeixinPath

if ($ValidateOnly) {
    $validation = [ordered]@{
        ok = (-not [string]::IsNullOrWhiteSpace($resolvedWeixinPath))
        weixinPath = $resolvedWeixinPath
        canLoadUia = $true
        canLoadDrawing = $true
        defaultMaxDepth = $MaxDepth
        defaultMaxNodes = $MaxNodes
        captureScreenshotByDefault = $false
        includeRawTextByDefault = $false
    }
    $validation | ConvertTo-Json -Depth 4
    exit 0
}

if ([string]::IsNullOrWhiteSpace($resolvedWeixinPath)) {
    throw "Weixin.exe was not found. Pass -WeixinPath with the full executable path."
}

$process = Get-WeixinProcess
if ($null -eq $process) {
    if ($Launch) {
        Write-Host "Launching Weixin: $resolvedWeixinPath"
        Start-Process -FilePath $resolvedWeixinPath | Out-Null
        Start-Sleep -Seconds $SettleSeconds
        $process = Wait-WeixinProcess -TimeoutSeconds 20
    }
    else {
        throw "Weixin is not running. Start Weixin first, or pass -Launch."
    }
}

if ($null -eq $process) {
    throw "Weixin process was not found after launch."
}

if ($process.MainWindowHandle -ne 0) {
    [void][WechatProbeWin32]::ShowWindowAsync($process.MainWindowHandle, 9)
    [void][WechatProbeWin32]::SetForegroundWindow($process.MainWindowHandle)
    Start-Sleep -Seconds $SettleSeconds
}

$rootElement = Get-AutomationRoot -Process $process
if ($null -eq $rootElement) {
    throw "Could not locate a UI Automation root for process id $($process.Id)."
}

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $repoRoot = Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")
    $OutputDir = Join-Path $repoRoot ("windows-probe\runs\" + (Get-Date -Format "yyyyMMdd_HHmmss"))
}

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

$script:ProbeFlatNodes = New-Object System.Collections.ArrayList
$script:ProbeNodeCount = 0
$script:ProbeWasTruncated = $false

$tree = Export-AutomationElement -Element $rootElement -Depth 0 -Path "0"
$nodes = @($script:ProbeFlatNodes)

$keywordNodes = @($nodes | Where-Object { $_.keywordHits.Count -gt 0 } | Select-Object `
    id, path, depth, name, controlType, localizedControlType, keywordHits, boundingRect, supportedPatterns)

$actionableNodes = @($nodes | Where-Object {
    $_.supportedPatterns -contains "InvokePattern" -or
    $_.supportedPatterns -contains "ValuePattern" -or
    $_.supportedPatterns -contains "SelectionItemPattern" -or
    $_.supportedPatterns -contains "ScrollItemPattern"
} | Select-Object -First 300 `
    id, path, depth, name, controlType, localizedControlType, keywordHits, boundingRect, supportedPatterns)

$windowBounds = Convert-BoundingRectangle $rootElement.Current.BoundingRectangle
$summary = [ordered]@{
    createdAt = (Get-Date).ToString("o")
    weixinPath = $resolvedWeixinPath
    fileVersion = (Get-Item -LiteralPath $resolvedWeixinPath).VersionInfo.FileVersion
    processId = $process.Id
    processName = $process.ProcessName
    includeRawText = [bool]$IncludeText
    captureScreenshot = [bool]$CaptureScreenshot
    maxDepth = $MaxDepth
    maxNodes = $MaxNodes
    nodeCount = $nodes.Count
    truncated = $script:ProbeWasTruncated
    windowName = (Convert-ProbeText $rootElement.Current.Name).text
    windowBounds = $windowBounds
    keywordNodeCount = $keywordNodes.Count
    actionableNodeCount = $actionableNodes.Count
    files = [ordered]@{}
}

$treePath = Join-Path $OutputDir "uia_tree.json"
$nodesPath = Join-Path $OutputDir "uia_nodes.jsonl"
$keywordsPath = Join-Path $OutputDir "keyword_nodes.json"
$actionablePath = Join-Path $OutputDir "actionable_nodes.json"
$summaryPath = Join-Path $OutputDir "summary.json"

Save-Json -Value $tree -Path $treePath -Depth 60
Save-JsonLines -Values $nodes -Path $nodesPath
Save-Json -Value $keywordNodes -Path $keywordsPath -Depth 12
Save-Json -Value $actionableNodes -Path $actionablePath -Depth 12

$summary.files.uiaTree = $treePath
$summary.files.uiaNodesJsonl = $nodesPath
$summary.files.keywordNodes = $keywordsPath
$summary.files.actionableNodes = $actionablePath

if ($CaptureScreenshot) {
    $screenshotPath = Join-Path $OutputDir "window.png"
    $overlayPath = Join-Path $OutputDir "window_overlay.png"
    Save-WindowScreenshot -Bounds $windowBounds -Path $screenshotPath
    Save-OverlayScreenshot -ScreenshotPath $screenshotPath -Nodes $nodes -WindowBounds $windowBounds -OutputPath $overlayPath
    $summary.files.screenshot = $screenshotPath
    $summary.files.overlayScreenshot = $overlayPath
}

Save-Json -Value $summary -Path $summaryPath -Depth 12
Write-Host "WeChat Windows probe complete."
Write-Host "Summary: $summaryPath"
Write-Host "UIA tree: $treePath"
Write-Host "Keyword nodes: $keywordsPath"
if ($CaptureScreenshot) {
    Write-Host "Screenshot overlay: $overlayPath"
}
