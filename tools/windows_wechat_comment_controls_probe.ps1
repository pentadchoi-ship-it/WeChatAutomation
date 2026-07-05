[CmdletBinding()]
param(
    [string]$OutputDir = "",
    [int]$MaxDepth = 8,
    [int]$MaxNodes = 5000,
    [switch]$IncludeRawText,
    [switch]$SkipClipboard
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

function New-Utf16String {
    param([Parameter(Mandatory = $true)][int[]]$CodePoints)

    return -join ($CodePoints | ForEach-Object { [char]$_ })
}

$CommentKeywords = @(
    (New-Utf16String @(0x670b, 0x53cb, 0x5708)),
    (New-Utf16String @(0x8bc4, 0x8bba)),
    (New-Utf16String @(0x56de, 0x590d)),
    (New-Utf16String @(0x70b9, 0x8d5e)),
    (New-Utf16String @(0x8d5e)),
    (New-Utf16String @(0x5220, 0x9664))
)

function Add-ProbeAssemblies {
    Add-Type -AssemblyName UIAutomationClient
    Add-Type -AssemblyName UIAutomationTypes
    Add-Type -AssemblyName Accessibility
    Add-Type -AssemblyName System.Windows.Forms

    if (-not ([System.Management.Automation.PSTypeName]"WechatCommentProbeWin32").Type) {
        Add-Type -ReferencedAssemblies "Accessibility" -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using Accessibility;

public static class WechatCommentProbeWin32 {
    public const uint OBJID_CLIENT = 0xFFFFFFFC;

    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    public static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow);

    [DllImport("oleacc.dll")]
    public static extern int AccessibleObjectFromWindow(
        IntPtr hwnd,
        uint dwObjectID,
        ref Guid riid,
        [MarshalAs(UnmanagedType.Interface)] out IAccessible ppvObject
    );

    [DllImport("oleacc.dll")]
    public static extern int AccessibleChildren(
        IAccessible paccContainer,
        int iChildStart,
        int cChildren,
        [Out, MarshalAs(UnmanagedType.LPArray, SizeParamIndex = 2)] object[] rgvarChildren,
        out int pcObtained
    );

    [DllImport("oleacc.dll", EntryPoint = "AccessibleChildren")]
    public static extern int AccessibleChildrenRaw(
        [MarshalAs(UnmanagedType.Interface)] object paccContainer,
        int iChildStart,
        int cChildren,
        [Out, MarshalAs(UnmanagedType.LPArray, SizeParamIndex = 2)] object[] rgvarChildren,
        out int pcObtained
    );
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
        return [pscustomobject][ordered]@{
            text = ""
            raw = ""
            length = 0
            hash = ""
            keywordHits = @()
        }
    }

    $hits = @()
    foreach ($keyword in $CommentKeywords) {
        if ($Text.Contains($keyword)) {
            $hits += $keyword
        }
    }

    $hash = Get-StringHashPrefix $Text
    $safe = if ($IncludeRawText) {
        $Text
    }
    elseif ($hits.Count -gt 0) {
        "[keywords:{0}; len:{1}; sha256:{2}]" -f (($hits | Select-Object -Unique) -join ","), $Text.Length, $hash
    }
    else {
        "[redacted len:{0}; sha256:{1}]" -f $Text.Length, $hash
    }

    return [pscustomobject][ordered]@{
        text = $safe
        raw = if ($IncludeRawText) { $Text } else { "" }
        length = $Text.Length
        hash = $hash
        keywordHits = @($hits | Select-Object -Unique)
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

function Get-BoundsObject {
    param($Rect)

    if ($null -eq $Rect -or $Rect.IsEmpty) {
        return [pscustomobject][ordered]@{
            left = 0; top = 0; right = 0; bottom = 0; width = 0; height = 0
        }
    }

    return [pscustomobject][ordered]@{
        left = [double]$Rect.Left
        top = [double]$Rect.Top
        right = [double]$Rect.Right
        bottom = [double]$Rect.Bottom
        width = [double]$Rect.Width
        height = [double]$Rect.Height
    }
}

function Get-UiaPatternNames {
    param([System.Windows.Automation.AutomationElement]$Element)

    try {
        return @($Element.GetSupportedPatterns() | ForEach-Object {
            $_.ProgrammaticName -replace "PatternIdentifiers\.Pattern$", "Pattern"
        })
    }
    catch {
        return @()
    }
}

function Export-UiaElement {
    param(
        [System.Windows.Automation.AutomationElement]$Element,
        [int]$Depth,
        [string]$Path
    )

    if ($script:UiaNodeCount -ge $MaxNodes) {
        $script:UiaTruncated = $true
        return
    }

    $script:UiaNodeCount += 1
    $current = $Element.Current
    $name = Convert-ProbeText $current.Name
    $help = Convert-ProbeText $current.HelpText
    $className = Convert-ProbeText $current.ClassName

    $keywordHits = @($name.keywordHits + $help.keywordHits + $className.keywordHits | Select-Object -Unique)
    if ($name.length -gt 0 -or $help.length -gt 0) {
        $script:UiaTextBearingCount += 1
    }
    if ($keywordHits.Count -gt 0) {
        $script:UiaKeywordCount += 1
    }

    $node = [pscustomobject][ordered]@{
        id = $script:UiaNodeCount
        path = $Path
        depth = $Depth
        name = $name.text
        helpText = $help.text
        className = $className.text
        nameLength = $name.length
        helpTextLength = $help.length
        nameHash = $name.hash
        helpTextHash = $help.hash
        keywordHits = $keywordHits
        controlType = ($current.ControlType.ProgrammaticName -replace "^ControlType\.", "")
        localizedControlType = $current.LocalizedControlType
        frameworkId = $current.FrameworkId
        isOffscreen = $current.IsOffscreen
        boundingRect = Get-BoundsObject $current.BoundingRectangle
        supportedPatterns = @(Get-UiaPatternNames $Element)
    }
    [void]$script:UiaNodes.Add($node)

    if ($Depth -ge $MaxDepth) {
        return
    }

    try {
        $children = $Element.FindAll(
            [System.Windows.Automation.TreeScope]::Children,
            [System.Windows.Automation.Condition]::TrueCondition
        )
    }
    catch {
        return
    }

    for ($i = 0; $i -lt $children.Count; $i++) {
        if ($script:UiaNodeCount -ge $MaxNodes) {
            $script:UiaTruncated = $true
            break
        }
        Export-UiaElement -Element $children.Item($i) -Depth ($Depth + 1) -Path "$Path/$i"
    }
}

function Get-AccProperty {
    param(
        [Parameter(Mandatory = $true)]$Accessible,
        [Parameter(Mandatory = $true)][string]$Property,
        [Parameter(Mandatory = $true)]$ChildId
    )

    try {
        switch ($Property) {
            "Name" { return [string]$Accessible.get_accName($ChildId) }
            "Value" { return [string]$Accessible.get_accValue($ChildId) }
            "Description" { return [string]$Accessible.get_accDescription($ChildId) }
            "Role" { return [string]$Accessible.get_accRole($ChildId) }
            "State" { return [string]$Accessible.get_accState($ChildId) }
        }
    }
    catch {
        return ""
    }
}

function Export-MsaaAccessible {
    param(
        [Parameter(Mandatory = $true)]$Accessible,
        [int]$Depth,
        [string]$Path
    )

    if ($script:MsaaNodeCount -ge $MaxNodes) {
        $script:MsaaTruncated = $true
        return
    }

    $script:MsaaNodeCount += 1
    $name = Convert-ProbeText (Get-AccProperty -Accessible $Accessible -Property "Name" -ChildId 0)
    $value = Convert-ProbeText (Get-AccProperty -Accessible $Accessible -Property "Value" -ChildId 0)
    $description = Convert-ProbeText (Get-AccProperty -Accessible $Accessible -Property "Description" -ChildId 0)
    $role = Get-AccProperty -Accessible $Accessible -Property "Role" -ChildId 0
    $state = Get-AccProperty -Accessible $Accessible -Property "State" -ChildId 0

    $keywordHits = @($name.keywordHits + $value.keywordHits + $description.keywordHits | Select-Object -Unique)
    if ($name.length -gt 0 -or $value.length -gt 0 -or $description.length -gt 0) {
        $script:MsaaTextBearingCount += 1
    }
    if ($keywordHits.Count -gt 0) {
        $script:MsaaKeywordCount += 1
    }

    $node = [pscustomobject][ordered]@{
        id = $script:MsaaNodeCount
        path = $Path
        depth = $Depth
        childId = 0
        name = $name.text
        value = $value.text
        description = $description.text
        nameLength = $name.length
        valueLength = $value.length
        descriptionLength = $description.length
        nameHash = $name.hash
        valueHash = $value.hash
        descriptionHash = $description.hash
        keywordHits = $keywordHits
        role = $role
        state = $state
    }
    [void]$script:MsaaNodes.Add($node)

    if ($Depth -ge $MaxDepth) {
        return
    }

    $childCount = 0
    try {
        $childCount = [int]$Accessible.accChildCount
    }
    catch {
        return
    }
    if ($childCount -le 0) {
        return
    }

    $children = New-Object object[] $childCount
    $obtained = 0
    $hr = [WechatCommentProbeWin32]::AccessibleChildrenRaw($Accessible, 0, $childCount, $children, [ref]$obtained)
    if ($hr -ne 0 -or $obtained -le 0) {
        return
    }

    for ($i = 0; $i -lt $obtained; $i++) {
        if ($script:MsaaNodeCount -ge $MaxNodes) {
            $script:MsaaTruncated = $true
            break
        }

        $child = $children[$i]
        if ($null -eq $child) {
            continue
        }

        if ($child -is [int]) {
            $childId = [int]$child
            $script:MsaaNodeCount += 1
            $childName = Convert-ProbeText (Get-AccProperty -Accessible $Accessible -Property "Name" -ChildId $childId)
            $childValue = Convert-ProbeText (Get-AccProperty -Accessible $Accessible -Property "Value" -ChildId $childId)
            $childDescription = Convert-ProbeText (Get-AccProperty -Accessible $Accessible -Property "Description" -ChildId $childId)
            $childHits = @($childName.keywordHits + $childValue.keywordHits + $childDescription.keywordHits | Select-Object -Unique)
            if ($childName.length -gt 0 -or $childValue.length -gt 0 -or $childDescription.length -gt 0) {
                $script:MsaaTextBearingCount += 1
            }
            if ($childHits.Count -gt 0) {
                $script:MsaaKeywordCount += 1
            }

            [void]$script:MsaaNodes.Add([pscustomobject][ordered]@{
                id = $script:MsaaNodeCount
                path = "$Path/$i"
                depth = $Depth + 1
                childId = $childId
                name = $childName.text
                value = $childValue.text
                description = $childDescription.text
                nameLength = $childName.length
                valueLength = $childValue.length
                descriptionLength = $childDescription.length
                nameHash = $childName.hash
                valueHash = $childValue.hash
                descriptionHash = $childDescription.hash
                keywordHits = $childHits
                role = Get-AccProperty -Accessible $Accessible -Property "Role" -ChildId $childId
                state = Get-AccProperty -Accessible $Accessible -Property "State" -ChildId $childId
            })
        }
        else {
            Export-MsaaAccessible -Accessible $child -Depth ($Depth + 1) -Path "$Path/$i"
        }
    }
}

function Invoke-ClipboardCopyProbe {
    $before = ""
    try {
        $before = Get-Clipboard -Raw -Format Text -ErrorAction SilentlyContinue
    }
    catch {
        $before = ""
    }

    $wshell = New-Object -ComObject WScript.Shell
    $wshell.SendKeys("^a")
    Start-Sleep -Milliseconds 200
    $wshell.SendKeys("^c")
    Start-Sleep -Milliseconds 500

    $after = ""
    try {
        $after = Get-Clipboard -Raw -Format Text -ErrorAction SilentlyContinue
    }
    catch {
        $after = ""
    }

    if (-not [string]::IsNullOrEmpty($before)) {
        Set-Clipboard -Value $before
    }

    $afterProbe = Convert-ProbeText $after
    return [pscustomobject][ordered]@{
        attempted = $true
        beforeTextLength = if ($null -eq $before) { 0 } else { $before.Length }
        afterTextLength = if ($null -eq $after) { 0 } else { $after.Length }
        afterTextHash = $afterProbe.hash
        afterText = $afterProbe.text
        keywordHits = $afterProbe.keywordHits
        changed = ((Get-StringHashPrefix $before) -ne (Get-StringHashPrefix $after))
        restoredPreviousTextClipboard = (-not [string]::IsNullOrEmpty($before))
    }
}

function Save-Json {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$Path,
        [int]$Depth = 10
    )

    $Value | ConvertTo-Json -Depth $Depth | Set-Content -LiteralPath $Path -Encoding UTF8
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

Add-ProbeAssemblies
$process = Get-WeixinProcess
[void][WechatCommentProbeWin32]::ShowWindowAsync($process.MainWindowHandle, 9)
[void][WechatCommentProbeWin32]::SetForegroundWindow($process.MainWindowHandle)
Start-Sleep -Milliseconds 350

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $repoRoot = Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")
    $OutputDir = Join-Path $repoRoot ("windows-probe\comment-runs\" + (Get-Date -Format "yyyyMMdd_HHmmss"))
}
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

$rootElement = [System.Windows.Automation.AutomationElement]::FromHandle($process.MainWindowHandle)
if ($null -eq $rootElement) {
    throw "Could not locate UIA root."
}

$script:UiaNodes = New-Object System.Collections.ArrayList
$script:UiaNodeCount = 0
$script:UiaTextBearingCount = 0
$script:UiaKeywordCount = 0
$script:UiaTruncated = $false
Export-UiaElement -Element $rootElement -Depth 0 -Path "0"

$script:MsaaNodes = New-Object System.Collections.ArrayList
$script:MsaaNodeCount = 0
$script:MsaaTextBearingCount = 0
$script:MsaaKeywordCount = 0
$script:MsaaTruncated = $false
$msaaAvailable = $false
$msaaError = ""
try {
    $iid = New-Object Guid "618736e0-3c3d-11cf-810c-00aa00389b71"
    $accObject = $null
    $hr = [WechatCommentProbeWin32]::AccessibleObjectFromWindow(
        $process.MainWindowHandle,
        [WechatCommentProbeWin32]::OBJID_CLIENT,
        [ref]$iid,
        [ref]$accObject
    )
    if ($hr -eq 0 -and $null -ne $accObject) {
        $msaaAvailable = $true
        Export-MsaaAccessible -Accessible $accObject -Depth 0 -Path "0"
    }
    else {
        $msaaError = "AccessibleObjectFromWindow HRESULT=$hr"
    }
}
catch {
    $msaaError = $_.Exception.Message
}

$clipboard = if ($SkipClipboard) {
    [pscustomobject][ordered]@{ attempted = $false }
}
else {
    Invoke-ClipboardCopyProbe
}

$uiaNodesPath = Join-Path $OutputDir "uia_nodes.jsonl"
$msaaNodesPath = Join-Path $OutputDir "msaa_nodes.jsonl"
$summaryPath = Join-Path $OutputDir "summary.json"

Save-JsonLines -Values @($script:UiaNodes) -Path $uiaNodesPath
Save-JsonLines -Values @($script:MsaaNodes) -Path $msaaNodesPath

$windowTitle = Convert-ProbeText $rootElement.Current.Name
$summary = [pscustomobject][ordered]@{
    createdAt = (Get-Date).ToString("o")
    processId = $process.Id
    processName = $process.ProcessName
    includeRawText = [bool]$IncludeRawText
    windowTitle = $windowTitle.text
    windowTitleLength = $windowTitle.length
    windowTitleHash = $windowTitle.hash
    maxDepth = $MaxDepth
    maxNodes = $MaxNodes
    uia = [pscustomobject][ordered]@{
        nodeCount = @($script:UiaNodes).Count
        textBearingNodeCount = $script:UiaTextBearingCount
        keywordNodeCount = $script:UiaKeywordCount
        truncated = $script:UiaTruncated
        nodesFile = $uiaNodesPath
    }
    msaa = [pscustomobject][ordered]@{
        available = $msaaAvailable
        error = $msaaError
        nodeCount = @($script:MsaaNodes).Count
        textBearingNodeCount = $script:MsaaTextBearingCount
        keywordNodeCount = $script:MsaaKeywordCount
        truncated = $script:MsaaTruncated
        nodesFile = $msaaNodesPath
    }
    clipboard = $clipboard
}

Save-Json -Value $summary -Path $summaryPath -Depth 12
Write-Host "WeChat comment controls probe complete."
Write-Host "Summary: $summaryPath"
Write-Host "UIA nodes: $uiaNodesPath"
Write-Host "MSAA nodes: $msaaNodesPath"
