#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p ci-evidence
collect_evidence() {
  adb shell dumpsys activity activities > ci-evidence/activities.txt || true
  adb shell dumpsys window windows > ci-evidence/windows.txt || true
  adb shell dumpsys accessibility > ci-evidence/accessibility.txt || true
  adb logcat -d -v threadtime > ci-evidence/logcat.txt || true
  adb pull /sdcard/Android/data/fr.roaddeck.launcher/files/. ci-evidence/screenshots >/dev/null 2>&1 || true
  adb exec-out screencap -p > ci-evidence/final-screen.png || true
}
trap collect_evidence EXIT
adb shell wm size 1280x720
adb shell wm density 160
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell input keyevent 82
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t fixture/build/outputs/apk/debug/fixture-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell settings put secure enabled_accessibility_services fr.roaddeck.launcher/.SplitScreenService
adb shell settings put secure accessibility_enabled 1
adb logcat -c
adb shell am instrument -w -r fr.roaddeck.launcher.test/androidx.test.runner.AndroidJUnitRunner | tee ci-evidence/instrumentation.txt
python3 - <<'PY'
from pathlib import Path
log = Path('ci-evidence/instrumentation.txt').read_text()
if 'OK (' not in log or 'FAILURES!!!' in log or 'INSTRUMENTATION_FAILED' in log:
    raise SystemExit('Android 10 instrumentation did not pass')
PY
