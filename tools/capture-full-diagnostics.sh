#!/usr/bin/env bash
set -euo pipefail
OUT="${1:-amaral-driver-lab-adb-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
adb get-state >/dev/null
adb shell getprop > "$OUT/getprop.txt"
adb shell dumpsys SurfaceFlinger > "$OUT/dumpsys-surfaceflinger.txt" || true
adb shell dumpsys thermalservice > "$OUT/dumpsys-thermalservice.txt" || true
adb shell dumpsys battery > "$OUT/dumpsys-battery.txt" || true
adb shell dumpsys meminfo com.amaral.driverlab > "$OUT/dumpsys-meminfo.txt" || true
adb shell dumpsys gfxinfo com.amaral.driverlab > "$OUT/dumpsys-gfxinfo.txt" || true
adb logcat -d -v threadtime > "$OUT/logcat.txt"
adb shell run-as com.amaral.driverlab sh -c 'find files/qualifications -maxdepth 2 -type f -print 2>/dev/null' \
  > "$OUT/app-qualification-files.txt" || true
printf 'Captured external diagnostics in %s\n' "$OUT"
printf 'Export diagnostic-bundle.zip from the app and place it in the same folder.\n'
