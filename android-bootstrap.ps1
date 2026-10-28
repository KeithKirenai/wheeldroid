<#
.SYNOPSIS
    WiiCompiled Android - one-shot clone-to-phone bootstrap.

.DESCRIPTION
    Detects the Android SDK / NDK / CMake / Ninja / .NET toolchain, writes
    local.properties, validates/stages the MKW disc files, runs the translator
    to regenerate the shard sources if they are missing, then builds the shard
    archive, builds the APK, and (optionally) installs it over ADB.

    Run from the repository root. Safe to re-run: completed steps are skipped.

.PARAMETER DiscSource
    Either a directory containing the required game disc files (main.dol,
    StaticR.rel), or a path to a Wii disc image (ISO, WBFS, GCM, GCZ, CISO,
    CHD, WIA, RVZ). When an image is given the bootstrap builds and runs the
    native WiiDiscExtractor to pull main.dol + StaticR.rel out, then stages the
    two files into Assets.
    If omitted and the files are missing from Wiicompiled\\Assets, you will be
    prompted for a location.

.PARAMETER SkipEnter
    Non-interactive mode. Fail with a clear error instead of prompting when a
    required input (disc files / toolchain) is missing.

.PARAMETER Only
    Only run a subset of the pipeline: Detect, LocalProperties, Assets,
    Translate, Shards, App, Install. Default runs the whole pipeline.

.PARAMETER Release
    Build a release APK instead of a debug APK.

.PARAMETER Install
    Install the built APK over ADB after building.

.PARAMETER ForceTranslate
    Re-run the translator even if generated shard sources already exist.

.PARAMETER SdkRoot
    Override Android SDK root detection.

.PARAMETER NdkRoot
    Override Android NDK root detection.

.EXAMPLE
    .\android-bootstrap.ps1 -Install
    .\android-bootstrap.ps1 -Release -Install -DiscSource D:\dumps\RMCP01
    .\android-bootstrap.ps1 -Only Translate
    .\android-bootstrap.ps1 -Only DiscExtract -DiscSource D:\images\RMCP01.iso
    .\android-bootstrap.ps1 -Assets -DiscSource D:\images\RMCP01.wbfs
