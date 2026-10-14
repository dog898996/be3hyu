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
      timeout 8 "$ADB" -s "$SERIAL" logcat -d -v brief \
        'AndroidRuntime:E' 'HoyoungDeviceProof:I' '*:S' 2>/dev/null | tail -240 || true
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
primary_passed=true
if ! timeout 720 "$ADB" -s "$SERIAL" shell am instrument -w -r \
  -e class com.hoyoung.assistant.DeviceRuntimeIntegrationTest,com.hoyoung.assistant.phone.PhoneDeviceIntegrationTest,com.hoyoung.assistant.memory.NativeAnnDeviceTest,com.hoyoung.assistant.memory.local.LocalNeuralDeviceTest \
  -e fixtureModelPath /data/local/tmp/hoyoung-semantic-model \
  "$TEST_RUNNER" | tee "$LOG_DIR/instrumentation.log"; then primary_passed=false; fi
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
if ! verify_instrumentation "$LOG_DIR/instrumentation.log" 16; then
  primary_passed=false
  echo '::error::Primary Android checks failed; independent real restart checks will still run, and this job remains failed.' >&2
fi
"$ADB" -s "$SERIAL" shell am force-stop "$APP_ID"
echo 'The force-stopped app is explicitly reopened by a second instrumentation process; no automatic force-stop recovery is claimed.'
explicit_recovery_passed=true
if ! timeout 120 "$ADB" -s "$SERIAL" shell am instrument -w -r \
  -e recoveryOnly true \
  -e class com.hoyoung.assistant.DeviceRuntimeIntegrationTest#dAcknowledgedOwnerDataSurvivesExplicitProcessStop \
  "$TEST_RUNNER" | tee "$LOG_DIR/recovery.log"; then explicit_recovery_passed=false; fi
if ! verify_instrumentation "$LOG_DIR/recovery.log" 1; then explicit_recovery_passed=false; fi

# Only this fresh emulator is touched. A shell-side probe runs before the next
# instrumentation/Activity launch so cached app state cannot fake auto recovery.
system_foreground_present() {
  local service_dump
  service_dump="$(timeout 8 "$ADB" -s "$SERIAL" shell dumpsys activity services "$APP_ID" 2>/dev/null)" || return 1
  printf '%s\n' "$service_dump" | python3 "$SCRIPT_DIR/verify-foreground-service.py" "$APP_ID"
}
capture_restart_failure() {
  local phase="$1" screenshot="$LOG_DIR/restart-$1-failure.png"
  echo "HOYOUNG_RESTART_FAILURE phase=$phase; offline-disposable-emulator-only; observing-OS-state-without-dismissing-own-app-errors"
  timeout 8 "$ADB" -s "$SERIAL" shell pidof "$APP_ID" || true
  timeout 8 "$ADB" -s "$SERIAL" shell dumpsys activity services "$APP_ID" | head -180 || true
  timeout 8 "$ADB" -s "$SERIAL" shell dumpsys activity processes "$APP_ID" | head -180 || true
  timeout 8 "$ADB" -s "$SERIAL" shell dumpsys activity exit-info "$APP_ID" | head -100 || true
  timeout 8 "$ADB" -s "$SERIAL" shell dumpsys window windows | python3 -c 'import sys; print("".join(line for line in sys.stdin if "mCurrentFocus=" in line or "mFocusedApp=" in line))' || true
  timeout 8 "$ADB" -s "$SERIAL" logcat -d -v brief \
    'ActivityManager:I' 'ActivityTaskManager:I' 'AndroidRuntime:E' '*:S' | tail -180 || true
  # The driver already proved this is a freshly wiped, offline AVD. Preserve its
  # real system dialog as PNG; never close an own-app crash/ANR to hide a failure.
  if timeout 8 "$ADB" -s "$SERIAL" exec-out screencap -p > "$screenshot"; then
    python3 - "$screenshot" "$phase" <<'PY'
import base64, pathlib, sys
body = pathlib.Path(sys.argv[1]).read_bytes()
if body.startswith(b'\x89PNG\r\n\x1a\n') and 0 < len(body) <= 5 * 1024 * 1024:
    encoded = base64.b64encode(body).decode('ascii')
    chunks = [encoded[offset:offset+2000] for offset in range(0, len(encoded), 2000)]
    for index, chunk in enumerate(chunks):
        print(f'HOYOUNG_UI_FAILURE_PNG_BASE64 restart-{sys.argv[2]} {index}/{len(chunks)} {chunk}')
else:
    print('Bounded actual restart screenshot could not be captured.')
PY
  fi
}
await_system_foreground() {
  local phase="$1" deadline=$((SECONDS + 120))
  while (( SECONDS < deadline )); do
    if system_foreground_present; then
      echo "HOYOUNG_SYSTEM_FGS phase=$phase; real-dumpsys-foreground-before-test-Activity"
      return 0
    fi
    sleep 2
  done
  echo "::error::The actual Android foreground service did not recover during $phase." >&2
  return 1
}
run_restart_phase() {
  local phase="$1" phase_log="$LOG_DIR/restart-$1.log"
  # API35 AMS normally force-stops the target on instrumentation finish.
  # --no-restart injects into the already-running normal process and leaves its
  # real FGS alive, rather than measuring that test-harness cleanup as a crash.
  # Official source: android-15.0.0_r1 ActivityManagerService.java,
  # startInstrumentation()/finishInstrumentationLocked().
  timeout 120 "$ADB" -s "$SERIAL" shell am instrument --no-restart -w -r \
    -e restartPhase "$phase" \
    -e class com.hoyoung.assistant.DeviceRestartIntegrationTest \
    "$TEST_RUNNER" | tee "$phase_log"
  verify_instrumentation "$phase_log" 1
}
start_normal_app() {
  timeout 30 "$ADB" -s "$SERIAL" shell am start -W -n "$APP_ID/.MainActivity" >/dev/null
  local deadline=$((SECONDS + 30)) app_pid=''
  while (( SECONDS < deadline )); do
    app_pid="$(timeout 8 "$ADB" -s "$SERIAL" shell pidof "$APP_ID" 2>/dev/null | tr -d '\r' || true)"
    if [[ "$app_pid" =~ ^[0-9]+$ ]]; then return 0; fi
    sleep 1
  done
  echo '::error::The normal app process did not start before no-restart instrumentation.' >&2
  return 1
}
start_normal_app
run_restart_phase seed
await_system_foreground seed
"$ADB" -s "$SERIAL" shell am force-stop "$APP_ID"
sleep 5
stopped_pid="$(timeout 8 "$ADB" -s "$SERIAL" shell pidof "$APP_ID" 2>/dev/null | tr -d '\r' || true)"
if [[ -n "$stopped_pid" ]] || system_foreground_present; then
  echo '::error::A force-stopped app must not claim to keep running or bypass the Android stop boundary.' >&2
  exit 1
