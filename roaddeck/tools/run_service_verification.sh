#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
case "${1:?API level required}" in
  29)
    bash tools/run_emulator_test.sh
    ;;
  36)
    adb shell wm size 720x1280
    adb shell wm density 240
    adb shell input keyevent 82
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    python3 tools/check_binding.py --output service-evidence
    ;;
  *) exit 2 ;;
esac
