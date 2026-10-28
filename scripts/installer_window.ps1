<#
.SYNOPSIS
    Wheeldroid - Interactive Setup & Deployment Wizard
.DESCRIPTION
    Native Windows GUI wizard (PowerShell 5.1 + Windows Forms) for Wheeldroid.
    - Scans for Mario Kart Wii disc images (.iso, .wbfs, .rvz) in typical emulator & download folders.
    - Detects connected Android ADB devices.
    - Automatically builds shards and installs to phone.
    Adapted from SSBU-Recomp (Denkishi) and WindowSmash (SNO2207), GPL-3.0.
#>

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$repo = Split-Path -Parent $PSScriptRoot
$bootstrapScript = Join-Path $repo "android-bootstrap.ps1"

# Create main Form
$form = New-Object System.Windows.Forms.Form
$form.Text = "Wheeldroid - Mario Kart Wii Android Installer"
$form.Size = New-Object System.Drawing.Size(720, 560)
$form.StartPosition = "CenterScreen"
$form.FormBorderStyle = "FixedDialog"
$form.MaximizeBox = $false
$form.BackColor = [System.Drawing.Color]::FromArgb(28, 28, 30)
$form.ForeColor = [System.Drawing.Color]::White

# Title Header
$lblTitle = New-Object System.Windows.Forms.Label
$lblTitle.Text = "Wheeldroid Installer & Setup"
$lblTitle.Font = New-Object System.Drawing.Font("Segoe UI", 16, [System.Drawing.FontStyle]::Bold)
$lblTitle.Location = New-Object System.Drawing.Point(24, 20)
$lblTitle.Size = New-Object System.Drawing.Size(500, 35)
$lblTitle.ForeColor = [System.Drawing.Color]::FromArgb(77, 208, 225)
$form.Controls.Add($lblTitle)

$lblSub = New-Object System.Windows.Forms.Label
$lblSub.Text = "Native AArch64 recompilation for Android. Zero emulation, pure hardware execution."
$lblSub.Font = New-Object System.Drawing.Font("Segoe UI", 9)
$lblSub.Location = New-Object System.Drawing.Point(26, 55)
$lblSub.Size = New-Object System.Drawing.Size(650, 20)
$lblSub.ForeColor = [System.Drawing.Color]::LightGray
$form.Controls.Add($lblSub)

# Disc Image Section
$grpDisc = New-Object System.Windows.Forms.GroupBox
$grpDisc.Text = " 1. Game Disc Image (RMCP01 / RMCE01 / RMCJ01) "
$grpDisc.Font = New-Object System.Drawing.Font("Segoe UI", 9, [System.Drawing.FontStyle]::Bold)
$grpDisc.ForeColor = [System.Drawing.Color]::FromArgb(255, 179, 0)
$grpDisc.Location = New-Object System.Drawing.Point(24, 90)
$grpDisc.Size = New-Object System.Drawing.Size(656, 105)

$txtDisc = New-Object System.Windows.Forms.TextBox
$txtDisc.Font = New-Object System.Drawing.Font("Segoe UI", 9)
$txtDisc.Location = New-Object System.Drawing.Point(16, 32)
$txtDisc.Size = New-Object System.Drawing.Size(515, 23)
$txtDisc.BackColor = [System.Drawing.Color]::FromArgb(44, 44, 46)
$txtDisc.ForeColor = [System.Drawing.Color]::White
$grpDisc.Controls.Add($txtDisc)

$btnBrowse = New-Object System.Windows.Forms.Button
$btnBrowse.Text = "Browse..."
$btnBrowse.Font = New-Object System.Drawing.Font("Segoe UI", 9)
$btnBrowse.Location = New-Object System.Drawing.Point(542, 30)
$btnBrowse.Size = New-Object System.Drawing.Size(95, 27)
$btnBrowse.BackColor = [System.Drawing.Color]::FromArgb(58, 58, 60)
$btnBrowse.FlatStyle = "Flat"
$btnBrowse.Add_Click({
    $dlg = New-Object System.Windows.Forms.OpenFileDialog
    $dlg.Filter = "Wii Disc Images (*.iso;*.wbfs;*.rvz;*.ciso;*.chd)|*.iso;*.wbfs;*.rvz;*.ciso;*.chd|All files (*.*)|*.*"
    if ($dlg.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
        $txtDisc.Text = $dlg.FileName
    }
})
$grpDisc.Controls.Add($btnBrowse)

