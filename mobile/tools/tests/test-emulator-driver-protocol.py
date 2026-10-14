#!/usr/bin/env python3
"""Regression for shell failure accounting only; never launches Android or Gradle."""
import os
import pathlib
import subprocess
import tempfile
import unittest


DRIVER = pathlib.Path(__file__).resolve().parents[1] / 'run-emulator-tests.sh'


def section(source, name):
    return source.split('# BEGIN ' + name + '\n', 1)[1].split('# END ' + name, 1)[0]


class EmulatorDriverProtocolTest(unittest.TestCase):
    def test_failed_commands_cannot_pass_and_later_boot_remains_reachable(self):
        source = DRIVER.read_text()
        helpers = section(source, 'INSTRUMENTATION PROOF DECODER') + section(source, 'RESTART PROTOCOL HELPERS')
        phase_driver = section(source, 'RESTART PHASE DRIVER')
        with tempfile.TemporaryDirectory(prefix='hoyoung-driver-protocol-') as temporary:
            directory = pathlib.Path(temporary)
            binaries = directory / 'bin'
            binaries.mkdir()
            adb = binaries / 'fixture-adb'
            adb.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
arguments = sys.argv[1:]
if arguments[:1] == ['-s']: arguments = arguments[2:]
state_path = pathlib.Path(os.environ['FAKE_ADB_STATE'])
state = json.loads(state_path.read_text()) if state_path.exists() else {
    'pid':100, 'fgs':os.environ.get('FAKE_ADB_NO_INITIAL_PROCESS') != 'true'}
command = ' '.join(arguments)
result = 0
if arguments[:3] == ['shell','am','instrument']:
    phase = arguments[arguments.index('restartPhase')+1]
    print('FIXTURE_INSTRUMENT_PHASE ' + phase)
    print('OK (1 test)')
    state['last_instrument_pid'] = state['pid']
    if phase == os.environ.get('FAKE_ADB_FAIL_PHASE'): result = 23
elif arguments[:3] == ['shell','am','start']:
    print('Status: ok')
    if os.environ.get('FAKE_ADB_START_FAILURE') == 'true': result = 24
    else: state['pid'] += 1; state['fgs'] = True
elif arguments[:3] == ['shell','am','force-stop']:
    state['fgs'] = False
elif arguments[:2] == ['shell','pidof']:
    if state['fgs']: print(state['pid'])
    else: result = 1
elif arguments[:4] == ['shell','dumpsys','activity','services']:
    if state['fgs']: print('.runtime.AutonomyService isForeground=true')
elif arguments[:2] == ['shell','run-as']:
    print('FIXTURE_PROCESS_SIGNAL')
    state['kill_count'] = state.get('kill_count', 0) + 1
    if state['kill_count'] == 2 and os.environ.get('FAKE_ADB_FAIL_CLEAN_BASELINE') == 'true':
        result = 25
    else:
        state['pid'] += 1; state['fgs'] = True
        if state['kill_count'] == 2: state['clean_baseline_pid'] = state['pid']
elif arguments[:3] == ['shell','am','crash']:
    print('FIXTURE_PROCESS_SIGNAL')
    requested = int(arguments[3])
    if requested != state.get('clean_baseline_pid') or requested == state.get('last_instrument_pid'):
        print('FIXTURE_JAVA_CRASH_UNCLEAN_PID'); result = 26
    else:
        print('FIXTURE_JAVA_CRASH_CLEAN_PID=' + str(requested))
        state['pid'] += 1; state['fgs'] = True
elif arguments == ['reboot']:
    print('FIXTURE_REBOOT_REACHED'); state['pid'] += 1; state['fgs'] = True
elif arguments[:2] == ['shell','getprop']:
    print('1')
state_path.write_text(json.dumps(state))
raise SystemExit(result)
''')
            timeout = binaries / 'timeout'
            timeout.write_text('''#!/usr/bin/env bash
