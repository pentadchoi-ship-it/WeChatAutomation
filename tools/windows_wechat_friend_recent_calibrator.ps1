[CmdletBinding()]
param(
    [string]$DisplayName = "friend-name",
    [int]$TargetMoments = 5,
    [string]$PlanPath = "",
    [string]$OutputDir = "",
    [switch]$ValidateOnly,
    [switch]$RunAfterSave
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

function Add-CalibratorAssemblies {
    Add-Type -AssemblyName UIAutomationClient
    Add-Type -AssemblyName UIAutomationTypes
    Add-Type -AssemblyName System.Drawing
    Add-Type -AssemblyName System.Windows.Forms

    if (-not ([System.Management.Automation.PSTypeName]"WechatFriendCalibratorWin32").Type) {
        Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

public static class WechatFriendCalibratorWin32 {
    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    public static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow);

    [DllImport("user32.dll")]
    public static extern void mouse_event(uint dwFlags, uint dx, uint dy, int dwData, UIntPtr dwExtraInfo);

    public const uint MOUSEEVENTF_WHEEL = 0x0800;
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

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
        $hash = (($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString("x2") }) -join "")
        return "item_" + $hash.Substring(0, 10)
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

function Invoke-WeixinScroll {
    param(
        [Parameter(Mandatory = $true)]$Bounds,
        [int]$X = -1,
        [int]$Y = -1,
        [int]$WheelCount = 4,
        [int]$WheelDelta = -360,
        [int]$WaitMilliseconds = 900
    )

    $windowX = if ($X -ge 0) { $X } else { [int]($Bounds.width / 2) }
    $windowY = if ($Y -ge 0) { $Y } else { [int]($Bounds.height * 0.65) }
    [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point(($Bounds.left + $windowX), ($Bounds.top + $windowY))
    Start-Sleep -Milliseconds 100
    for ($i = 0; $i -lt $WheelCount; $i++) {
        [WechatFriendCalibratorWin32]::mouse_event([WechatFriendCalibratorWin32]::MOUSEEVENTF_WHEEL, 0, 0, $WheelDelta, [UIntPtr]::Zero)
        Start-Sleep -Milliseconds 180
    }
    Start-Sleep -Milliseconds $WaitMilliseconds
}

function Capture-WeixinPage {
    param(
        [Parameter(Mandatory = $true)][string]$PageId,
        [Parameter(Mandatory = $true)][string]$Directory
    )

    $process = Get-WeixinProcess
    [void][WechatFriendCalibratorWin32]::ShowWindowAsync($process.MainWindowHandle, 9)
    [void][WechatFriendCalibratorWin32]::SetForegroundWindow($process.MainWindowHandle)
    Start-Sleep -Milliseconds 350
    $bounds = Get-WindowBounds $process.MainWindowHandle
    $path = Join-Path $Directory ("{0}.png" -f $PageId)
    Save-WindowScreenshot -Bounds $bounds -Path $path
    return [pscustomobject][ordered]@{
        process = $process
        bounds = $bounds
        path = $path
    }
}

function New-PointObject {
    param(
        [Parameter(Mandatory = $true)][int]$X,
        [Parameter(Mandatory = $true)][int]$Y
    )

    return [pscustomobject][ordered]@{ x = $X; y = $Y }
}

function New-MomentDraft {
    return [pscustomobject][ordered]@{
        postId = ""
        postedAtText = ""
        postType = "unknown"
        textPoint = $null
        media = New-Object System.Collections.ArrayList
        links = New-Object System.Collections.ArrayList
        touched = $false
    }
}

function Convert-DraftMoment {
    param([Parameter(Mandatory = $true)]$Draft)

    $moment = [ordered]@{
        postId = [string]$Draft.postId
        postedAtText = [string]$Draft.postedAtText
        postType = [string]$Draft.postType
        media = @($Draft.media)
        links = @($Draft.links)
    }
    if ($null -ne $Draft.textPoint) {
        $moment["textPoint"] = $Draft.textPoint
    }
    return [pscustomobject]$moment
}

function New-PageObject {
    param([Parameter(Mandatory = $true)][int]$Index)

    return [pscustomobject][ordered]@{
        pageId = "page_{0:D3}" -f $Index
        moments = New-Object System.Collections.ArrayList
        afterScroll = [pscustomobject][ordered]@{
            x = 280
            y = 560
            wheelCount = 4
            wheelDelta = -360
            waitMilliseconds = 1200
        }
    }
}

function Convert-PageObject {
    param([Parameter(Mandatory = $true)]$Page)

    return [pscustomobject][ordered]@{
        pageId = [string]$Page.pageId
        moments = @($Page.moments)
        afterScroll = $Page.afterScroll
    }
}

function Set-DraftPostType {
    param(
        [Parameter(Mandatory = $true)]$Draft,
        [Parameter(Mandatory = $true)][string]$NewKind
    )

    if ($NewKind -eq "text") {
        if ($Draft.postType -eq "unknown") {
            $Draft.postType = "pure_text"
        }
        return
    }

    if ($Draft.postType -eq "unknown" -or $Draft.postType -eq "pure_text") {
        if ($NewKind -eq "image") { $Draft.postType = "image_set" }
        elseif ($NewKind -eq "video") { $Draft.postType = "video" }
        elseif ($NewKind -eq "external_url") { $Draft.postType = "external_url" }
        else { $Draft.postType = $NewKind }
        return
    }

    if ($Draft.postType -ne $NewKind -and
        -not ($Draft.postType -eq "image_set" -and $NewKind -eq "image")) {
        $Draft.postType = "mixed"
    }
}

function Update-DraftFields {
    $script:CurrentMoment.postId = $postIdTextBox.Text
    $script:CurrentMoment.postedAtText = $postedAtTextBox.Text
    if ($postTypeComboBox.SelectedItem) {
        $script:CurrentMoment.postType = [string]$postTypeComboBox.SelectedItem
    }
}

function Update-PostFieldsFromDraft {
    $postIdTextBox.Text = $script:CurrentMoment.postId
    $postedAtTextBox.Text = $script:CurrentMoment.postedAtText
    $postTypeComboBox.SelectedItem = $script:CurrentMoment.postType
}

function Add-Log {
    param([Parameter(Mandatory = $true)][string]$Text)

    [void]$logListBox.Items.Add(("{0:HH:mm:ss} {1}" -f (Get-Date), $Text))
    $logListBox.TopIndex = [Math]::Max(0, $logListBox.Items.Count - 1)
}

function Update-LastPointLabel {
    if ($null -eq $script:LastPoint) {
        $lastPointLabel.Text = "Selected point: none"
    }
    else {
        $lastPointLabel.Text = "Selected point: x=$($script:LastPoint.x), y=$($script:LastPoint.y)"
    }
}

function Update-CurrentMomentLabel {
    $labelVariable = Get-Variable -Name currentMomentLabel -ErrorAction SilentlyContinue
    if ($null -ne $labelVariable) {
        $currentMomentLabel.Text = "Current post: {0}    Saved: {1}/{2}" -f $script:CurrentMoment.postId, $script:TotalMomentCount, $TargetMoments
    }
}

function Set-ImagePath {
    param([Parameter(Mandatory = $true)][string]$Path)

    if ($pictureBox.Image) {
        $oldImage = $pictureBox.Image
        $pictureBox.Image = $null
        $oldImage.Dispose()
    }
    $image = [System.Drawing.Image]::FromFile($Path)
    $pictureBox.Image = $image
    Add-Log ("Screenshot loaded: {0}x{1}" -f $image.Width, $image.Height)
}

function Convert-PicturePointToImagePoint {
    param(
        [Parameter(Mandatory = $true)][int]$X,
        [Parameter(Mandatory = $true)][int]$Y
    )

    if ($null -eq $pictureBox.Image) {
        return $null
    }

    $imageWidth = [double]$pictureBox.Image.Width
    $imageHeight = [double]$pictureBox.Image.Height
    $boxWidth = [double]$pictureBox.ClientSize.Width
    $boxHeight = [double]$pictureBox.ClientSize.Height
    if ($imageWidth -le 0 -or $imageHeight -le 0 -or $boxWidth -le 0 -or $boxHeight -le 0) {
        return $null
    }

    $scale = [Math]::Min($boxWidth / $imageWidth, $boxHeight / $imageHeight)
    $displayWidth = $imageWidth * $scale
    $displayHeight = $imageHeight * $scale
    $offsetX = ($boxWidth - $displayWidth) / 2
    $offsetY = ($boxHeight - $displayHeight) / 2

    if ($X -lt $offsetX -or $Y -lt $offsetY -or $X -gt ($offsetX + $displayWidth) -or $Y -gt ($offsetY + $displayHeight)) {
        return $null
    }

    $imageX = [int][Math]::Round(($X - $offsetX) / $scale)
    $imageY = [int][Math]::Round(($Y - $offsetY) / $scale)
    $imageX = [Math]::Max(0, [Math]::Min(([int]$imageWidth - 1), $imageX))
    $imageY = [Math]::Max(0, [Math]::Min(([int]$imageHeight - 1), $imageY))
    return New-PointObject -X $imageX -Y $imageY
}

function Update-CalibratorLayout {
    if ($null -eq $splitContainer -or $splitContainer.Width -le 0) {
        return
    }

    $leftMin = 320
    $rightMin = 360
    if ($splitContainer.Width -le ($leftMin + $rightMin)) {
        return
    }

    $desiredRightWidth = 420
    if ($splitContainer.Width -lt 820) {
        $desiredRightWidth = [Math]::Max($rightMin, $splitContainer.Width - $leftMin)
    }

    $distance = $splitContainer.Width - $desiredRightWidth
    $distance = [Math]::Max($leftMin, $distance)
    $distance = [Math]::Min(($splitContainer.Width - $rightMin), $distance)
    if ($distance -ge $splitContainer.Panel1MinSize -and $distance -le ($splitContainer.Width - $splitContainer.Panel2MinSize)) {
        $splitContainer.SplitterDistance = $distance
    }
}

function Reset-CurrentMoment {
    $nextIndex = $script:TotalMomentCount + 1
    $script:CurrentMoment = New-MomentDraft
    $script:CurrentMoment.postId = "moment_{0:D3}" -f $nextIndex
    $postIdTextBox.Text = $script:CurrentMoment.postId
    $postedAtTextBox.Text = ""
    $postTypeComboBox.SelectedItem = "unknown"
    Update-CurrentMomentLabel
    Add-Log ("New draft {0}" -f $script:CurrentMoment.postId)
}

function Save-CurrentMoment {
    Update-DraftFields
    if (-not $script:CurrentMoment.touched) {
        Add-Log "Skip empty moment draft."
        return
    }

    if ([string]::IsNullOrWhiteSpace($script:CurrentMoment.postId)) {
        $script:CurrentMoment.postId = "moment_{0:D3}" -f ($script:TotalMomentCount + 1)
    }

    [void]$script:CurrentPage.moments.Add((Convert-DraftMoment -Draft $script:CurrentMoment))
    $script:TotalMomentCount += 1
    Add-Log ("Saved {0} on {1}. Total={2}" -f $script:CurrentMoment.postId, $script:CurrentPage.pageId, $script:TotalMomentCount)
    Reset-CurrentMoment
}

function Save-Plan {
    param([bool]$CloseAfterSave = $false)

    Update-DraftFields
    $pages = New-Object System.Collections.ArrayList
    foreach ($page in $script:Pages) {
        if (@($page.moments).Count -gt 0) {
            [void]$pages.Add((Convert-PageObject -Page $page))
        }
    }

    if ($pages.Count -eq 0) {
        throw "No moments have been saved into the plan."
    }

    $display = $displayNameTextBox.Text
    if ([string]::IsNullOrWhiteSpace($display)) {
        $display = "friend-name"
    }

    $plan = [pscustomobject][ordered]@{
        schemaVersion = 1
        profile = [pscustomobject][ordered]@{
            displayName = $display
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
        pages = @($pages)
    }

    Save-Json -Value $plan -Path $script:ResolvedPlanPath -Depth 16
    Add-Log ("Plan saved: {0}" -f $script:ResolvedPlanPath)
    if ($CloseAfterSave) {
        $form.Close()
    }
}

function Invoke-CaptureFromPlan {
    Save-Plan
    if ($script:TotalMomentCount -lt $TargetMoments) {
        $confirm = [System.Windows.Forms.MessageBox]::Show(
            $form,
            ("Only {0}/{1} posts have been saved. Start capture anyway?" -f $script:TotalMomentCount, $TargetMoments),
            "Not enough posts",
            "YesNo",
            "Warning"
        )
        if ($confirm -ne [System.Windows.Forms.DialogResult]::Yes) {
            Add-Log "Capture cancelled: not enough saved posts."
            return
        }
    }

    $message = "Return WeChat to the first calibrated page, then click OK to start capture. The calibrator window will hide while capture runs."
    [void][System.Windows.Forms.MessageBox]::Show($form, $message, "Start capture", "OK", "Information")
    $captureScript = Join-Path $PSScriptRoot "windows_wechat_capture_friend_recent.ps1"
    $form.Hide()
    try {
        $captureParams = @{
            PlanPath = $script:ResolvedPlanPath
            MaxMoments = [int]$TargetMoments
        }
        if (-not [string]::IsNullOrWhiteSpace($OutputDir)) {
            $captureParams["OutputDir"] = $OutputDir
        }
        & $captureScript @captureParams
        [void][System.Windows.Forms.MessageBox]::Show("Capture finished. Check the capture-runs output directory.", "Capture finished", "OK", "Information")
    }
    finally {
        $form.Show()
    }
}

Add-CalibratorAssemblies

if ($TargetMoments -lt 1) {
    throw "TargetMoments must be >= 1."
}

$repoRoot = Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")
$safeProfile = ConvertTo-SafePathSegment $DisplayName
if ([string]::IsNullOrWhiteSpace($PlanPath)) {
    $PlanPath = Join-Path $repoRoot ("windows-probe\profiles\{0}_recent_capture_plan.json" -f $safeProfile)
}
$script:ResolvedPlanPath = $PlanPath

if ($ValidateOnly) {
    $weixinRunning = $false
    $weixinWindowFound = $false
    $bounds = $null
    try {
        $process = Get-WeixinProcess
        $weixinRunning = $true
        $weixinWindowFound = ($process.MainWindowHandle -ne 0)
        if ($weixinWindowFound) {
            $bounds = Get-WindowBounds $process.MainWindowHandle
        }
    }
    catch {
        $weixinRunning = $false
    }

    [pscustomobject][ordered]@{
        ok = $true
        canLoadWinForms = $true
        targetMoments = $TargetMoments
        planPath = $script:ResolvedPlanPath
        weixinRunning = $weixinRunning
        weixinWindowFound = $weixinWindowFound
        windowBounds = $bounds
    } | ConvertTo-Json -Depth 8
    return
}

$script:CalibrationDir = Join-Path $repoRoot ("windows-probe\calibration-runs\{0}_{1}" -f $safeProfile, (Get-Date -Format "yyyyMMdd_HHmmss"))
New-Item -ItemType Directory -Path $script:CalibrationDir -Force | Out-Null

$script:Pages = New-Object System.Collections.ArrayList
$script:CurrentPage = New-PageObject -Index 1
[void]$script:Pages.Add($script:CurrentPage)
$script:CurrentPageIndex = 1
$script:CurrentMoment = New-MomentDraft
$script:TotalMomentCount = 0
$script:LastPoint = $null

$firstCapture = Capture-WeixinPage -PageId $script:CurrentPage.pageId -Directory $script:CalibrationDir
$script:CurrentBounds = $firstCapture.bounds
$script:CurrentScreenshotPath = $firstCapture.path

$form = New-Object System.Windows.Forms.Form
$form.Text = "WeChat Friend Moments Calibrator"
$workingArea = [System.Windows.Forms.Screen]::PrimaryScreen.WorkingArea
$form.Width = [Math]::Min(1220, [Math]::Max(980, $workingArea.Width - 80))
$form.Height = [Math]::Min(860, [Math]::Max(700, $workingArea.Height - 80))
$form.MinimumSize = New-Object System.Drawing.Size(900, 680)
$form.StartPosition = "CenterScreen"
$form.ShowInTaskbar = $true
$form.TopMost = $true

$splitContainer = New-Object System.Windows.Forms.SplitContainer
$splitContainer.Dock = "Fill"
$splitContainer.FixedPanel = "Panel2"
$splitContainer.Panel1MinSize = 120
$splitContainer.Panel2MinSize = 120
$form.Controls.Add($splitContainer)

$imagePanel = New-Object System.Windows.Forms.Panel
$imagePanel.Dock = "Fill"
$imagePanel.AutoScroll = $false
$splitContainer.Panel1.Controls.Add($imagePanel)

$pictureBox = New-Object System.Windows.Forms.PictureBox
$pictureBox.Dock = "Fill"
$pictureBox.BackColor = [System.Drawing.Color]::FromArgb(245, 245, 245)
$pictureBox.SizeMode = "Zoom"
$imagePanel.Controls.Add($pictureBox)

$rightPanel = New-Object System.Windows.Forms.Panel
$rightPanel.Dock = "Fill"
$rightPanel.AutoScroll = $true
$rightPanel.Padding = New-Object System.Windows.Forms.Padding(10)
$splitContainer.Panel2.Controls.Add($rightPanel)

$y = 10
function New-Label {
    param([string]$Text, [int]$Top)
    $label = New-Object System.Windows.Forms.Label
    $label.Text = $Text
    $label.Left = 10
    $label.Top = $Top
    $label.Width = 360
    $label.Height = 20
    $rightPanel.Controls.Add($label)
    return $label
}

function New-TextBox {
    param([string]$Text, [int]$Top)
    $box = New-Object System.Windows.Forms.TextBox
    $box.Text = $Text
    $box.Left = 10
    $box.Top = $Top
    $box.Width = 360
    $rightPanel.Controls.Add($box)
    return $box
}

function New-Button {
    param([string]$Text, [int]$Left, [int]$Top, [int]$Width = 170)
    $button = New-Object System.Windows.Forms.Button
    $button.Text = $Text
    $button.Left = $Left
    $button.Top = $Top
    $button.Width = $Width
    $button.Height = 30
    $rightPanel.Controls.Add($button)
    return $button
}

$guideLabel = New-Object System.Windows.Forms.Label
$guideLabel.Left = 10
$guideLabel.Top = $y
$guideLabel.Width = 380
$guideLabel.Height = 82
$guideLabel.Text = "Simple flow:`r`n1. Click a place on the screenshot.`r`n2. Press Text / Image / Video / Link.`r`n3. Press Save this post, then continue."
$rightPanel.Controls.Add($guideLabel)
$y += 92

[void](New-Label -Text "Friend" -Top $y)
$y += 22
$displayNameTextBox = New-TextBox -Text $DisplayName -Top $y
$y += 38

$currentMomentLabel = New-Label -Text "Current post: moment_001" -Top $y
$currentMomentLabel.Height = 24
$y += 30

$lastPointLabel = New-Label -Text "Selected point: none" -Top $y
$lastPointLabel.Height = 24
$y += 34

$setTextButton = New-Button -Text "Text" -Left 10 -Top $y -Width 180
$addImageButton = New-Button -Text "Image" -Left 210 -Top $y -Width 180
$setTextButton.Height = 42
$addImageButton.Height = 42
$y += 50
$addVideoButton = New-Button -Text "Video" -Left 10 -Top $y -Width 180
$addLinkButton = New-Button -Text "Link / Repost" -Left 210 -Top $y -Width 180
$addVideoButton.Height = 42
$addLinkButton.Height = 42
$y += 58

$saveMomentButton = New-Button -Text "Save this post" -Left 10 -Top $y -Width 380
$saveMomentButton.Height = 42
$y += 50
$nextPageButton = New-Button -Text "Next page" -Left 10 -Top $y -Width 380
$nextPageButton.Height = 42
$y += 50
$runButton = New-Button -Text "Finish: Save + Run" -Left 10 -Top $y -Width 380
$runButton.Height = 42
$y += 56

$savePlanButton = New-Button -Text "Save plan only" -Left 10 -Top $y -Width 180
$newMomentButton = New-Button -Text "Discard draft" -Left 210 -Top $y -Width 180
$y += 40

$advancedLinkLabel = New-Object System.Windows.Forms.Label
$advancedLinkLabel.Left = 10
$advancedLinkLabel.Top = $y
$advancedLinkLabel.Width = 380
$advancedLinkLabel.Height = 34
$advancedLinkLabel.Text = "Defaults: images save up to 9 items; links save URL only. No other settings are needed."
$rightPanel.Controls.Add($advancedLinkLabel)
$y += 42

$postIdTextBox = New-TextBox -Text "moment_001" -Top $y
$postIdTextBox.Visible = $false
$postedAtTextBox = New-TextBox -Text "" -Top $y
$postedAtTextBox.Visible = $false
$postTypeComboBox = New-Object System.Windows.Forms.ComboBox
$postTypeComboBox.Left = 10
$postTypeComboBox.Top = $y
$postTypeComboBox.Width = 360
$postTypeComboBox.DropDownStyle = "DropDownList"
[void]$postTypeComboBox.Items.AddRange(@("unknown", "pure_text", "image_set", "video", "external_url", "mixed"))
$postTypeComboBox.SelectedItem = "unknown"
$postTypeComboBox.Visible = $false
$rightPanel.Controls.Add($postTypeComboBox)

$maxItemsInput = New-Object System.Windows.Forms.NumericUpDown
$maxItemsInput.Left = 10
$maxItemsInput.Top = $y
$maxItemsInput.Width = 90
$maxItemsInput.Minimum = 1
$maxItemsInput.Maximum = 20
$maxItemsInput.Value = 9
$maxItemsInput.Visible = $false
$rightPanel.Controls.Add($maxItemsInput)

$providerComboBox = New-Object System.Windows.Forms.ComboBox
$providerComboBox.Left = 115
$providerComboBox.Top = $y
$providerComboBox.Width = 255
$providerComboBox.DropDownStyle = "DropDownList"
[void]$providerComboBox.Items.AddRange(@("unknown", "video_account", "article", "link", "mini_program", "music", "product", "location"))
$providerComboBox.SelectedItem = "unknown"
$providerComboBox.Visible = $false
$rightPanel.Controls.Add($providerComboBox)

$planLabel = New-Label -Text ("Plan: {0}" -f $script:ResolvedPlanPath) -Top $y
$planLabel.Height = 40
$y += 46

$logListBox = New-Object System.Windows.Forms.ListBox
$logListBox.Left = 10
$logListBox.Top = $y
$logListBox.Width = 380
$logListBox.Height = 260
$rightPanel.Controls.Add($logListBox)

Set-ImagePath -Path $script:CurrentScreenshotPath
Reset-CurrentMoment
Add-Log ("Captured {0}" -f $script:CurrentPage.pageId)
Update-CalibratorLayout

$pictureBox.Add_MouseClick({
    param($sender, $eventArgs)

    try {
        $point = Convert-PicturePointToImagePoint -X $eventArgs.X -Y $eventArgs.Y
        if ($null -eq $point) {
            Add-Log "Click ignored outside screenshot."
            return
        }
        $script:LastPoint = $point
        Update-LastPointLabel
        Add-Log ("Clicked screenshot x={0}, y={1}" -f $point.x, $point.y)
    }
    catch {
        Add-Log ("Click failed: {0}" -f $_.Exception.Message)
    }
})

$setTextButton.Add_Click({
    if ($null -eq $script:LastPoint) {
        Add-Log "No point selected."
        return
    }
    Update-DraftFields
    $script:CurrentMoment.textPoint = $script:LastPoint
    $script:CurrentMoment.touched = $true
    Set-DraftPostType -Draft $script:CurrentMoment -NewKind "text"
    Update-PostFieldsFromDraft
    Add-Log ("Text point set for {0}" -f $script:CurrentMoment.postId)
})

$addImageButton.Add_Click({
    if ($null -eq $script:LastPoint) {
        Add-Log "No point selected."
        return
    }
    Update-DraftFields
    $media = [pscustomobject][ordered]@{
        id = "image_group_{0:D3}" -f (@($script:CurrentMoment.media).Count + 1)
        kind = "image"
        openPoint = $script:LastPoint
        saveMode = "viewer_batch"
        maxItems = [int]$maxItemsInput.Value
        baseName = "image"
        extension = "jpg"
    }
    [void]$script:CurrentMoment.media.Add($media)
    $script:CurrentMoment.touched = $true
    Set-DraftPostType -Draft $script:CurrentMoment -NewKind "image"
    Update-PostFieldsFromDraft
    Add-Log ("Image point added for {0}" -f $script:CurrentMoment.postId)
})

$addVideoButton.Add_Click({
    if ($null -eq $script:LastPoint) {
        Add-Log "No point selected."
        return
    }
    Update-DraftFields
    $media = [pscustomobject][ordered]@{
        id = "video_{0:D3}" -f (@($script:CurrentMoment.media).Count + 1)
        kind = "video"
        openPoint = $script:LastPoint
        saveMode = "viewer_batch"
        maxItems = 1
        baseName = "video"
        extension = "mp4"
    }
    [void]$script:CurrentMoment.media.Add($media)
    $script:CurrentMoment.touched = $true
    Set-DraftPostType -Draft $script:CurrentMoment -NewKind "video"
    Update-PostFieldsFromDraft
    Add-Log ("Video point added for {0}" -f $script:CurrentMoment.postId)
})

$addLinkButton.Add_Click({
    if ($null -eq $script:LastPoint) {
        Add-Log "No point selected."
        return
    }
    Update-DraftFields
    $link = [pscustomobject][ordered]@{
        id = "link_{0:D3}" -f (@($script:CurrentMoment.links).Count + 1)
        providerHint = [string]$providerComboBox.SelectedItem
        title = ""
        point = $script:LastPoint
    }
    [void]$script:CurrentMoment.links.Add($link)
    $script:CurrentMoment.touched = $true
    Set-DraftPostType -Draft $script:CurrentMoment -NewKind "external_url"
    Update-PostFieldsFromDraft
    Add-Log ("Link point added for {0}" -f $script:CurrentMoment.postId)
})

$saveMomentButton.Add_Click({
    Save-CurrentMoment
    if ($script:TotalMomentCount -ge $TargetMoments) {
        Add-Log ("Target moments reached: {0}" -f $TargetMoments)
    }
})

$newMomentButton.Add_Click({
    Reset-CurrentMoment
})

$nextPageButton.Add_Click({
    if ($script:CurrentMoment.touched) {
        Save-CurrentMoment
    }
    $form.Hide()
    try {
        $process = Get-WeixinProcess
        [void][WechatFriendCalibratorWin32]::ShowWindowAsync($process.MainWindowHandle, 9)
        [void][WechatFriendCalibratorWin32]::SetForegroundWindow($process.MainWindowHandle)
        Start-Sleep -Milliseconds 300
        Invoke-WeixinScroll -Bounds $script:CurrentBounds -WheelCount 4 -WheelDelta -360 -WaitMilliseconds 1200
        $script:CurrentPageIndex += 1
        $script:CurrentPage = New-PageObject -Index $script:CurrentPageIndex
        [void]$script:Pages.Add($script:CurrentPage)
        $capture = Capture-WeixinPage -PageId $script:CurrentPage.pageId -Directory $script:CalibrationDir
        $script:CurrentBounds = $capture.bounds
        $script:CurrentScreenshotPath = $capture.path
        Set-ImagePath -Path $script:CurrentScreenshotPath
        Add-Log ("Captured {0}" -f $script:CurrentPage.pageId)
    }
    catch {
        Add-Log ("Next page failed: {0}" -f $_.Exception.Message)
        [void][System.Windows.Forms.MessageBox]::Show($_.Exception.Message, "Next page failed", "OK", "Error")
    }
    finally {
        $form.Show()
    }
})

$savePlanButton.Add_Click({
    try {
        if ($script:CurrentMoment.touched) {
            Save-CurrentMoment
        }
        Save-Plan
        [void][System.Windows.Forms.MessageBox]::Show("Plan saved.", "Saved", "OK", "Information")
    }
    catch {
        Add-Log ("Save plan failed: {0}" -f $_.Exception.Message)
        [void][System.Windows.Forms.MessageBox]::Show($_.Exception.Message, "Save plan failed", "OK", "Error")
    }
})

$runButton.Add_Click({
    try {
        if ($script:CurrentMoment.touched) {
            Save-CurrentMoment
        }
        Invoke-CaptureFromPlan
    }
    catch {
        Add-Log ("Run failed: {0}" -f $_.Exception.Message)
        [void][System.Windows.Forms.MessageBox]::Show($_.Exception.Message, "Run failed", "OK", "Error")
    }
})

$form.Add_FormClosed({
    if ($pictureBox.Image) {
        $oldImage = $pictureBox.Image
        $pictureBox.Image = $null
        $oldImage.Dispose()
    }
})

$form.Add_Resize({
    Update-CalibratorLayout
})

$form.Add_Shown({
    Update-CalibratorLayout
    $form.WindowState = "Normal"
    $form.Activate()
    $form.BringToFront()
})

if ($RunAfterSave) {
    Add-Log "RunAfterSave is enabled. Use Save plan + run after calibration."
}

[void]$form.ShowDialog()