$lblDiscStatus = New-Object System.Windows.Forms.Label
$lblDiscStatus.Text = "Scanning common folders..."
$lblDiscStatus.Font = New-Object System.Drawing.Font("Segoe UI", 8.5)
$lblDiscStatus.Location = New-Object System.Drawing.Point(16, 68)
$lblDiscStatus.Size = New-Object System.Drawing.Size(600, 25)
$lblDiscStatus.ForeColor = [System.Drawing.Color]::DarkGray
$grpDisc.Controls.Add($lblDiscStatus)
$form.Controls.Add($grpDisc)

# Target Device Section
$grpDevice = New-Object System.Windows.Forms.GroupBox
$grpDevice.Text = " 2. Target Android Device "
$grpDevice.Font = New-Object System.Drawing.Font("Segoe UI", 9, [System.Drawing.FontStyle]::Bold)
$grpDevice.ForeColor = [System.Drawing.Color]::FromArgb(129, 199, 132)
$grpDevice.Location = New-Object System.Drawing.Point(24, 205)
$grpDevice.Size = New-Object System.Drawing.Size(656, 80)

$lblDevice = New-Object System.Windows.Forms.Label
$lblDevice.Text = "Detecting connected ADB devices..."
$lblDevice.Font = New-Object System.Drawing.Font("Segoe UI", 9)
$lblDevice.Location = New-Object System.Drawing.Point(16, 32)
$lblDevice.Size = New-Object System.Drawing.Size(515, 25)
$lblDevice.ForeColor = [System.Drawing.Color]::White
$grpDevice.Controls.Add($lblDevice)

$btnRefreshDevice = New-Object System.Windows.Forms.Button
$btnRefreshDevice.Text = "Refresh"
$btnRefreshDevice.Font = New-Object System.Drawing.Font("Segoe UI", 9)
$btnRefreshDevice.Location = New-Object System.Drawing.Point(542, 28)
$btnRefreshDevice.Size = New-Object System.Drawing.Size(95, 27)
$btnRefreshDevice.BackColor = [System.Drawing.Color]::FromArgb(58, 58, 60)
$btnRefreshDevice.FlatStyle = "Flat"
$grpDevice.Controls.Add($btnRefreshDevice)
$form.Controls.Add($grpDevice)

# Progress / Log Box
$txtLog = New-Object System.Windows.Forms.TextBox
$txtLog.Multiline = $true
$txtLog.ScrollBars = "Vertical"
$txtLog.ReadOnly = $true
$txtLog.Font = New-Object System.Drawing.Font("Consolas", 8.5)
$txtLog.BackColor = [System.Drawing.Color]::FromArgb(18, 18, 18)
$txtLog.ForeColor = [System.Drawing.Color]::FromArgb(200, 200, 200)
$txtLog.Location = New-Object System.Drawing.Point(24, 298)
$txtLog.Size = New-Object System.Drawing.Size(656, 140)
$form.Controls.Add($txtLog)

# Action Buttons
$btnBuildOnly = New-Object System.Windows.Forms.Button
$btnBuildOnly.Text = "Build APK Only"
$btnBuildOnly.Font = New-Object System.Drawing.Font("Segoe UI", 9)
$btnBuildOnly.Location = New-Object System.Drawing.Point(365, 455)
$btnBuildOnly.Size = New-Object System.Drawing.Size(140, 42)
$btnBuildOnly.BackColor = [System.Drawing.Color]::FromArgb(58, 58, 60)
$btnBuildOnly.FlatStyle = "Flat"
$form.Controls.Add($btnBuildOnly)

