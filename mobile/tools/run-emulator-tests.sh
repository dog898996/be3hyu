#!/usr/bin/env bash
# Real API 35 instrumentation on a fresh, offline, hardware-accelerated AVD.
# Never targets a physical phone, imports an account, or uses a release signing key.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${1:-$(dirname -- "$SCRIPT_DIR")}"
TOOLS_DIR="${HOYOUNG_ANDROID_TOOLS_DIR:-/workspace/.tools/android-build}"
SDK_DIR="$TOOLS_DIR/sdk"
MODEL_DIR="${HOYOUNG_SEMANTIC_MODEL_DIR:-/workspace/.tools/hoyoung-semantic-model}"
APP_ID='com.hoyoung.assistant'
TEST_RUNNER="$APP_ID.test/androidx.test.runner.AndroidJUnitRunner"
EMULATOR_PORT="${HOYOUNG_EMULATOR_PORT:-5556}"
SERIAL="emulator-$EMULATOR_PORT"
ADB="$SDK_DIR/platform-tools/adb"
EMULATOR="$SDK_DIR/emulator/emulator"
AVD_MANAGER="$SDK_DIR/cmdline-tools/15859902/bin/avdmanager"
AVD_ROOT="$TOOLS_DIR/instrumentation-avd"
ANDROID_USER_DIR="$TOOLS_DIR/instrumentation-android-user"
LOG_DIR="${HOYOUNG_DEVICE_LOG_DIR:-$TOOLS_DIR/device-test-logs}"

if [[ ! -c /dev/kvm || ! -r /dev/kvm || ! -w /dev/kvm ]]; then
  echo 'Real instrumentation requires accessible KVM; no software/mock fallback is counted as a device pass.' >&2
  exit 1
fi
for command_path in "$ADB" "$EMULATOR" "$AVD_MANAGER"; do
  if [[ ! -x "$command_path" ]]; then echo "Missing official Android tool: $command_path" >&2; exit 1; fi
done
"$EMULATOR" -accel-check
if [[ ! "$EMULATOR_PORT" =~ ^[0-9]+$ || $((EMULATOR_PORT % 2)) != 0 ]]; then
  echo 'Emulator port must be an even integer.' >&2; exit 1
fi
if "$ADB" devices | awk -v target="$SERIAL" '$1 == target { found=1 } END { exit !found }'; then
  echo 'The test emulator port is already in use; refusing to modify another device.' >&2; exit 1
fi

python3 - "$PROJECT_DIR" "$MODEL_DIR" <<'PY'
import hashlib, json, pathlib, sys
manifest = json.loads((pathlib.Path(sys.argv[1]) / 'app/src/main/assets/semantic-model.json').read_text())
directory = pathlib.Path(sys.argv[2])
for specification in manifest['files']:
    name = specification['name']
    if name not in ('transformer.onnx', 'dense.safetensors', 'vocab.txt'):
        raise SystemExit('Unexpected test model file name.')
    path = directory / name
    if not path.is_file() or path.stat().st_size != specification['size']:
        raise SystemExit('Missing model fixture; run prepare-semantic-model.py outside the source tree first.')
    with path.open('rb') as stream:
        if hashlib.file_digest(stream, 'sha256').hexdigest() != specification['sha256']:
            raise SystemExit('Model fixture SHA256 differs from the application manifest.')
print('All three public neural-model fixtures match the pinned application manifest.')
PY

if [[ ! -f "$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk" || ! -f "$PROJECT_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" ]]; then
  "$SCRIPT_DIR/build-android.sh" "$PROJECT_DIR" assembleDebug assembleDebugAndroidTest