#>
[CmdletBinding()]
param(
    [string]$DiscSource = "",
    [switch]$SkipEnter,
    [ValidateSet("Detect", "LocalProperties", "Assets", "DiscExtract", "Translate", "Shards", "App", "Install")]
    [string]$Only = "",
    [switch]$Release,
    [switch]$Install,
    [switch]$ForceTranslate,
    [string]$SdkRoot = "",
    [string]$NdkRoot = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$script:SdkRootDetected = $null
$script:NdkDetected = $null
$script:CmakeDetected = $null
$script:NinjaDetected = $null

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Mkw = $Root
$AndroidDir = Join-Path $Root "android"
$AssetsDir = Join-Path $Root "Assets"
$LocalProps = Join-Path $AndroidDir "local.properties"
$Generated = Join-Path $Root "generated"
$ShardsCmake = Join-Path $Generated "build_shards\shards.cmake"
$ShardArchive = Join-Path $AndroidDir "app\src\main\jniLibs\arm64-v8a\libmkw_base_shared.a"
$Manifest = Join-Path $Root "projects\mkwii\recomp.yml"
$TranslatorCsproj = Join-Path $Root "translator\src\Translator.Cli\Translator.Cli.csproj"
$TranslatorDll = Join-Path $Root "translator\src\Translator.Cli\bin\Release\net8.0\Translator.Cli.dll"

$GameFiles = @("main.dol", "StaticR.rel")

# Build shortcuts for the extractor (CLI + GUI). These are thin wrappers that build
# the chosen project and invoke it; they reuse the same Has-DotNet8 / dotnet logic.
$ExtractorGuiCsproj = Join-Path $Root "Launcher\WiiDiscExtractorGui\WiiDiscExtractorGui.csproj"

function Invoke-ExtractorCli([string]$ImagePath, [string]$OutputDir, [switch]$Staging, [int]$CancelAfter) {
    if (-not (Has-DotNet8)) {
        Fail "WiiDiscExtractor requires the .NET 8 SDK. Install from https://dotnet.microsoft.com/download/dotnet/8.0"
    }
    Write-Host "Building WiiDiscExtractor..." -ForegroundColor Yellow
    Push-Location $Root
    try {
        & dotnet build $ExtractorCsproj -c Release --nologo -v q
        if ($LASTEXITCODE -ne 0) { Fail "WiiDiscExtractor build failed (exit $LASTEXITCODE)." }
    } finally { Pop-Location }

    if (-not (Test-Path $ExtractorExe)) {
        Fail "WiiDiscExtractor.exe not found after build."
    }

    $args = @()
    if ($ImagePath) { $args += "-i"; $args += $ImagePath }
    if ($OutputDir) { $args += "-o"; $args += $OutputDir }
    if ($Staging) { $args += "--staging" }
    if ($CancelAfter -gt 0) { $args += "--cancel-after"; $args += $CancelAfter.ToString() }

    Push-Location $Root
    try {
        $proc = Start-Process -FilePath $ExtractorExe -ArgumentList $args -NoNewWindow -Wait -PassThru
        return $proc.ExitCode
    } finally { Pop-Location }
}

function Invoke-ExtractorGui() {
    if (-not (Has-DotNet8)) {
        Fail "WiiDiscExtractor GUI requires the .NET 8 SDK. Install from https://dotnet.microsoft.com/download/dotnet/8.0"
    }
    Write-Host "Building WiiDiscExtractorGui..." -ForegroundColor Yellow
    Push-Location $Root
    try {
        & dotnet build $ExtractorGuiCsproj -c Release --nologo -v q
        if ($LASTEXITCODE -ne 0) { Fail "WiiDiscExtractorGui build failed (exit $LASTEXITCODE)." }
    } finally { Pop-Location }

    if (-not (Test-Path $ExtractorGuiExe)) {
        Fail "WiiDiscExtractorGui.exe not found after build."
    }
    Write-Host "Launching $ExtractorGuiExe" -ForegroundColor Cyan
    Push-Location $Root
    try {
        $proc = Start-Process -FilePath $ExtractorGuiExe -NoNewWindow -PassThru
        return $proc.Id
    } finally { Pop-Location }
}

# WiiDiscExtractor: pure-.NET ISO/WBFS extractor (main.dol + StaticR.rel).
# Builds and runs it when -DiscSource points at a disc image file rather than an
# already-extracted directory. GUI lives under Launcher/WiiDiscExtractorGui/.
$ExtractorCsproj = Join-Path $Root "Launcher\WiiDiscExtractor\WiiDiscExtractor.csproj"
$ExtractorExe = Join-Path $Root "Launcher\WiiDiscExtractor\bin\Release\net8.0-windows\WiiDiscExtractor.exe"
$ExtractorGuiExe = Join-Path $Root "Launcher\WiiDiscExtractorGui\bin\Release\net8.0-windows\WiiCompiledAndroidBuilder.exe"

# Shortcut entry points (powerShell-side wrappers, reused by the pipeline and by a
# future `disc-extract`/`disc-gui` task). The real extraction logic lives in
# WiiDiscImage.cs (shared library) so both the CLI and the GUI call the same code.

function Invoke-DiscExtract([string]$ImagePath, [string]$OutputDir = "", [switch]$Staging) {
    if (-not $ImagePath) { Fail "Invoke-DiscExtract: -ImagePath is required." }
    $out = if ($OutputDir) { $OutputDir } else { "" }
    $rc = Invoke-ExtractorCli -ImagePath $ImagePath -OutputDir $out -Staging:$Staging -CancelAfter 0
    return $rc
}

function Invoke-DiscGui() {
    $pid = Invoke-ExtractorGui
    Write-Host "Disc extractor GUI running (process id $pid)." -ForegroundColor Green
    return $pid
}

function Write-Step($Title, $Sub = "") {
    Write-Host ""
    Write-Host "== $Title ==" -ForegroundColor Cyan
    if ($Sub) { Write-Host $Sub -ForegroundColor DarkGray }
}

function Fail($Message, [int]$Code = 1) {
    Write-Host "[ERROR] $Message" -ForegroundColor Red
    if ($SkipEnter) { exit $Code }
    $reply = Read-Host "Press Enter to continue..."
    exit $Code
}

function Install-PortableSdk() {
    $cmdtoolsUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    $destSdk = Join-Path $Root "deps\AndroidSdk"
    if (Test-Path (Join-Path $destSdk "platform-tools\adb.exe")) {
        return $destSdk
    }
    $downloadDir = Join-Path $Root "build\downloads"
    New-Item -ItemType Directory -Force $downloadDir | Out-Null
    $zipPath = Join-Path $downloadDir "cmdline-tools.zip"
    Write-Host ""
    Write-Host "[Agnostic Auto-Download] Android SDK not detected on system." -ForegroundColor Cyan
    Write-Host "Downloading Android Command-Line Tools from official Google repository (~150 MB)..." -ForegroundColor Yellow
    if (-not (Test-Path $zipPath) -or (Get-Item $zipPath).Length -lt 100000000) {
        Invoke-WebRequest -Uri $cmdtoolsUrl -OutFile $zipPath -UseBasicParsing
    }
    Write-Host "Extracting Command-Line Tools into $destSdk ..." -ForegroundColor Cyan
    $extractTemp = Join-Path $downloadDir "cmdline_temp"
    if (Test-Path $extractTemp) { Remove-Item $extractTemp -Recurse -Force }
    New-Item -ItemType Directory -Force $extractTemp | Out-Null
    Expand-Archive -LiteralPath $zipPath -DestinationPath $extractTemp -Force
    $cmdDir = Join-Path $destSdk "cmdline-tools\latest"
    New-Item -ItemType Directory -Force $cmdDir | Out-Null
    Move-Item -Path (Join-Path $extractTemp "cmdline-tools\*") -Destination $cmdDir -Force
    Remove-Item $extractTemp -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "Installing platform-tools and build-tools via sdkmanager..." -ForegroundColor Cyan
    $sdkmanager = Join-Path $cmdDir "bin\sdkmanager.bat"
    if (Test-Path $sdkmanager) {
        & cmd /c "echo y | `"$sdkmanager`" --sdk_root=`"$destSdk`" `"platform-tools`" `"platforms;android-34`""
    }
    Write-Host "Android SDK successfully set up at: $destSdk" -ForegroundColor Green
    return $destSdk
}

function Find-AndroidSdk {
    $candidates = @($SdkRoot.trim(), $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA "Android\Sdk"),
        (Join-Path $env:USERPROFILE "AppData\Local\Android\Sdk"),
        "C:\Android\Sdk")
    foreach ($c in $candidates) {
        if (-not $c) { continue }
        $expanded = $c
        if ($c.StartsWith("~")) { $expanded = Join-Path $HOME $c.Substring(1) }
        if (Test-Path (Join-Path $expanded "platform-tools")) {
            return (Resolve-Path $expanded).Path
        }
    }
    return $null
}

function Install-PortableNdk([string]$TargetSdkDir) {
    $ndkVersion = "28.0.12433566"
    $zipName = "android-ndk-r28-windows.zip"
    $ndkUrl = "https://dl.google.com/android/repository/$zipName"
    $destDir = if ($TargetSdkDir) { Join-Path $TargetSdkDir "ndk\$ndkVersion" } else { Join-Path $Root "deps\ndk\$ndkVersion" }
    if (Test-Path (Join-Path $destDir "toolchains\llvm\prebuilt\windows-x86_64\bin")) {
        return $destDir
    }
    $downloadDir = Join-Path $Root "build\downloads"
    New-Item -ItemType Directory -Force $downloadDir | Out-Null
    $zipPath = Join-Path $downloadDir $zipName
    Write-Host ""
    Write-Host "[Agnostic Auto-Download] Android NDK 28 not detected locally." -ForegroundColor Cyan
    Write-Host "Downloading Android NDK r28 from official Google repository (~715 MB)..." -ForegroundColor Yellow
    Write-Host "URL: $ndkUrl" -ForegroundColor DarkGray
    if (-not (Test-Path $zipPath) -or (Get-Item $zipPath).Length -lt 700000000) {
        Invoke-WebRequest -Uri $ndkUrl -OutFile $zipPath -UseBasicParsing
    }
    Write-Host "Extracting Android NDK r28 into $destDir ..." -ForegroundColor Cyan
    $extractTemp = Join-Path $downloadDir "ndk_temp"
    if (Test-Path $extractTemp) { Remove-Item $extractTemp -Recurse -Force }
    New-Item -ItemType Directory -Force $extractTemp | Out-Null
    Expand-Archive -LiteralPath $zipPath -DestinationPath $extractTemp -Force
    $extractedFolder = Get-ChildItem $extractTemp -Directory | Select-Object -First 1
    New-Item -ItemType Directory -Force (Split-Path -Parent $destDir) | Out-Null
    Move-Item -Path $extractedFolder.FullName -Destination $destDir -Force
    Remove-Item $extractTemp -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "Android NDK 28 successfully installed at: $destDir" -ForegroundColor Green
    return $destDir
}

function Find-Ndk {
    param([string]$Sdk)
    if ($NdkRoot.trim()) {
        if (Test-Path $NdkRoot) { return (Resolve-Path $NdkRoot).Path }
        return $NdkRoot
    }
    foreach ($c in @($env:ANDROID_NDK_HOME, $env:ANDROID_NDK_ROOT, $env:ANDROID_NDK)) {
        if ($c -and (Test-Path $c)) { return (Resolve-Path $c).Path }
    }
    if ($Sdk) {
        $ndkDir = Join-Path $Sdk "ndk"
        if (Test-Path $ndkDir) {
            $versions = Get-ChildItem $ndkDir -Directory -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending
            foreach ($v in $versions) {
                $tc = Join-Path $v.FullName "toolchains\llvm\prebuilt\windows-x86_64\bin"
                if (Test-Path $tc) { return $v.FullName }
            }
        }
    }
    return $null
}

function Find-CMake {
    param([string]$Sdk)
    $candidates = @()
    if ($Sdk) {
        $cmakeRoot = Join-Path $Sdk "cmake"
        if (Test-Path $cmakeRoot) {
            $candidates += Get-ChildItem $cmakeRoot -Directory -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending |
                ForEach-Object { Join-Path $_.FullName "bin\cmake.exe" }
        }
    }
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    $fromPath = (Get-Command cmake.exe -ErrorAction SilentlyContinue)
    if ($fromPath) { return $fromPath.Source }
    return $null
}

function Find-Ninja {
    param([string]$Sdk)
    $candidates = @()
    if ($Sdk) {
        $cmakeRoot = Join-Path $Sdk "cmake"
        if (Test-Path $cmakeRoot) {
            $candidates += Get-ChildItem $cmakeRoot -Directory -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending |
                ForEach-Object { Join-Path $_.FullName "bin\ninja.exe" }
        }
        $ndkDir = Join-Path $Sdk "ndk"
        if (Test-Path $ndkDir) {
            $versions = Get-ChildItem $ndkDir -Directory -ErrorAction SilentlyContinue
            foreach ($v in $versions) {
                $candidates += Join-Path $v.FullName "prebuilt\windows-x86_64\bin\ninja.exe"
            }
        }
    }
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    $fromPath = (Get-Command ninja.exe -ErrorAction SilentlyContinue)
    if ($fromPath) { return $fromPath.Source }
    return $null
}

function Find-Adb {
    $fromPath = (Get-Command adb.exe -ErrorAction SilentlyContinue)
    if ($fromPath) { return $fromPath.Source }
    if ($script:SdkRootDetected) {
        $legacy = Join-Path $script:SdkRootDetected "platform-tools\adb.exe"
        if (Test-Path $legacy) { return $legacy }
    }
    return $null
}

function Has-DotNet8 {
    try {
        $sdks = & dotnet --list-sdks 2>$null
        return @($sdks -match "^8\.\d+\.\d+").Count -gt 0
    } catch { return $false }
}

function Write-LocalProperties {
    $content = @()
    $content += "sdk.dir=$($script:SdkRootDetected -replace '\\', '/')"
    # cmake.dir is only meaningful for a real CMake install; the SDK-managed
    # cmake (pinned via `version = "4.1.2"` in build.gradle.kts) is resolved by
    # Gradle itself, so PATH shims are intentionally omitted.
    if ($script:CmakeDetected -match "Sdk(\\|/)cmake") {
        $cmakeDir = Split-Path (Split-Path $script:CmakeDetected -Parent) -Parent
        $content += "cmake.dir=$($cmakeDir -replace '\\', '/')"
    }
    $content += ""
    Set-Content -Path $LocalProps -Value $content -Encoding ASCII
    Write-Host "Wrote $LocalProps"
    Get-Content $LocalProps | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
}

function Get-GameFileStatus {
    $missing = @()
    foreach ($f in $GameFiles) {
        if (-not (Test-Path (Join-Path $AssetsDir $f))) { $missing += $f }
    }
    return @($missing)
}

function Resolve-DiscSource {
    param([string]$DiscSource)
    # Returns a hashtable:
    #   Kind      = "dir" | "image"
    #   Source    = the directory to copy main.dol + StaticR.rel from
    #   Cleanup   = if true, caller should delete Source after staging
    #
    # Kind "dir"  — the user passed a directory that already contains the two files.
    # Kind "image" — the user passed an ISO/WBFS; the extractor produced Source as a
    #                 staging folder with both files side-by-side.
    if (-not $DiscSource) { return $null }

    $ds = $DiscSource
    if ($ds.StartsWith("~")) { $ds = Join-Path $HOME $ds.Substring(1) }
    $ds = [System.IO.Path]::GetFullPath($ds)

    if (-not (Test-Path $ds)) {
        Fail "DiscSource path not found: $DiscSource"
    }

    # Directory case: must contain both game files.
    $dolIn = Join-Path $ds "main.dol"
    $relIn = Join-Path $ds "StaticR.rel"
    if ((Test-Path $dolIn) -and (Test-Path $relIn)) {
        return @{ Kind = "dir"; Source = $ds; Cleanup = $false }
    }

    # Image case: any supported Wii container extension — build + run the extractor.
    $ext = [System.IO.Path]::GetExtension($ds).ToLowerInvariant()
    $supported = @(".iso", ".gcm", ".gcz", ".ciso", ".chd", ".wbfs", ".wia", ".rvz")
    if ($supported -contains $ext) {
        $staging = Extract-DiscImage $ds
        return @{ Kind = "image"; Source = $staging; Cleanup = $true }
    }

    # Anything else — treat as a directory the user expects us to check.
    Fail "DiscSource '$DiscSource' is not a directory containing main.dol + StaticR.rel " +
         "and is not a supported disc image (.iso/.gcm/.gcz/.ciso/.chd/.wbfs/.wia/.rvz)."
}

function Extract-DiscImage([string]$ImagePath) {
    if (-not (Has-DotNet8)) {
        Fail "Extracting from a disc image requires the .NET 8 SDK (to build WiiDiscExtractor). " +
             "Install from https://dotnet.microsoft.com/download/dotnet/8.0"
    }

    Write-Host "Building WiiDiscExtractor..." -ForegroundColor Yellow
    Push-Location $Root
    try {
        & dotnet build $ExtractorCsproj -c Release --nologo -v q
        if ($LASTEXITCODE -ne 0) { Fail "WiiDiscExtractor build failed (dotnet build exit code $LASTEXITCODE)." }
    } finally { Pop-Location }

    if (-not (Test-Path $ExtractorExe)) {
        Fail "WiiDiscExtractor.exe not found after build at $ExtractorExe"
    }

    # Output folder: a staging dir named <imagename>_extracted next to the image,
    # inside which main.dol + StaticR.rel sit side-by-side (the --staging mode).
    $imageName = [System.IO.Path]::GetFileNameWithoutExtension($ImagePath)
    $imageDir = [System.IO.Path]::GetDirectoryName($ImagePath)
    $stagingDir = Join-Path $imageDir "${imageName}_extracted"

    Write-Host "Extracting from $ImagePath -> $stagingDir" -ForegroundColor Cyan
    Push-Location $Root
    try {
        $proc = Start-Process -FilePath $ExtractorExe -ArgumentList @(
            "-i", $ImagePath,
            "-a", $stagingDir,
            "--staging",
            "--cancel-after", "0"
        ) -NoNewWindow -Wait -PassThru

        if ($proc.ExitCode -ne 0) {
            # The CLI prints its own ERROR line; surface both.
            Fail "WiiDiscExtractor failed (exit $($proc.ExitCode)). Check its output above."
        }
    } finally { Pop-Location }

    # Verify the staging dir actually contains the two files.
    foreach ($f in $GameFiles) {
        $p = Join-Path $stagingDir $f
        if (-not (Test-Path $p)) {
            Fail "Extraction produced no $f in $stagingDir."
        }
    }

    return $stagingDir
}

function Stage-GameFiles([string]$sourceDir) {
    if (-not (Test-Path $sourceDir)) { Fail "Disc source directory not found: $sourceDir" }
    foreach ($f in $GameFiles) {
        $src = Join-Path $sourceDir $f
        if (-not (Test-Path $src)) {
            Fail "Disc source is missing $f (looked at $src). Expected RMCP01 PAL: main.dol + StaticR.rel."
        }
    }
    if (-not (Test-Path $AssetsDir)) { New-Item -ItemType Directory -Path $AssetsDir | Out-Null }
    foreach ($f in $GameFiles) {
        Copy-Item (Join-Path $sourceDir $f) (Join-Path $AssetsDir $f) -Force
        Write-Host "Staged $f from $sourceDir" -ForegroundColor Green
    }
}

function Invoke-Translator {
    Write-Step "Running translator" "Regenerates Wiicompiled\generated (shard sources, data init, shards.cmake)"
    if (-not (Has-DotNet8)) {
        Fail "The .NET 8 SDK is required to run the translator. Install it from https://dotnet.microsoft.com/download/dotnet/8.0"
    }
    if (-not (Test-Path $TranslatorDll)) {
        Write-Host "Building translator CLI..." -ForegroundColor Yellow
        Push-Location $Mkw
        try {
            & dotnet build $TranslatorCsproj -c Release
            if ($LASTEXITCODE -ne 0) { Fail "Translator build failed (dotnet build exit code $LASTEXITCODE)." }
        } finally { Pop-Location }
    }

    $missing = @(Get-GameFileStatus)
    if ($missing.Count -gt 0) {
        Fail "Translation requires $($missing -join ', ') in $AssetsDir first."
    }

    Push-Location $Mkw
    try {
        # 1. Translate the full call graph from the game entry point.
        Write-Host ""; Write-Host "Translating base (this can take several minutes)..." -ForegroundColor Yellow
        & dotnet $TranslatorDll translate-recursive 0x800060A4 `
            --project $Manifest `
            --output-metadata "$Generated\base_translation_output.json"
        if ($LASTEXITCODE -ne 0) { Fail "translate-recursive failed (exit $LASTEXITCODE)." }

        # 2. Emit the embedded .data/.rodata/.sdata section initializer + RuntimeConfig.h
        Write-Host ""; Write-Host "Generating data-section initializer / RuntimeConfig.h..." -ForegroundColor Yellow
        & dotnet $TranslatorDll generate-data-init --project $Manifest
        if ($LASTEXITCODE -ne 0) { Fail "generate-data-init failed (exit $LASTEXITCODE)." }

        # 3. Emit the CMake build graph (shards.cmake) covering generated + runtime sources.
        Write-Host ""; Write-Host "Emitting CMake shard build graph..." -ForegroundColor Yellow
        & dotnet $TranslatorDll emit-build-shards --project $Manifest
        if ($LASTEXITCODE -ne 0) { Fail "emit-build-shards failed (exit $LASTEXITCODE)." }
    } finally { Pop-Location }
}

# ---------------------------------------------------------------------------
# Pipeline
# ---------------------------------------------------------------------------

if ($script:SdkRootDetected -eq $null -and $Only -ne "" -and $Only -ne "Detect") {
    # A specific step was requested without Detect; load the toolchain first.
    $script:SdkRootDetected = Find-AndroidSdk
    $script:NdkDetected = Find-Ndk -Sdk $script:SdkRootDetected
    $script:CmakeDetected = Find-CMake -Sdk $script:SdkRootDetected
    $script:NinjaDetected = Find-Ninja -Sdk $script:SdkRootDetected
}

if ($Only -eq "" -or $Only -eq "Detect") {
    Write-Step "Detecting toolchain"
    $script:SdkRootDetected = Find-AndroidSdk
    if ($script:SdkRootDetected) {
        Write-Host "Android SDK: $script:SdkRootDetected" -ForegroundColor Green
    } else {
        $script:SdkRootDetected = $null
        Write-Host "Android SDK: not found" -ForegroundColor DarkYellow
    }
    $script:NdkDetected = Find-Ndk -Sdk $script:SdkRootDetected
    if ($script:NdkDetected) {
        Write-Host "Android NDK: $script:NdkDetected" -ForegroundColor Green
    } else {
        $script:NdkDetected = $null
        Write-Host "Android NDK: not found" -ForegroundColor DarkYellow
    }
    $script:CmakeDetected = Find-CMake -Sdk $script:SdkRootDetected
    if ($script:CmakeDetected) {
        Write-Host "CMake:       $script:CmakeDetected" -ForegroundColor Green
    } else {
        $script:CmakeDetected = $null
        Write-Host "CMake:       not found" -ForegroundColor DarkYellow
    }
    $script:NinjaDetected = Find-Ninja -Sdk $script:SdkRootDetected
    if ($script:NinjaDetected) {
        Write-Host "Ninja:       $script:NinjaDetected" -ForegroundColor Green
    } else {
        $script:NinjaDetected = $null
        Write-Host "Ninja:       not found" -ForegroundColor DarkYellow
    }
    if (Has-DotNet8) { Write-Host ".NET 8:      OK" -ForegroundColor Green }
    else { Write-Host ".NET 8:      not found (needed only to re-run the translator)" -ForegroundColor DarkYellow }
    if (-not $script:SdkRootDetected) {
        $script:SdkRootDetected = Install-PortableSdk
        if (-not $script:SdkRootDetected -or -not (Test-Path $script:SdkRootDetected)) {
            Fail "Failed to automatically install Android SDK."
        }
    }

    if (-not $script:NdkDetected) {
        $script:NdkDetected = Install-PortableNdk -TargetSdkDir $script:SdkRootDetected
        if (-not $script:NdkDetected -or -not (Test-Path $script:NdkDetected)) {
            Fail "Failed to automatically install Android NDK 28."
        }
    }
}

if ($Only -eq "" -or $Only -eq "LocalProperties") {
    Write-Step "Writing local.properties"
    Write-LocalProperties
}

if ($Only -eq "" -or $Only -eq "Assets") {
    Write-Step "Staging MKW disc files (RMCP01 PAL)"
    $missing = @(Get-GameFileStatus)
    if ($missing.Count -gt 0) {
        Write-Host "Missing in $AssetsDir : $($missing -join ', ')" -ForegroundColor DarkYellow
        if ($DiscSource) {
            $ds = Resolve-DiscSource $DiscSource
            Write-Host "Staging from -DiscSource $DiscSource ($($ds.Kind))" -ForegroundColor Cyan
            Stage-GameFiles $ds.Source
            if ($ds.Cleanup) {
                Write-Host "Disc image staged; extracted tree left at $($ds.Source). Remove it when done." -ForegroundColor DarkYellow
            }
        } elseif (-not $SkipEnter) {
            $src = Read-Host "Directory containing your RMCP01 PAL main.dol + StaticR.rel (disc dump)"
            Stage-GameFiles $src
        } else {
            Fail "Game disc files are required in $AssetsDir. Provide -DiscSource <dir>."
        }
    } else {
        Write-Host "All game files present." -ForegroundColor Green
    }
}

if ($Only -eq "" -or $Only -eq "DiscExtract") {
    Write-Step "Extracting disc image (WiiDiscExtractor)"
    if (-not $DiscSource) {
        Fail "DiscExtract requires -DiscSource <image>. Pass a Wii ISO/WBFS path."
    }
    $ds = Resolve-DiscSource $DiscSource
    if ($null -eq $ds) {
        Fail "Could not resolve -DiscSource. Provide a supported disc image path."
    }
    if ($ds.Kind -eq "dir") {
        Write-Host "DiscSource is already an extracted directory; nothing to extract." -ForegroundColor DarkYellow
        Write-Host "main.dol and StaticR.rel are present at $DiscSource" -ForegroundColor Green
    }
    # If Kind == "image", Extract-DiscImage already ran inside Resolve-DiscSource and
    # printed its own progress. Nothing further to do here.
}

if ($Only -eq "" -or $Only -eq "Translate") {
    $needsTranslate = $ForceTranslate -or (-not (Test-Path $ShardsCmake))
    if ($needsTranslate) {
        Invoke-Translator
    } else {
        Write-Step "Translator" "Existing generated shard build found at $ShardsCmake"
        Write-Host "Skipping translator (use -ForceTranslate to re-run)." -ForegroundColor DarkGray
    }
}

if ($Only -eq "" -or $Only -eq "Shards") {
    Write-Step "Building shard archive"
    if (-not (Test-Path $ShardsCmake)) {
        Fail "Missing $ShardsCmake — run translate step first."
    }
    # Point build-shards.bat at the detected toolchain via env so it never re-hardcodes.
    if ($script:NdkDetected) { $env:ANDROID_NDK_HOME = $script:NdkDetected }
    if ($script:CmakeDetected) { $env:CMAKE_BIN = $script:CmakeDetected }
    if ($script:NinjaDetected) { $env:NINJA_BIN = $script:NinjaDetected }
    Push-Location $Root
    try {
        cmd /c "build-shards.bat"
        if ($LASTEXITCODE -ne 0) { Fail "build-shards.bat failed (exit $LASTEXITCODE)." }
    } finally { Pop-Location }
}

if ($Only -eq "" -or $Only -eq "App") {
    Write-Step "Building APK"
    if (-not (Test-Path $ShardArchive)) {
        Fail "Missing shard archive $ShardArchive — build shards first."
    }
    Push-Location $Root
    try {
        if ($Release) {
            cmd /c "build-app.bat release"
        } else {
            cmd /c "build-app.bat debug"
        }
        if ($LASTEXITCODE -ne 0) { Fail "APK build failed (exit $LASTEXITCODE)." }
    } finally { Pop-Location }
}

if ($Only -eq "" -or $Only -eq "Install") {
    if (-not $Install) {
        if ($Only -ne "") { Write-Step "Install" "Skipped (-Install not passed)." }
        exit 0
    }
    Write-Step "Installing APK over ADB"
    $adb = Find-Adb
    if (-not $adb) { Fail "adb not found. Install platform-tools or add Android SDK to PATH." }
    $apk = if ($Release) {
        Join-Path $AndroidDir "app\build\outputs\apk\release\app-release.apk"
    } else {
        Join-Path $AndroidDir "app\build\outputs\apk\debug\app-debug.apk"
    }
    if (-not (Test-Path $apk)) { Fail "APK not found at $apk" }
    & $adb install -r $apk
    if ($LASTEXITCODE -ne 0) { Fail "ADB install failed." }
}

Write-Host ""
Write-Host "Done." -ForegroundColor Green
