param([string]$Out = "amaral-driver-lab-adb-$(Get-Date -Format yyyyMMdd-HHmmss)")
$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $Out | Out-Null
adb get-state | Out-Null
adb shell getprop | Out-File -Encoding utf8 "$Out/getprop.txt"
adb shell dumpsys SurfaceFlinger | Out-File -Encoding utf8 "$Out/dumpsys-surfaceflinger.txt"
adb shell dumpsys thermalservice | Out-File -Encoding utf8 "$Out/dumpsys-thermalservice.txt"
adb shell dumpsys battery | Out-File -Encoding utf8 "$Out/dumpsys-battery.txt"
adb shell dumpsys meminfo com.amaral.driverlab | Out-File -Encoding utf8 "$Out/dumpsys-meminfo.txt"
adb shell dumpsys gfxinfo com.amaral.driverlab | Out-File -Encoding utf8 "$Out/dumpsys-gfxinfo.txt"
adb logcat -d -v threadtime | Out-File -Encoding utf8 "$Out/logcat.txt"
adb shell run-as com.amaral.driverlab sh -c "find files/qualifications -maxdepth 2 -type f -print 2>/dev/null" | Out-File -Encoding utf8 "$Out/app-qualification-files.txt"
Write-Host "Captured external diagnostics in $Out"
Write-Host "Export diagnostic-bundle.zip from the app and place it in the same folder."
