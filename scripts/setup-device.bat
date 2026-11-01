@echo off
chcp 65001 >nul
REM FamilyGuard - one-time setup of a child device (phone, tablet or Android TV)
REM Usage:  setup-device.bat FamilyGuard-Child.apk                 (USB)
REM         setup-device.bat FamilyGuard-Child.apk 192.168.1.50    (Android TV over the network)
setlocal
set APK=%~1
if "%APK%"=="" set APK=FamilyGuard-Child.apk
set HOST=%~2
set PKG=com.familyguard.child
set ACC=%PKG%/%PKG%.enforce.GuardAccessibilityService

where adb >nul 2>nul || (echo adb not found. Install Android platform-tools first. & exit /b 1)
if not exist "%APK%" (echo File %APK% not found & exit /b 1)

if not "%HOST%"=="" adb connect %HOST%:5555

adb devices
echo == Installing
adb install -r "%APK%" || exit /b 1

echo == Device Owner
adb shell dpm set-device-owner %PKG%/.admin.AdminReceiver
if errorlevel 1 (
  echo !! Failed: remove all Google accounts from the device temporarily and run again.
  echo    On Xiaomi enable "USB debugging (Security settings)".
)

echo == Permissions
adb shell pm grant %PKG% android.permission.WRITE_SECURE_SETTINGS
adb shell appops set %PKG% GET_USAGE_STATS allow
adb shell pm grant %PKG% android.permission.POST_NOTIFICATIONS 2>nul

echo == Accessibility service
adb shell settings put secure enabled_accessibility_services %ACC%
adb shell settings put secure accessibility_enabled 1

adb shell monkey -p %PKG% 1 >nul 2>nul
echo.
echo Done. On the device: sign in with the parent account and name the device, then add Google accounts back.
endlocal
