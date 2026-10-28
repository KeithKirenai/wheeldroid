<#
.SYNOPSIS
    Wheeldroid / WiiCompiled - Portable Environment Bootstrapper
.DESCRIPTION
    Installs a private, portable Python into <repo>\deps\python from official python.org
    embeddable binaries, checked against SHA-256.
    Zero system-wide installation, PATH is never modified, registry is never touched.
    Adapted from SSBU-Recomp (Denkishi) and WindowSmash (SNO2207), GPL-3.0.
#>
param(
    [switch]$NoLaunch
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
$repo = Split-Path -Parent $PSScriptRoot
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ProgressPreference = 'SilentlyContinue'

# Official python.org embeddable release
$pyVersion = '3.13.16'
$embedUrl = "https://www.python.org/ftp/python/$pyVersion/python-$pyVersion-embed-amd64.zip"
$embedSha = '97dae5274cc54867065e8d5a3226e48c35017ed332a0fdb0e27d5b5821961297'
$pipName = 'pip-26.2.1-py3-none-any.whl'
$pipUrl = 'https://files.pythonhosted.org/packages/f3/6e/1736e5b4ae2b778ef2f81c47d797de9f891d4d8acb047a24ca37a60294dd/pip-26.2.1-py3-none-any.whl'
$pipSha = '71138adf1f4ca900cdb7d289c21b7494329f2332b6d85f0e1c42108c0384ed3e'

$dl = Join-Path $repo 'build\downloads'
$deps = Join-Path $repo 'deps'
$depsPy = Join-Path $deps 'python'
$pyExe = Join-Path $depsPy 'python.exe'

function Get-Verified([string]$url, [string]$file, [string]$sha) {
    if (Test-Path $file) {
        if ((Get-FileHash $file -Algorithm SHA256).Hash -eq $sha.ToUpper()) { return }
        Remove-Item $file -Force
    }
    Write-Host "Downloading $url ..." -ForegroundColor Cyan
    Invoke-WebRequest -Uri $url -OutFile $file -UseBasicParsing
    $h = (Get-FileHash $file -Algorithm SHA256).Hash
    if ($h -ne $sha.ToUpper()) {
        Remove-Item $file -Force
        throw "SHA-256 mismatch for $(Split-Path -Leaf $file): expected $sha, got $h"
    }
}

function Test-Private {
    if (-not (Test-Path $pyExe)) { return $false }
    try {
        & $pyExe -c "import sys; sys.exit(0)" 2>$null | Out-Null
        return $LASTEXITCODE -eq 0
    } catch { return $false }
}

function Install-Private {
    New-Item -ItemType Directory -Force $dl | Out-Null
    if (-not (Test-Path $pyExe)) {
        $zip = Join-Path $dl "python-$pyVersion-embed-amd64.zip"
        Get-Verified $embedUrl $zip $embedSha
        if (Test-Path $depsPy) { Remove-Item $depsPy -Recurse -Force }
        New-Item -ItemType Directory -Force $depsPy | Out-Null
        Expand-Archive -LiteralPath $zip -DestinationPath $depsPy -Force
    }

    # Enable site-packages in embeddable python: uncomment "import site" in pythonXY._pth
    $pth = Get-ChildItem $depsPy -Filter 'python*._pth' | Select-Object -First 1
    if (-not $pth) { throw 'python._pth not found in embeddable package' }
    $txt = (Get-Content $pth.FullName) -replace '^\s*#\s*import site', 'import site'
    Set-Content -Path $pth.FullName -Value $txt -Encoding ASCII

    Write-Host "Portable Python ready at: $depsPy" -ForegroundColor Green
}

# Unblock files downloaded via browser
try {
    Get-ChildItem -LiteralPath $repo -Recurse -File -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notlike "$repo\deps\*" -and $_.FullName -notlike "$repo\build\*" -and $_.FullName -notlike "$repo\.git\*" } |
        Unblock-File -ErrorAction SilentlyContinue
} catch {}

if (-not (Test-Private)) {
    Install-Private
} else {
    Write-Host "Verified portable Python in deps\python" -ForegroundColor Green
}

if (-not $NoLaunch) {
    $wizard = Join-Path $PSScriptRoot "installer_window.ps1"
    if (Test-Path $wizard) {
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -STA -File $wizard
    }
}
