@echo off
chcp 65001 >nul
title Zemory Family Guard - Install
REM Run as Administrator. Installs the protection service and links this computer.

net session >nul 2>&1
if errorlevel 1 (
  echo Please right-click install.bat and choose "Run as administrator".
  pause
  exit /b 1
)

set "DIR=%ProgramFiles%\ZemoryGuard"
set "DATA=%ProgramData%\ZemoryGuard"

echo Stopping previous version...
sc stop ZemoryGuard >nul 2>&1
timeout /t 3 /nobreak >nul
taskkill /f /im ZemoryGuard.exe >nul 2>&1

if not exist "%DIR%" mkdir "%DIR%"
copy /y "%~dp0ZemoryGuard.exe" "%DIR%\ZemoryGuard.exe" >nul
if errorlevel 1 (
  echo Copy failed.
  pause
  exit /b 1
)

if not exist "%DATA%" mkdir "%DATA%"
REM only SYSTEM and Administrators can read the settings
icacls "%DATA%" /inheritance:r /grant:r *S-1-5-18:(OI)(CI)F *S-1-5-32-544:(OI)(CI)F >nul

if not exist "%DATA%\config.json" (
  "%DIR%\ZemoryGuard.exe" link
  if errorlevel 1 (
    echo Linking failed. Run install.bat again.
    pause
    exit /b 1
  )
)

sc query ZemoryGuard >nul 2>&1
if errorlevel 1 (
  sc create ZemoryGuard binPath= "\"%DIR%\ZemoryGuard.exe\" service" start= auto DisplayName= "Zemory Family Guard" >nul
)
sc description ZemoryGuard "Zemory Family Guard - parental control" >nul
sc failure ZemoryGuard reset= 0 actions= restart/5000/restart/5000/restart/5000 >nul
sc start ZemoryGuard >nul

echo.
echo Done. Zemory Family Guard is running.
echo The child must use a STANDARD (non-administrator) Windows account.
pause
