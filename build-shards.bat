@echo off
setlocal enabledelayedexpansion

echo ===================================================
echo [WiiCompiled Android] Compiling C++ Shards
echo ===================================================

set "SCRIPT_DIR=%~dp0"
set "ROOT_DIR=%SCRIPT_DIR%"
set "MKW_DIR=%ROOT_DIR%"
set "CMAKE_SHARDS=%MKW_DIR%\cmake\shards"
set "BUILD_DIR=%MKW_DIR%\cmake\shards\build_arm64"
set "OUT_DIR=%MKW_DIR%\android\app\src\main\jniLibs\arm64-v8a"
set "LOCAL_PROPS=%MKW_DIR%\android\local.properties"

rem ---------------------------------------------------------------
rem Resolve Android SDK root: env vars, then local.properties sdk.dir,
rem then well-known install locations.
rem ---------------------------------------------------------------
set "LP_SDK_DIR="
set "LP_NDK_DIR="
set "LP_CMAKE_DIR="
if exist "%LOCAL_PROPS%" (
    for /f "usebackq tokens=1,* delims==" %%A in ("%LOCAL_PROPS%") do (
        if /i "%%A"=="sdk.dir" set "LP_SDK_DIR=%%B"
        if /i "%%A"=="ndk.dir" set "LP_NDK_DIR=%%B"
        if /i "%%A"=="cmake.dir" set "LP_CMAKE_DIR=%%B"
    )
)

set "SDK_ROOT="
if defined LP_SDK_DIR if exist "%LP_SDK_DIR:\=/%" set "SDK_ROOT=%LP_SDK_DIR:\=/%"
if not defined SDK_ROOT if defined ANDROID_HOME if exist "%ANDROID_HOME%" set "SDK_ROOT=%ANDROID_HOME:\=/%"
if not defined SDK_ROOT if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%" set "SDK_ROOT=%ANDROID_SDK_ROOT:\=/%"
if not defined SDK_ROOT if exist "%LOCALAPPDATA%\Android\Sdk" set "SDK_ROOT=%LOCALAPPDATA%\Android\Sdk"
if not defined SDK_ROOT if exist "%USERPROFILE%\AppData\Local\Android\Sdk" set "SDK_ROOT=%USERPROFILE%\AppData\Local\Android\Sdk"
if not defined SDK_ROOT set "SDK_ROOT=C:\Android\Sdk"

rem ---------------------------------------------------------------
rem Resolve Android NDK: env override, local.properties ndk.dir, then
rem pick the newest installed under the SDK.
rem ---------------------------------------------------------------
set "SDK_ROOT_BS=%SDK_ROOT:/=\%"
set "ANDROID_NDK="
if defined ANDROID_NDK_HOME set "ANDROID_NDK=%ANDROID_NDK_HOME%"
if defined ANDROID_NDK set "ANDROID_NDK=%ANDROID_NDK:\=/%"
if not defined ANDROID_NDK if defined LP_NDK_DIR set "ANDROID_NDK=%LP_NDK_DIR%"
if not defined ANDROID_NDK if exist "%SDK_ROOT_BS%\ndk" (
    for /f "delims=" %%V in ('dir /b /ad /o-n "%SDK_ROOT_BS%\ndk" 2^>nul') do (
        if not defined ANDROID_NDK set "ANDROID_NDK=%SDK_ROOT_BS%\ndk\%%V"
    )
)

if not defined ANDROID_NDK (
    echo [ERROR] Android NDK not found. Set ANDROID_NDK_HOME or run android-bootstrap.ps1.
    exit /b 1
)
if not exist "%ANDROID_NDK%" (
    echo [ERROR] Android NDK not found at %ANDROID_NDK%
    exit /b 1
)

rem ---------------------------------------------------------------
rem Resolve CMake: env override, local.properties cmake.dir, portable deps, SDK, then PATH.
rem ---------------------------------------------------------------
set "CMAKE_BIN="
if defined CMAKE_BIN_SET set "CMAKE_BIN=%CMAKE_BIN%"
if not defined CMAKE_BIN if defined LP_CMAKE_DIR if exist "%LP_CMAKE_DIR%\bin\cmake.exe" set "CMAKE_BIN=%LP_CMAKE_DIR%\bin\cmake.exe"
if not defined CMAKE_BIN if exist "%ROOT_DIR%\deps\cmake\bin\cmake.exe" set "CMAKE_BIN=%ROOT_DIR%\deps\cmake\bin\cmake.exe"
if not defined CMAKE_BIN if exist "%SDK_ROOT_BS%\cmake\4.1.2\bin\cmake.exe" set "CMAKE_BIN=%SDK_ROOT_BS%\cmake\4.1.2\bin\cmake.exe"
if not defined CMAKE_BIN for /f "delims=" %%C in ('dir /b /ad /o-n "%SDK_ROOT_BS%\cmake" 2^>nul') do (
    if not defined CMAKE_BIN if exist "%SDK_ROOT_BS%\cmake\%%C\bin\cmake.exe" set "CMAKE_BIN=%SDK_ROOT_BS%\cmake\%%C\bin\cmake.exe"
)
where cmake.exe >nul 2>&1
if not defined CMAKE_BIN if %errorlevel% equ 0 set "CMAKE_BIN=cmake.exe"