$btnInstall = New-Object System.Windows.Forms.Button
$btnInstall.Text = "Build & Install to Phone"
$btnInstall.Font = New-Object System.Drawing.Font("Segoe UI", 10, [System.Drawing.FontStyle]::Bold)
$btnInstall.Location = New-Object System.Drawing.Point(515, 455)
$btnInstall.Size = New-Object System.Drawing.Size(165, 42)
$btnInstall.BackColor = [System.Drawing.Color]::FromArgb(0, 150, 136)
$btnInstall.FlatStyle = "Flat"
$form.Controls.Add($btnInstall)

# Auto-Discovery Function
function Search-Discs {
    $searchPaths = @(
        (Join-Path $env:USERPROFILE "Downloads"),
        (Join-Path $env:USERPROFILE "Documents\Dolphin Emulator\Load"),
        (Join-Path $env:USERPROFILE "Documents\Dolphin Emulator\Games"),
        (Join-Path $env:USERPROFILE "Games"),
        "D:\Games",
        "E:\Games"
    )
    $exts = @("*.iso", "*.wbfs", "*.rvz")
    foreach ($p in $searchPaths) {
        if (Test-Path $p) {
            foreach ($ext in $exts) {
                $found = Get-ChildItem -Path $p -Filter $ext -ErrorAction SilentlyContinue |
                    Where-Object { $_.Name -match "mario|kart|mkw|rmcp|rmce|rmcj" } |
                    Select-Object -First 1
                if ($found) {
                    return $found.FullName
                }
            }
        }
    }
    return $null
}

# Auto-Detect ADB Devices
function Check-AdbDevices {
    try {
        $adbOut = & adb devices 2>$null
        $lines = $adbOut | Where-Object { $_ -match "\tdevice$" }
        if ($lines) {
            $devCount = @($lines).Count
            $lblDevice.Text = "Connected: $devCount Android device(s) ready."
            $lblDevice.ForeColor = [System.Drawing.Color]::FromArgb(129, 199, 132)
            $btnInstall.Enabled = $true
        } else {
            $lblDevice.Text = "No ADB device detected (USB debugging enabled?). You can still Build APK."
            $lblDevice.ForeColor = [System.Drawing.Color]::FromArgb(255, 183, 77)
        }
    } catch {
        $lblDevice.Text = "ADB not in PATH or not running. Building APK only."
        $lblDevice.ForeColor = [System.Drawing.Color]::Gray
    }
}

$btnRefreshDevice.Add_Click({ Check-AdbDevices })

# Background Initialization
$form.Add_Shown({
    $foundDisc = Search-Discs
    if ($foundDisc) {
        $txtDisc.Text = $foundDisc
        $lblDiscStatus.Text = "Found game image: $foundDisc"
        $lblDiscStatus.ForeColor = [System.Drawing.Color]::FromArgb(129, 199, 132)
    } else {
        $lblDiscStatus.Text = "Please select your Mario Kart Wii (.iso, .wbfs, or .rvz) disc image."
    }
    Check-AdbDevices
    $txtLog.AppendText("Ready. Select disc image and tap Build or Install.`r`n")
})

# Build execution helper
function Run-Bootstrap([switch]$DoInstall) {
    $btnBuildOnly.Enabled = $false
    $btnInstall.Enabled = $false
    $txtLog.Clear()
    $txtLog.AppendText("Starting build pipeline...`r`n")

    $discArg = if ($txtDisc.Text) { "-DiscSource `"$($txtDisc.Text)`"" } else { "" }
    $installArg = if ($DoInstall) { "-Install" } else { "" }
    $cmd = "powershell -NoProfile -ExecutionPolicy Bypass -File `"$bootstrapScript`" $discArg $installArg"

    $proc = Start-Process -FilePath "powershell.exe" -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$bootstrapScript`" $discArg $installArg" -PassThru -NoNewWindow
    $proc.WaitForExit()

    $txtLog.AppendText("Build finished with exit code: $($proc.ExitCode)`r`n")
    $btnBuildOnly.Enabled = $true
    $btnInstall.Enabled = $true
}

$btnBuildOnly.Add_Click({ Run-Bootstrap })
$btnInstall.Add_Click({ Run-Bootstrap -DoInstall })

[void]$form.ShowDialog()
