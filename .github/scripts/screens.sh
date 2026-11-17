#!/usr/bin/env bash
# Runs inside the Android emulator job: installs the APK, visits each tab in portrait and
# landscape, and saves screenshots + crash logs to ./shots.
set -x
APK=${1:-RKACast.apk}
mkdir -p shots
adb install -r "$APK"
adb shell pm grant com.rkacast android.permission.POST_NOTIFICATIONS || true
adb logcat -c

shot() {
  sleep "${2:-5}"
  adb exec-out screencap -p > "shots/$1.png"
}

adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell am start -W -n com.rkacast/.MainActivity
shot launch 8
for tab in HOME BROWSER STREMIO REMOTE SETTINGS; do
  adb shell am start -W -n com.rkacast/.MainActivity --es tab "$tab"
  shot "portrait-$tab" 6
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "shots/ui-$tab.xml" >/dev/null 2>&1
  if [ "$tab" = "BROWSER" ]; then shot "portrait-BROWSER-later" 15; fi
done

adb shell settings put system user_rotation 1
adb shell cmd window user-rotation lock 1 || true
sleep 4
for tab in HOME STREMIO REMOTE SETTINGS; do
  adb shell am start -W -n com.rkacast/.MainActivity --es tab "$tab"
  shot "landscape-$tab" 5
done
adb shell cmd window user-rotation lock 0 || true
adb shell settings put system user_rotation 0

# Is the app still alive?
adb shell pidof com.rkacast > shots/pid.txt || echo "APP NOT RUNNING" > shots/pid.txt
adb logcat -d AndroidRuntime:E RKA/CRASH:E '*:S' > shots/crash-log.txt || true
adb logcat -d | grep -E 'RKA/|chromium|WebView|cr_' > shots/app-log.txt || true
echo "==== crash log ===="; cat shots/crash-log.txt
echo "==== app log (tail) ===="; tail -n 80 shots/app-log.txt
echo "==== pid ===="; cat shots/pid.txt
ls -la shots