fi
echo 'HOYOUNG_SYSTEM_FORCE_STOP actual-process-and-FGS-absent; explicit-user-reopen-required'
start_normal_app
run_restart_phase forceReopen
await_system_foreground forceReopen
# Abrupt process death is a separate OS path from Java's crash-dialog policy.
# run-as targets only this debuggable fixture's own UID; it never signals another
# package or counts a user force-stop as something Android may auto-recover.
process_death_pid="$(timeout 8 "$ADB" -s "$SERIAL" shell pidof "$APP_ID" | tr -d '\r')"
if [[ ! "$process_death_pid" =~ ^[0-9]+$ ]]; then echo '::error::Expected one actual app PID before process death.' >&2; exit 1; fi
timeout 8 "$ADB" -s "$SERIAL" shell run-as "$APP_ID" /system/bin/kill -9 "$process_death_pid"
process_death_deadline=$((SECONDS + 300))
replacement_pid=''
while (( SECONDS < process_death_deadline )); do
  replacement_pid="$(timeout 8 "$ADB" -s "$SERIAL" shell pidof "$APP_ID" 2>/dev/null | tr -d '\r' || true)"
  if [[ "$replacement_pid" =~ ^[0-9]+$ && "$replacement_pid" != "$process_death_pid" ]] && system_foreground_present; then break; fi
  sleep 2
done
process_death_passed=true
echo "HOYOUNG_PROCESS_DEATH_PROBE oldPid=$process_death_pid; finalPid=${replacement_pid:-absent}; five-minute-bound; exact-system-FGS-required"
if [[ ! "$replacement_pid" =~ ^[0-9]+$ || "$replacement_pid" == "$process_death_pid" ]] || ! system_foreground_present; then
  echo '::error::Abrupt actual process death did not auto-recover a new PID and foreground service within five minutes.' >&2
  process_death_passed=false
  capture_restart_failure processDeath
  echo 'HOYOUNG_PROCESS_DEATH_BOOTSTRAP explicit-user-reopen-for-independent-Java-crash-and-reboot-checks; not-auto-recovery'
  "$ADB" -s "$SERIAL" shell am force-stop "$APP_ID"
  start_normal_app
  await_system_foreground afterProcessDeathExplicitReopen
else
  echo 'HOYOUNG_SYSTEM_PROCESS_DEATH_RECOVERY actual-own-UID-SIGKILL; new-process-PID; START_STICKY-FGS-recovered-before-test-Activity; not-Java-crash-or-force-stop'
  run_restart_phase processDeath
