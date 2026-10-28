@echo off
setlocal

rem WiiCompiled Android - APK builder. Usage:
rem   build-app.bat [debug|release]  [extra gradle args...]
rem Defaults to an interactive prompt when no variant is given.

set "MODE="
set "GRADLE_ARGS="
if /i "%~1"=="debug" set "MODE=debug"
if /i "%~1"=="release" set "MODE=release"
if defined MODE set "GRADLE_ARGS=%2 %3 %4 %5 %6 %7 %8 %9"
if not defined MODE set "GRADLE_ARGS=%*"

if not defined MODE (
    echo ===================================================
    echo [WiiCompiled Android] App Builder
    echo ===================================================
    echo Choose build variant:
    echo   [1] Release (Optimized, recommended for gaming)
    echo   [2] Debug   (Debug symbols, development)
    echo.
)
if not defined MODE set /p CHOICE="Select [1 or 2, default=1]: "
if "%CHOICE%"=="2" set "MODE=debug"
if not defined MODE set "MODE=release"

set "SCRIPT_DIR=%~dp0"
set "MKW_DIR=%SCRIPT_DIR%android"

if "%MODE%"=="debug" (
    set "GRADLE_TASK=assembleDebug"
    set "APK_PATH=%MKW_DIR%\app\build\outputs\apk\debug\app-debug.apk"
    set "MODE_LABEL=Debug"
) else (
    set "GRADLE_TASK=assembleRelease"
    set "APK_PATH=%MKW_DIR%\app\build\outputs\apk\release\app-release.apk"
    set "MODE_LABEL=Release"
)

echo ===================================================
echo [WiiCompiled Android] Building %MODE_LABEL% APK
echo ===================================================

set "SHARD_PREBUILT=%MKW_DIR%\app\src\main\jniLibs\arm64-v8a\libmkw_base_shared.a"

if exist "%SHARD_PREBUILT%" goto SHARDS_OK

echo [INFO] Prebuilt shard archive not found.
echo [INFO] Automatically triggering build-shards.bat first...
echo.
call "%SCRIPT_DIR%build-shards.bat"
if errorlevel 1 (
    echo [ERROR] Shard compilation failed! Cannot proceed with APK build.
    exit /b 1
)
echo.
echo [INFO] Shards successfully built. Proceeding with APK build...
echo.

:SHARDS_OK
echo [INFO] Found prebuilt shards archive:
echo        %SHARD_PREBUILT%
echo [INFO] Skipping shard compilation (fast build).

cd /d "%MKW_DIR%"

echo Building %MODE_LABEL% APK with Gradle...
call gradlew.bat %GRADLE_TASK% %GRADLE_ARGS%

if errorlevel 1 (
    echo [ERROR] Gradle build failed!
    cd /d "%SCRIPT_DIR%"
    exit /b 1
)

cd /d "%SCRIPT_DIR%"

echo ===================================================
echo [WiiCompiled Android] %MODE_LABEL% build complete!
if exist "%APK_PATH%" (
    echo Output APK: %APK_PATH%
    for %%F in ("%APK_PATH%") do (
        echo Size:       %%~zF bytes
        echo Timestamp:  %%~tF
    )
)
echo ===================================================