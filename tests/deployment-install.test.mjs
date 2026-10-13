import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { execFileSync } from 'node:child_process';

const installerPath = new URL('../deploy/oracle/install.sh', import.meta.url).pathname;

test('Oracle installer rejects missing memory seeds and startup helpers before host changes', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'oracle-install-source-'));
  try {
    const installer = await readFile(installerPath, 'utf8');
    execFileSync('bash', ['-n', installerPath]);
    const validation = installer.match(/required_source_files=\([\s\S]*?\n\)\nfor file[\s\S]*?\ndone/)[0];
    const files = validation.match(/required_source_files=\(([\s\S]*?)\n\)/)[1].trim().split(/\s+/);
    for (const file of files) {
      const path = join(directory, file);
      await mkdir(dirname(path), { recursive: true });
      await writeFile(path, 'fixture');
    }
    const mustExist = ['docs/source-transcript.txt', 'docs/latest-requirements.json', 'deploy/run.mjs', 'deploy/codex-storage.mjs', 'deploy/r2-backup.mjs', 'deploy/snapshot-format.mjs', 'src/model-login.mjs', 'src/extensions.mjs', 'public/app.js'];
    for (const missing of mustExist) {
      assert.ok(files.includes(missing), `${missing} must be an installation prerequisite`);
      await rm(join(directory, missing));
      assert.throws(() => execFileSync('bash', [installerPath, '--check', '--source', directory, '--install-dir', '/opt/hoyoung-fixture'], { stdio: 'pipe' }), error => {
        assert.equal(error.status, 1);
        assert.ok(error.stderr.toString().includes(`Source checkout is missing ${missing}`));
        return true;
      });
      await writeFile(join(directory, missing), 'fixture');
    }
    execFileSync('bash', ['-c', `set -Eeuo pipefail\ndie() { exit 1; }\nPROJECT_SOURCE_DIR="$1"\n${validation}`, 'fixture', directory]);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test('Oracle installer readiness uses real HTTP, verifies service, and records private bounded status', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'oracle-install-health-'));
  try {
    const installer = await readFile(installerPath, 'utf8');
    const healthStage = installer.match(/<<'PY_HEALTH'\n([\s\S]*?)\nPY_HEALTH/)[1];
    const stagePath = join(directory, 'health.py');
    await writeFile(stagePath, healthStage);
    const harness = String.raw`
import http.server, json, os, pathlib, signal, sys, threading, time
stage_path, directory, kind = sys.argv[1:]
root = pathlib.Path(directory)
root.mkdir()
requests = []
class Health(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        requests.append(self.path)
        assert self.path == '/api/health'
        if len(requests) == 1:
            self.send_response(503)
            self.end_headers()
            return
        self.send_response(200)
        self.end_headers()
        if kind == 'malformed':
            self.wfile.write(b'not-json')
            return
        self.wfile.write(json.dumps({'ok': 'true' if kind == 'wrong-ok' else True, 'service': 'unrelated-server' if kind == 'wrong-service' else 'autonomous-assistant'}).encode())
    def log_message(self, *args): pass
server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Health)
threading.Thread(target=server.serve_forever, daemon=True).start()
stage = pathlib.Path(stage_path).read_text().replace('http://127.0.0.1:8080/api/health', 'http://127.0.0.1:%s/api/health' % server.server_port)
os.environ['http_proxy'] = 'http://127.0.0.1:9'
os.environ['HTTP_PROXY'] = 'http://127.0.0.1:9'
os.environ['NO_PROXY'] = ''
clock, alarms = [0], []
time.monotonic = lambda: clock[0]
time.sleep = lambda seconds: clock.__setitem__(0, clock[0] + seconds)
signal.setitimer = lambda which, seconds: alarms.append(seconds)
sys.argv = ['health', str(root)]
try:
    exec(compile(stage, stage_path, 'exec'))
except SystemExit as error:
    assert kind != 'healthy'
    assert 'Application health did not become ready' in str(error)
    assert clock[0] == 120
else:
    assert kind == 'healthy'
    assert len(requests) == 2
status = json.loads((root / '.install-status.json').read_text())
assert status['state'] == ('ready' if kind == 'healthy' else 'failed')
assert status['service'] == 'autonomous-assistant' and status['check'] == 'local-http'
assert (root / '.install-status.json').stat().st_mode & 0o777 == 0o600
assert alarms == [120, 0]
assert not (root / '.install-status.json.tmp').exists()
server.shutdown()
`;
    for (const kind of ['healthy', 'wrong-service', 'wrong-ok', 'malformed']) {
      execFileSync('python3', ['-c', harness, stagePath, join(directory, kind), kind], { stdio: 'pipe' });
    }
  } finally { await rm(directory, { recursive: true, force: true }); }
});
