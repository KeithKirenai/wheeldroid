@echo off
rem Wheeldroid - One-Click Launcher & Setup Wizard
rem Boots a private portable environment into deps\ (no admin required)
rem and opens the graphical setup assistant.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\bootstrap.ps1"
if errorlevel 1 pause