fi
mkdir -p "$AVD_ROOT" "$ANDROID_USER_DIR" "$LOG_DIR"
export ANDROID_HOME="$SDK_DIR" ANDROID_SDK_ROOT="$SDK_DIR"
export ANDROID_AVD_HOME="$AVD_ROOT" ANDROID_USER_HOME="$ANDROID_USER_DIR"
export JAVA_HOME="$TOOLS_DIR/jdk-17.0.16+8"
python3 - "$AVD_MANAGER" "$AVD_ROOT" <<'PY'
import os, pathlib, subprocess, sys
root = pathlib.Path(sys.argv[2])
result = subprocess.run([sys.argv[1], 'create', 'avd', '--force', '--name', 'hoyoung-instrumentation-api35',
    '--package', 'system-images;android-35;default;x86_64', '--path', str(root / 'hoyoung-instrumentation-api35.avd'),
    '--device', 'pixel_7'], input='no\n', text=True, capture_output=True)
if result.returncode:
    print(result.stdout); print(result.stderr); raise SystemExit(result.returncode)
config = root / 'hoyoung-instrumentation-api35.avd/config.ini'
values = {'hw.lcd.width':'720', 'hw.lcd.height':'1280', 'hw.lcd.density':'320',
    'hw.ramSize':'2560', 'hw.cpu.ncore':'2', 'hw.gpu.enabled':'yes', 'hw.gpu.mode':'swiftshader',
    'disk.dataPartition.size':'6G'}
lines=[]
for line in config.read_text().splitlines():
    key=line.split('=', 1)[0].strip()
    lines.append(key+'='+values.pop(key) if key in values else line)
lines.extend(key+'='+value for key,value in values.items())
config.write_text('\n'.join(lines)+'\n')
PY

emulator_pid=''
cleanup() {
  test_exit=$?
  trap - EXIT
  if [[ -n "$emulator_pid" ]]; then
    if (( test_exit != 0 )); then
      echo 'Offline disposable-emulator failure diagnostics (no account or physical phone was used):' >&2
      timeout 8 "$ADB" -s "$SERIAL" logcat -d -t 250 -v brief \
        'AndroidRuntime:E' 'HoyoungDeviceProof:I' '*:S' 2>/dev/null | tail -120 || true
      if [[ -f "$LOG_DIR/emulator.log" ]]; then
        tail -30 "$LOG_DIR/emulator.log" | sed '/pubkey/d;/public key/d' || true
      fi
    fi
    timeout 10 "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || kill "$emulator_pid" 2>/dev/null || true
    wait "$emulator_pid" 2>/dev/null || true
  fi
  exit "$test_exit"
}
trap cleanup EXIT
"$EMULATOR" -avd hoyoung-instrumentation-api35 -port "$EMULATOR_PORT" -accel on \
  -wipe-data -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader \
  -feature -Vulkan -memory 2560 -cores 2 > "$LOG_DIR/emulator.log" 2>&1 &
