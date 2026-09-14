@echo off
setlocal
echo ===================================================
echo [WiiCompiled Android] Starting Setup Launcher (Avalonia)
echo ===================================================

set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

set "PROJECT=%SCRIPT_DIR%\Launcher\WiiCompiled.Setup.Android\WiiCompiled.Setup.Android\WiiCompiled.Setup.Android.csproj"
set "EXE=%SCRIPT_DIR%\Launcher\WiiCompiled.Setup.Android\WiiCompiled.Setup.Android\bin\Release\net8.0-windows\WiiCompiled.Setup.Android.exe"

where dotnet >nul 2>nul
if errorlevel 1 (
    echo [ERROR] .NET 8 SDK not found on PATH.
    echo Please install the .NET 8 SDK from https://dotnet.microsoft.com/download/dotnet/8.0
    pause
    exit /b 1
)

echo Building WiiCompiled Android Setup Launcher (incremental; skips when up to date)...
dotnet build "%PROJECT%" -c Release --nologo -v q
if errorlevel 1 (
    echo [ERROR] Failed to compile Setup Launcher.
    pause
    exit /b 1
)

echo Launching Setup Launcher...
start "" "%EXE%"
exit /b 0
