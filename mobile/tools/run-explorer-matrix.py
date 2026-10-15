#!/usr/bin/env python3
"""Independent, disposable API35 profiles; actual offline Android, never an account."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time

PROFILES = {
    'ko-compact': (720, 1280, 320, 1.0, 'ko-KR'),
    'ko-large-text': (1080, 2400, 420, 1.6, 'ko-KR'),
    'en-rotation': (960, 1600, 320, 1.0, 'en-US'),
    'ko-background': (720, 1600, 320, 1.0, 'ko-KR'),
    'ko-tablet': (1280, 800, 213, 1.2, 'ko-KR'),
}
parser = argparse.ArgumentParser()
parser.add_argument('profile', choices=PROFILES)
parser.add_argument('--scope', choices=('full', 'work-recovery'), default='full')
args = parser.parse_args()
project = Path(__file__).resolve().parents[1]
tools = Path(os.environ['HOYOUNG_ANDROID_TOOLS_DIR'])
sdk = tools / 'sdk'
logs = Path(os.environ.get('RUNNER_TEMP', '/tmp')) / ('hoyoung-explorer-' + args.profile)
logs.mkdir(parents=True, exist_ok=True)
env = dict(os.environ, ANDROID_HOME=str(sdk), ANDROID_AVD_HOME=str(logs / 'avd'),
           ANDROID_USER_HOME=str(logs / 'android-user'), JAVA_HOME=str(tools / 'jdk-17.0.16+8'))
for name in ('ANDROID_AVD_HOME', 'ANDROID_USER_HOME'):
    Path(env[name]).mkdir(exist_ok=True)
adb = str(sdk / 'platform-tools/adb')
serial = 'emulator-5556'
app = 'com.hoyoung.assistant'
runner = app + '.test/' + app + '.PreservingAccessibilityTestRunner'


def run(command, *, timeout=120, check=True, **kwargs):
    result = subprocess.run(command, env=env, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=timeout, **kwargs)
    if check and result.returncode:
        raise RuntimeError(str(command) + '\n' + result.stdout)
    return result.stdout


def device(*command, **kwargs):
    return run([adb, '-s', serial, *command], **kwargs)


def instrument(name, classes, expected, timeout):
    text = device('shell', 'am', 'instrument', '--no-restart', '-w', '-r', '-e', 'class',
                  classes, runner, timeout=timeout)
    (logs / (name + '.log')).write_text(text)
    print(text, flush=True)
    success = re.search(r'OK \((\d+) tests?\)', text)
    if re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed', text):
        raise RuntimeError(name + ' failed; original failure retained')
    if not success or int(success.group(1)) != expected:
        raise RuntimeError(name + ' did not execute every required scenario')
    return text


if not Path('/dev/kvm').is_char_device() or not os.access('/dev/kvm', os.R_OK | os.W_OK):
    raise SystemExit('Accessible KVM required; no simulated pass fallback')
if serial in run([adb, 'devices']):
    raise SystemExit('Reserved device already exists; refusing to touch another device')
avd_name = 'hoyoung-explorer'
avd = Path(env['ANDROID_AVD_HOME']) / (avd_name + '.avd')
run([str(sdk / 'cmdline-tools/15859902/bin/avdmanager'), 'create', 'avd', '--force',
     '--name', avd_name, '--package', 'system-images;android-35;default;x86_64',
     '--path', str(avd), '--device', 'pixel_7'], input='no\n')
width, height, density, font, locale = PROFILES[args.profile]
values = {'hw.lcd.width': str(width), 'hw.lcd.height': str(height), 'hw.lcd.density': str(density),
          'hw.ramSize': '2560', 'hw.cpu.ncore': '2', 'hw.gpu.enabled': 'yes',
          'hw.gpu.mode': 'swiftshader', 'disk.dataPartition.size': '6G'}
config = avd / 'config.ini'
lines = []
for line in config.read_text().splitlines():
    key = line.split('=', 1)[0].strip()
    lines.append(key + '=' + values.pop(key) if key in values else line)
lines.extend(key + '=' + value for key, value in values.items())
config.write_text('\n'.join(lines) + '\n')
emulator = None
try:
    with (logs / 'emulator.log').open('w') as output:
        emulator = subprocess.Popen([str(sdk / 'emulator/emulator'), '-avd', avd_name,
            '-port', '5556', '-accel', 'on', '-wipe-data', '-no-window', '-no-audio',
            '-no-boot-anim', '-no-snapshot', '-gpu', 'swiftshader', '-feature', '-Vulkan',
            '-memory', '2560', '-cores', '2'], env=env, stdout=output, stderr=subprocess.STDOUT)
    deadline = time.monotonic() + 240
    while time.monotonic() < deadline:
        if emulator.poll() is not None:
            raise RuntimeError('Emulator exited before boot')
        if device('shell', 'getprop', 'sys.boot_completed', check=False, timeout=8).strip() == '1':
            break
        time.sleep(2)
    else:
        raise RuntimeError('Emulator boot timeout')
    if device('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
        raise RuntimeError('Not a disposable emulator')
    # ADB can close its transport while the fresh emulator restarts adbd. Check
    # the observed UID after reconnecting instead of treating that EOF as a pass.
    root_ready = False
    for attempt in range(3):
        device('root', check=False, timeout=20)
        device('wait-for-device', timeout=30)
        if device('shell', 'id', '-u', check=False, timeout=10).strip() == '0':
            root_ready = True
            break
        time.sleep(1)
    if not root_ready:
        raise RuntimeError('Emulator adbd did not become root; no product tests executed')
    device('shell', 'cmd', 'connectivity', 'airplane-mode', 'enable')
    device('shell', 'svc', 'wifi', 'disable')
    device('shell', 'svc', 'data', 'disable', check=False)
    device('shell', 'for p in /sys/class/net/*; do i=${p##*/}; [ "$i" = lo ] || ip link set dev "$i" down || exit 1; done')
    device('shell', 'input', 'keyevent', '224'); device('shell', 'input', 'keyevent', '82')
    device('shell', 'wm', 'dismiss-keyguard')
    for key in ('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale'):
        device('shell', 'settings', 'put', 'global', key, '0')
    device('shell', 'settings', 'put', 'system', 'font_scale', str(font))
    device('shell', 'setprop', 'persist.sys.locale', locale)
    device('install', '-r', str(project / 'app/build/outputs/apk/debug/app-debug.apk'))
    device('install', '-r', str(project / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
    permissions = ('READ_CONTACTS WRITE_CONTACTS READ_CALENDAR WRITE_CALENDAR ACCESS_COARSE_LOCATION '
        'ACCESS_FINE_LOCATION ACCESS_BACKGROUND_LOCATION CAMERA RECORD_AUDIO READ_PHONE_STATE '
        'READ_PHONE_NUMBERS CALL_PHONE READ_SMS SEND_SMS RECEIVE_SMS READ_CALL_LOG WRITE_CALL_LOG '
        'BLUETOOTH_CONNECT BLUETOOTH_SCAN READ_MEDIA_IMAGES READ_MEDIA_VIDEO READ_MEDIA_AUDIO '
        'READ_MEDIA_VISUAL_USER_SELECTED POST_NOTIFICATIONS ACTIVITY_RECOGNITION NEARBY_WIFI_DEVICES')
    for permission in permissions.split():
        device('shell', 'pm', 'grant', app, 'android.permission.' + permission)
    for op in ('GET_USAGE_STATS WRITE_SETTINGS SYSTEM_ALERT_WINDOW MANAGE_EXTERNAL_STORAGE '
               'SCHEDULE_EXACT_ALARM ACCESS_RESTRICTED_SETTINGS').split():
        device('shell', 'appops', 'set', app, op, 'allow')
    device('shell', 'cmd', 'notification', 'allow_listener', app + '/.phone.HoyoungNotificationListener')
    device('shell', 'cmd', 'notification', 'allow_dnd', app)
    device('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', app + '/.phone.HoyoungAccessibilityService')
    device('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1')
    device('shell', 'dumpsys', 'deviceidle', 'whitelist', '+' + app)
    device('shell', 'dpm', 'set-active-admin', app + '/.phone.HoyoungDeviceAdminReceiver')
    device('shell', 'am', 'start', '-W', '-n', app + '/.MainActivity')
    proof = instrument('offline', app + '.OfflineFixtureDeviceTest', 1, 90)
    if 'actual-activeNetwork=null; actual-getAllNetworks=0;' not in proof:
        raise RuntimeError('Framework offline proof missing')
    classes = [app + '.' + name for name in ('WorkCompatibilityExplorerDeviceTest',
        'SettingsExplorerDeviceTest', 'MediaExplorerDeviceTest', 'MemoryExplorerDeviceTest',
        'ExportRotationExplorerDeviceTest')]
    classes += [app + '.LifecycleExplorerDeviceTest#' + name for name in (
        'draftSurvivesScreenOffBackgroundRecreationAndActualReplyTap',
        'dismissedNotificationIsNotReadAndDoesNotReplay',
        'actualForegroundStopControlReleasesServiceAndPreservesDraft')]
    print('HOYOUNG_EXPLORER_PROFILE ' + json.dumps({'profile': args.profile, 'api': 35,
          'requestedPixels': [width, height], 'requestedFontScale': font, 'requestedLocale': locale,
          'configurationMustBeReadFromActualTestProof': True}), flush=True)
    if args.scope == 'work-recovery':
        instrument('work-recovery', app + '.WorkCompatibilityExplorerDeviceTest', 12, 360)
    else:
        instrument('product', ','.join(classes), 33, 720)
finally:
    if emulator is not None:
        try:
            diagnostics = device('logcat', '-d', '-v', 'brief', 'AndroidRuntime:E', 'HoyoungDeviceProof:I', '*:S', timeout=15)
            print(diagnostics[-24000:], flush=True)
        except Exception as failure:
            print('Device diagnostics unavailable: ' + str(failure), flush=True)
        emulator.terminate()
        try: emulator.wait(timeout=15)
        except subprocess.TimeoutExpired: emulator.kill()