emulator_pid=$!
boot_ready=false
boot_started=$SECONDS
for ((attempt=1; SECONDS-boot_started<240; attempt++)); do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then
    echo 'Emulator exited before boot; no device tests were counted.' >&2
    tail -30 "$LOG_DIR/emulator.log" | sed '/pubkey/d;/public key/d'
    exit 1
  fi
  if [[ "$(timeout 8 "$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then
    boot_ready=true; break
  fi
  if (( attempt % 10 == 0 )); then echo "Waiting for accelerated Android boot, elapsed $((SECONDS-boot_started))s/240s"; fi
  sleep 2
done
if ! $boot_ready; then echo 'Android boot did not complete within the bounded wait.' >&2; exit 1; fi
if [[ "$("$ADB" -s "$SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
  echo 'Refusing test mutations because the target is not an emulator.' >&2; exit 1
fi
if [[ "$("$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')" != 35 ]]; then
  echo 'Instrumentation requires the declared API 35 fixture.' >&2; exit 1
fi
"$ADB" -s "$SERIAL" shell input keyevent 224
"$ADB" -s "$SERIAL" shell input keyevent 82
"$ADB" -s "$SERIAL" shell wm dismiss-keyguard
for animation_setting in window_animation_scale transition_animation_scale animator_duration_scale; do
  "$ADB" -s "$SERIAL" shell settings put global "$animation_setting" 0
done
"$ADB" -s "$SERIAL" shell cmd connectivity airplane-mode enable
"$ADB" -s "$SERIAL" shell svc wifi disable
"$ADB" -s "$SERIAL" shell svc data disable
"$ADB" -s "$SERIAL" install -r "$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
"$ADB" -s "$SERIAL" install -r "$PROJECT_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
for permission in READ_CONTACTS WRITE_CONTACTS READ_CALENDAR WRITE_CALENDAR; do
  "$ADB" -s "$SERIAL" shell pm revoke "$APP_ID" "android.permission.$permission" >/dev/null 2>&1 || true
done
"$ADB" -s "$SERIAL" shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS
"$ADB" -s "$SERIAL" shell mkdir -p /data/local/tmp/hoyoung-semantic-model
for model_file in transformer.onnx dense.safetensors vocab.txt; do
  "$ADB" -s "$SERIAL" push "$MODEL_DIR/$model_file" "/data/local/tmp/hoyoung-semantic-model/$model_file" >/dev/null
  "$ADB" -s "$SERIAL" shell chmod 644 "/data/local/tmp/hoyoung-semantic-model/$model_file"
done
"$ADB" -s "$SERIAL" shell chmod 755 /data/local/tmp/hoyoung-semantic-model
echo 'Testing a fresh API 35 AOSP emulator offline, with synthetic data and real ONNX native inference.'
"$ADB" -s "$SERIAL" logcat -c
timeout 720 "$ADB" -s "$SERIAL" shell am instrument -w -r \
  -e fixtureModelPath /data/local/tmp/hoyoung-semantic-model \
  "$TEST_RUNNER" | tee "$LOG_DIR/instrumentation.log"
verify_instrumentation() {
python3 - "$1" "$2" <<'PY'
import pathlib, re, sys
text = pathlib.Path(sys.argv[1]).read_text()
failure = re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed', text)
success = re.search(r'OK \((\d+) tests?\)', text)
if failure or not success or int(success.group(1)) < int(sys.argv[2]):
    raise SystemExit('Real instrumentation did not report the required passing test count.')
print('Verified real Android instrumentation:', success.group(1), 'passing tests.')
PY
}
verify_instrumentation "$LOG_DIR/instrumentation.log" 12
"$ADB" -s "$SERIAL" shell am force-stop "$APP_ID"
echo 'The force-stopped app is explicitly reopened by a second instrumentation process; no automatic force-stop recovery is claimed.'
timeout 120 "$ADB" -s "$SERIAL" shell am instrument -w -r \
  -e recoveryOnly true \
  -e class com.hoyoung.assistant.DeviceRuntimeIntegrationTest#dAcknowledgedOwnerDataSurvivesExplicitProcessStop \
  "$TEST_RUNNER" | tee "$LOG_DIR/recovery.log"
verify_instrumentation "$LOG_DIR/recovery.log" 1
"$ADB" -s "$SERIAL" logcat -d -v brief 'HoyoungDeviceProof:I' '*:S' | tee "$LOG_DIR/device-proof.log"
python3 - "$LOG_DIR/device-proof.log" <<'PY'
import pathlib, sys
text = pathlib.Path(sys.argv[1]).read_text()
for marker in ('HOYOUNG_REAL_ANDROID_NN', 'HOYOUNG_REAL_ANDROID_ANN', 'HOYOUNG_DEVICE_SCREEN_OFF',
               'HOYOUNG_DEVICE_RECOVERY verified-after-explicit-force-stop', 'HOYOUNG_DEVICE_APP_JOURNAL_BACKUP',
               'HOYOUNG_DEVICE_PHOTO_UI'):
    if marker not in text:
        raise SystemExit('Missing actual Android proof marker: ' + marker)
print('Real native-neural, ANN, screen-off service, explicit-reopen recovery, application journal-backup, and native photo-UI proof reached the CI log.')
PY