fi
crash_pid="$(timeout 8 "$ADB" -s "$SERIAL" shell pidof "$APP_ID" | tr -d '\r')"
if [[ ! "$crash_pid" =~ ^[0-9]+$ ]]; then echo '::error::Expected one actual app PID before crash.' >&2; exit 1; fi
# Record AMS state before injection, not just after the timeout: distinguishes
# an already broken service from Android's crash-dialog/restart policy.
timeout 8 "$ADB" -s "$SERIAL" shell dumpsys activity services "$APP_ID" | tee "$LOG_DIR/pre-java-crash-services.log"
echo "HOYOUNG_JAVA_CRASH_START oldPid=$crash_pid; elapsedHostSeconds=$SECONDS; auto-recovery-only"
"$ADB" -s "$SERIAL" shell am crash "$crash_pid"
crash_deadline=$((SECONDS + 120))
replacement_pid=''
while (( SECONDS < crash_deadline )); do
  replacement_pid="$(timeout 8 "$ADB" -s "$SERIAL" shell pidof "$APP_ID" 2>/dev/null | tr -d '\r' || true)"
  if [[ "$replacement_pid" =~ ^[0-9]+$ && "$replacement_pid" != "$crash_pid" ]] && system_foreground_present; then break; fi
  sleep 2
done
crash_passed=true
echo "HOYOUNG_JAVA_CRASH_PROBE oldPid=$crash_pid; finalPid=${replacement_pid:-absent}; two-minute-observation-bound; OS-dialog-wait-is-not-proof-of-service-defect"
if [[ ! "$replacement_pid" =~ ^[0-9]+$ || "$replacement_pid" == "$crash_pid" ]] || ! system_foreground_present; then
  echo '::error::An actual crashed app failed to replace its process and recover the foreground service.' >&2
  crash_passed=false
  capture_restart_failure crash
  # Keep this automatic-crash check failed. An explicit fixture reopen only
  # prepares the independent reboot check; it is never called auto recovery.
  echo 'HOYOUNG_CRASH_BOOTSTRAP explicit-force-stop-and-user-launch-for-independent-reboot-proof; not-auto-crash-recovery'
  "$ADB" -s "$SERIAL" shell am force-stop "$APP_ID"
  start_normal_app
  await_system_foreground afterCrashExplicitReopen
else
  echo 'HOYOUNG_SYSTEM_CRASH_RECOVERY actual-am-crash; new-process-PID; START_STICKY-FGS-recovered-before-test-Activity'
  run_restart_phase crash
fi
await_system_foreground beforeBoot
"$ADB" -s "$SERIAL" logcat -d -v brief 'HoyoungDeviceProof:I' '*:S' | tee "$LOG_DIR/device-proof-preboot.log"
"$ADB" -s "$SERIAL" reboot
timeout 240 "$ADB" -s "$SERIAL" wait-for-device
reboot_deadline=$((SECONDS + 240))
while (( SECONDS < reboot_deadline )); do
  if [[ "$(timeout 8 "$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then break; fi
  sleep 2
done
if [[ "$(timeout 8 "$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != 1 ]]; then
  echo '::error::A real emulator reboot did not complete in time.' >&2; exit 1
fi
"$ADB" -s "$SERIAL" shell input keyevent 224
"$ADB" -s "$SERIAL" shell input keyevent 82
"$ADB" -s "$SERIAL" shell wm dismiss-keyguard
"$ADB" -s "$SERIAL" shell cmd connectivity airplane-mode enable
"$ADB" -s "$SERIAL" shell svc wifi disable
"$ADB" -s "$SERIAL" shell svc data disable
await_system_foreground bootReceiver
run_restart_phase boot
{ cat "$LOG_DIR/device-proof-preboot.log"; "$ADB" -s "$SERIAL" logcat -d -v brief 'HoyoungDeviceProof:I' '*:S'; } | tee "$LOG_DIR/device-proof.log"
if ! $primary_passed || ! $explicit_recovery_passed || ! $process_death_passed || ! $crash_passed; then
  echo '::error::Independent restart phases finished, but an earlier Android test failure still prevents a successful job.' >&2
  exit 1
fi
python3 - "$LOG_DIR/device-proof.log" <<'PY'
import pathlib, sys
text = pathlib.Path(sys.argv[1]).read_text()
for marker in ('HOYOUNG_REAL_ANDROID_NN', 'HOYOUNG_REAL_ANDROID_ANN', 'HOYOUNG_DEVICE_SCREEN_OFF',
               'HOYOUNG_DEVICE_RECOVERY verified-after-explicit-force-stop', 'HOYOUNG_DEVICE_APP_JOURNAL_BACKUP',
               'HOYOUNG_DEVICE_PHOTO_UI', 'HOYOUNG_DEVICE_PAGED_CHAT', 'HOYOUNG_DEVICE_REPLY_NOTIFICATION', 'HOYOUNG_DEVICE_FIVE_LAYER_GATE',
               'HOYOUNG_RESTART_RECOVERED phase=forceReopen', 'HOYOUNG_RESTART_RECOVERED phase=processDeath',
               'HOYOUNG_RESTART_RECOVERED phase=crash',
               'HOYOUNG_RESTART_RECOVERED phase=boot'):
    if marker not in text:
        raise SystemExit('Missing actual Android proof marker: ' + marker)
print('Real native-neural, ANN, screen-off service, explicit-reopen recovery, application journal-backup, and native photo-UI proof reached the CI log.')
PY