rem ---------------------------------------------------------------
rem Resolve Ninja: env override, local.properties cmake.dir, portable deps, SDK, then PATH.
rem ---------------------------------------------------------------
set "NINJA_BIN="
if defined LP_CMAKE_DIR if exist "%LP_CMAKE_DIR%\bin\ninja.exe" set "NINJA_BIN=%LP_CMAKE_DIR%\bin\ninja.exe"
if not defined NINJA_BIN if exist "%ROOT_DIR%\deps\ninja\ninja.exe" set "NINJA_BIN=%ROOT_DIR%\deps\ninja\ninja.exe"
if not defined NINJA_BIN for /f "delims=" %%C in ('dir /b /ad /o-n "%SDK_ROOT_BS%\cmake" 2^>nul') do (
    if not defined NINJA_BIN if exist "%SDK_ROOT_BS%\cmake\%%C\bin\ninja.exe" set "NINJA_BIN=%SDK_ROOT_BS%\cmake\%%C\bin\ninja.exe"
)
where ninja.exe >nul 2>&1
if not defined NINJA_BIN if %errorlevel% equ 0 set "NINJA_BIN=ninja.exe"

echo [INFO] Android SDK   : %SDK_ROOT%
echo [INFO] Android NDK   : %ANDROID_NDK%
echo [INFO] CMake         : %CMAKE_BIN%
echo [INFO] Ninja         : %NINJA_BIN%
echo.

if "%CMAKE_BIN%"=="cmake.exe" (
    where cmake.exe >nul 2>nul
    if errorlevel 1 (
        echo [ERROR] CMake not found. Install CMake or set CMAKE_BIN.
        exit /b 1
    )
)
if "%NINJA_BIN%"=="ninja.exe" (
    where ninja.exe >nul 2>nul
    if errorlevel 1 (
        echo [ERROR] Ninja not found. Install Ninja or set NINJA_BIN.
        exit /b 1
    )
)

if not exist "%OUT_DIR%" (
    mkdir "%OUT_DIR%"
)

echo [1/2] Configuring Shards CMake build...
"%CMAKE_BIN%" ^
    "-H%CMAKE_SHARDS%" ^
    "-B%BUILD_DIR%" ^
    -GNinja ^
    "-DCMAKE_MAKE_PROGRAM=%NINJA_BIN%" ^
    "-DCMAKE_SYSTEM_NAME=Android" ^
    "-DCMAKE_SYSTEM_VERSION=28" ^
    "-DANDROID_PLATFORM=android-28" ^
    "-DANDROID_ABI=arm64-v8a" ^
    "-DCMAKE_ANDROID_ARCH_ABI=arm64-v8a" ^
    "-DANDROID_NDK=%ANDROID_NDK%" ^
    "-DCMAKE_ANDROID_NDK=%ANDROID_NDK%" ^
    "-DCMAKE_TOOLCHAIN_FILE=%ANDROID_NDK%\build\cmake\android.toolchain.cmake" ^
    "-DCMAKE_BUILD_TYPE=Release" ^
    "-DANDROID_STL=c++_shared"

if errorlevel 1 (
    echo [ERROR] CMake configure failed!
    exit /b 1
)

echo [2/2] Compiling Shards with Ninja...
"%NINJA_BIN%" -C "%BUILD_DIR%" mkw_base_shared

if errorlevel 1 (
    echo [ERROR] Shards compilation failed!
    exit /b 1
)

if exist "%BUILD_DIR%\libmkw_base_shared.a" (
    echo [SUCCESS] Copying libmkw_base_shared.a to %OUT_DIR%...
    copy /Y "%BUILD_DIR%\libmkw_base_shared.a" "%OUT_DIR%\libmkw_base_shared.a"
) else (
    echo [ERROR] libmkw_base_shared.a was not found in %BUILD_DIR%!
    exit /b 1
)

echo ===================================================
echo [WiiCompiled Android] Shard build complete!
echo Output: %OUT_DIR%\libmkw_base_shared.a
echo ===================================================