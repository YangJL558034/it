@echo off
cd /d "%~dp0"
if exist "artifacts\installer\FactoryMES-Setup-1.0.0-win-x64.exe" (
  start "" "artifacts\installer\FactoryMES-Setup-1.0.0-win-x64.exe"
) else (
  echo Build the Windows installer using scripts\build-windows.ps1.
  echo See README.md for development and deployment instructions.
  pause
)
