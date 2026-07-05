[CmdletBinding()]
param(
    [string]$OutputDir = ""
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.Drawing

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $repoRoot = Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")
    $OutputDir = Join-Path $repoRoot "windows-probe\mock-data"
}

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

function New-MockImage {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Title,
        [Parameter(Mandatory = $true)][string]$AccentHex,
        [Parameter(Mandatory = $true)][string]$Index
    )

    $width = 1200
    $height = 900
    $bitmap = New-Object System.Drawing.Bitmap($width, $height)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit

    $bg = [System.Drawing.ColorTranslator]::FromHtml("#F7F4EE")
    $ink = [System.Drawing.ColorTranslator]::FromHtml("#22313F")
    $muted = [System.Drawing.ColorTranslator]::FromHtml("#667085")
    $accent = [System.Drawing.ColorTranslator]::FromHtml($AccentHex)

    $bgBrush = New-Object System.Drawing.SolidBrush($bg)
    $inkBrush = New-Object System.Drawing.SolidBrush($ink)
    $mutedBrush = New-Object System.Drawing.SolidBrush($muted)
    $accentBrush = New-Object System.Drawing.SolidBrush($accent)
    $whiteBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
    $accentPen = New-Object System.Drawing.Pen($accent, 8)
    $thinPen = New-Object System.Drawing.Pen([System.Drawing.ColorTranslator]::FromHtml("#D0D5DD"), 3)

    $titleFont = New-Object System.Drawing.Font("Segoe UI", 54, [System.Drawing.FontStyle]::Bold)
    $subtitleFont = New-Object System.Drawing.Font("Segoe UI", 28, [System.Drawing.FontStyle]::Regular)
    $monoFont = New-Object System.Drawing.Font("Consolas", 22, [System.Drawing.FontStyle]::Regular)

    try {
        $graphics.FillRectangle($bgBrush, 0, 0, $width, $height)
        $graphics.FillRectangle($accentBrush, 0, 0, $width, 22)
        $graphics.FillRectangle($accentBrush, 0, $height - 22, $width, 22)

        $graphics.FillEllipse($accentBrush, 890, 90, 220, 220)
        $graphics.FillEllipse($whiteBrush, 932, 132, 136, 136)
        $graphics.DrawEllipse($accentPen, 922, 122, 156, 156)

        $graphics.DrawString($Title, $titleFont, $inkBrush, 82, 125)
        $graphics.DrawString("Windows WeChat automation mock asset", $subtitleFont, $mutedBrush, 88, 215)
        $graphics.DrawString("Safe draft test. Do not publish.", $subtitleFont, $inkBrush, 88, 275)

        $graphics.DrawRectangle($thinPen, 88, 382, 1024, 310)
        $graphics.DrawLine($thinPen, 88, 485, 1112, 485)
        $graphics.DrawLine($thinPen, 88, 588, 1112, 588)
        $graphics.DrawString("asset_index = " + $Index, $monoFont, $inkBrush, 122, 420)
        $graphics.DrawString("created_for = compose_chain_probe", $monoFont, $inkBrush, 122, 523)
        $graphics.DrawString("publish_guard = manual_confirmation_required", $monoFont, $inkBrush, 122, 626)

        [void]$graphics.Save()
        $bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    }
    finally {
        $titleFont.Dispose()
        $subtitleFont.Dispose()
        $monoFont.Dispose()
        $thinPen.Dispose()
        $accentPen.Dispose()
        $whiteBrush.Dispose()
        $accentBrush.Dispose()
        $mutedBrush.Dispose()
        $inkBrush.Dispose()
        $bgBrush.Dispose()
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

$image1 = Join-Path $OutputDir "mock_moment_01.png"
$image2 = Join-Path $OutputDir "mock_moment_02.png"
$textPath = Join-Path $OutputDir "mock_text.txt"
$manifestPath = Join-Path $OutputDir "mock_manifest.json"

New-MockImage -Path $image1 -Title "Mock Moment 01" -AccentHex "#2F80ED" -Index "01"
New-MockImage -Path $image2 -Title "Mock Moment 02" -AccentHex "#12B76A" -Index "02"

$mockText = @(
    "Mock Windows WeChat compose-chain test.",
    "Generated locally for automation validation.",
    "Do not publish. Manual confirmation required."
) -join [Environment]::NewLine

Set-Content -LiteralPath $textPath -Value $mockText -Encoding UTF8

$manifest = [pscustomobject][ordered]@{
    createdAt = (Get-Date).ToString("o")
    purpose = "windows_wechat_compose_chain_mock"
    publishGuard = "do_not_click_publish"
    images = @($image1, $image2)
    textFile = $textPath
    textPreview = $mockText
}

$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestPath -Encoding UTF8

Write-Host "Mock data generated."
Write-Host "Manifest: $manifestPath"
Write-Host "Image 1: $image1"
Write-Host "Image 2: $image2"
Write-Host "Text: $textPath"