set -euo pipefail
shift
if [[ "${FAKE_TIMEOUT_INSTRUMENT:-false}" == true && "$*" == *"am instrument"* ]]; then
  echo 'OK (1 test)'
  exit 124
fi
exec "$@"
''')
            sleep = binaries / 'sleep'
            sleep.write_text('#!/usr/bin/env bash\nexit 0\n')
            for executable in (adb, timeout, sleep):
                executable.chmod(0o755)
            environment = dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ['PATH'],
                               FAKE_ADB_STATE=str(directory / 'state.json'))
            prefix = 'set -euo pipefail\n' + helpers + '''
ADB="$FIXTURE_ADB"
SERIAL=fixture-only-no-device
APP_ID=com.hoyoung.assistant
TEST_RUNNER=fixture.runner
LOG_DIR="$FIXTURE_LOG_DIR"
primary_passed=true
explicit_recovery_passed=true
'''
            environment.update(FIXTURE_ADB=str(adb), FIXTURE_LOG_DIR=str(directory))

            def run(body, **variables):
                (directory / 'state.json').unlink(missing_ok=True)
                return subprocess.run(['bash', '-c', prefix + body], env=dict(environment, **variables),
                                      text=True, capture_output=True, timeout=10)

            for variables in ({'FAKE_TIMEOUT_INSTRUMENT': 'true'}, {'FAKE_ADB_FAIL_PHASE': 'seed'}):
                with self.subTest(variables=variables):
                    result = run('if run_restart_phase seed; then exit 0; else exit 9; fi\n', **variables)
                    self.assertEqual(9, result.returncode, result.stdout + result.stderr)
                    self.assertIn('OK (1 test)', result.stdout)
                    self.assertIn('command=false', result.stderr)

            result = run('if start_normal_app; then exit 0; else exit 9; fi\n', FAKE_ADB_START_FAILURE='true')
            self.assertEqual(9, result.returncode, result.stdout + result.stderr)
            self.assertIn('launch command failed', result.stderr)

            # Execute the actual driver's phase-control section with a fake ADB.
            # A misleading OK plus failing processDeath command must remain fatal,
            # while the actual later Java-crash and reboot branches are reached.
            result = run(phase_driver, FAKE_ADB_FAIL_PHASE='processDeath')
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn('phase=afterProcessDeathExplicitReopen', result.stdout)
            self.assertIn('FIXTURE_INSTRUMENT_PHASE crash', result.stdout)
            self.assertIn('FIXTURE_REBOOT_REACHED', result.stdout)
            self.assertIn('FIXTURE_INSTRUMENT_PHASE boot', result.stdout)
            self.assertIn('processDeath=false; javaCrash=true; boot=true', result.stdout)
            self.assertIn('HOYOUNG_JAVA_CLEAN_BASELINE_READY', result.stdout)
            self.assertIn('FIXTURE_JAVA_CRASH_CLEAN_PID=', result.stdout)
            self.assertNotIn('FIXTURE_JAVA_CRASH_UNCLEAN_PID', result.stdout)

            result = run(phase_driver, FAKE_ADB_FAIL_CLEAN_BASELINE='true')
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertNotIn('FIXTURE_JAVA_CRASH_CLEAN_PID=', result.stdout)
            self.assertNotIn('FIXTURE_JAVA_CRASH_UNCLEAN_PID', result.stdout)
            self.assertIn('FIXTURE_REBOOT_REACHED', result.stdout)
            self.assertIn('javaCrash=false; boot=true', result.stdout)
            self.assertIn('javaCleanBaseline=false', result.stdout)

            result = run(phase_driver, FAKE_ADB_START_FAILURE='true', FAKE_ADB_NO_INITIAL_PROCESS='true')
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertNotIn('FIXTURE_PROCESS_SIGNAL', result.stdout)
            self.assertIn('FIXTURE_REBOOT_REACHED', result.stdout)
            self.assertIn('boot=true; bootstrapAndProofInfrastructure=false', result.stdout)


if __name__ == '__main__':
    unittest.main()
