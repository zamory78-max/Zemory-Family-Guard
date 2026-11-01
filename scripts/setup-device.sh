#!/usr/bin/env bash
# FamilyGuard — one-time setup of a child device (phone, tablet or Android TV).
# Usage:  ./setup-device.sh FamilyGuard-Child.apk            (USB)
#         ./setup-device.sh FamilyGuard-Child.apk 192.168.1.50   (Android TV over the network)
set -u
APK="${1:-FamilyGuard-Child.apk}"
HOST="${2:-}"
PKG="com.familyguard.child"
ACC="$PKG/$PKG.enforce.GuardAccessibilityService"

command -v adb >/dev/null || { echo "adb غير موجود. ثبّت Android platform-tools أولاً."; exit 1; }
[ -f "$APK" ] || { echo "الملف $APK غير موجود"; exit 1; }

if [ -n "$HOST" ]; then
  echo "== الاتصال بالجهاز $HOST"
  adb connect "$HOST:5555" || adb connect "$HOST"
fi

echo "== الأجهزة المتصلة:"; adb devices
echo "== تثبيت التطبيق"
adb install -r "$APK" || exit 1

echo "== تفعيل مالك الجهاز (Device Owner)"
if ! adb shell dpm set-device-owner "$PKG/.admin.AdminReceiver"; then
  echo
  echo "!! فشل التفعيل. الأسباب الشائعة:"
  echo "   - توجد حسابات على الجهاز: احذف حسابات Google مؤقتاً من الإعدادات ثم أعد المحاولة."
  echo "   - يوجد مستخدم آخر أو ملف عمل على الجهاز."
  echo "   - أجهزة Xiaomi: فعّل «تصحيح USB (إعدادات الأمان)» في خيارات المطوّر."
  echo "   يمكن المتابعة بدونه لكن بعض الميزات (الحذف/التثبيت الصامت، القيود) لن تعمل."
fi

echo "== منح الصلاحيات"
adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS
adb shell appops set "$PKG" GET_USAGE_STATS allow
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null

echo "== تفعيل خدمة إمكانية الوصول"
CUR=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
if [ -z "$CUR" ] || [ "$CUR" = "null" ]; then NEW="$ACC"
elif echo "$CUR" | grep -q "$ACC"; then NEW="$CUR"
else NEW="$CUR:$ACC"; fi
adb shell settings put secure enabled_accessibility_services "$NEW"
adb shell settings put secure accessibility_enabled 1

echo "== فتح التطبيق"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || \
  adb shell monkey -p "$PKG" -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1

echo
echo "تم. الآن على الجهاز: سجّل الدخول بحساب الوالد وأعطِ الجهاز اسماً، ثم أعد إضافة حسابات Google."
