#!/usr/bin/env bash
# Verifies that AI screening works with ALL networking disabled.
#
# Needs: a device or emulator on adb, and the model installed in
# app/src/main/assets/model (see ml/install_model.py).
#
#   bash tools/verify_offline.sh
#
# It turns on airplane mode (and switches off Wi-Fi/data in case airplane mode
# leaves Wi-Fi on), runs the on-device instrumentation tests, prints the network
# state the test recorded, and restores connectivity afterwards.
set -euo pipefail
cd "$(dirname "$0")/.."

restore() {
  adb shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
  adb shell svc wifi enable >/dev/null 2>&1 || true
  adb shell svc data enable >/dev/null 2>&1 || true
}
trap restore EXIT

./gradlew :app:installDebug :app:installDebugAndroidTest

adb shell cmd connectivity airplane-mode enable
adb shell svc wifi disable
adb shell svc data disable
sleep 3
echo "Device network check (expect failure):"
adb shell ping -c 1 -W 2 8.8.8.8 && { echo "Device is still online - aborting"; exit 1; } || true

adb logcat -c
adb shell am instrument -w \
  -e class com.sih.netrasahayak.OfflineInferenceTest,com.sih.netrasahayak.MigrationTest \
  com.sih.netrasahayak.test/androidx.test.runner.AndroidJUnitRunner | tee build/offline_test_result.txt
adb logcat -d -s OfflineInferenceTest:I | tee -a build/offline_test_result.txt

grep -q "FAILURES!!!" build/offline_test_result.txt && exit 1
grep -q "network during test: offline" build/offline_test_result.txt || { echo "Inference test did not run offline"; exit 1; }
echo "OFFLINE VERIFICATION PASSED"
