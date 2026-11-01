@echo off
chcp 65001 >nul
title Zemory Family Guard - Uninstall
net session >nul 2>&1
if errorlevel 1 (
  echo Please right-click uninstall.bat and choose "Run as administrator".
  pause
  exit /b 1
)
set "DIR=%ProgramFiles%\ZemoryGuard"
sc stop ZemoryGuard >nul 2>&1
timeout /t 3 /nobreak >nul
taskkill /f /im ZemoryGuard.exe >nul 2>&1
if exist "%DIR%\ZemoryGuard.exe" "%DIR%\ZemoryGuard.exe" cleanup
sc delete ZemoryGuard >nul 2>&1
rmdir /s /q "%DIR%" 2>nul
rmdir /s /q "%ProgramData%\ZemoryGuard" 2>nul
echo Zemory Family Guard was removed.
pause
