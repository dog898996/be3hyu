import test from 'node:test';
import assert from 'node:assert/strict';
import { chmod, mkdir, mkdtemp, readFile, writeFile, stat, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createCloudInit, writeCloudInit, validateSourceUrl, MAX_USER_DATA_BYTES } from '../deploy/oracle/cloud-init.mjs';

const sourceUrl = 'https://objectstorage.ap-osaka-1.oraclecloud.com/p/fixture-par/n/fixture/b/private-source/o/source.tar.gz';
const sourceSha256 = 'a'.repeat(64), adminToken = 'fixture'.repeat(8);

test('OCI source PAR validation accepts published endpoint formats and rejects unsafe alternatives', () => {
  assert.equal(validateSourceUrl(sourceUrl), sourceUrl);
  assert.equal(validateSourceUrl(sourceUrl.replace('objectstorage.ap-osaka-1.oraclecloud.com', 'fixture.objectstorage.ap-osaka-1.oci.customer-oci.com')).startsWith('https://fixture.'), true);
  for (const url of [sourceUrl.replace('https:', 'http:'), sourceUrl.replace('.oraclecloud.com', '.oraclecloud.com.evil.example'), sourceUrl + '?token=wrong', sourceUrl + '#fragment', sourceUrl.replace('https://', 'https://owner:password@'), 'https://objectstorage.ap-osaka-1.oraclecloud.com/not-a-par']) {
    assert.throws(() => validateSourceUrl(url));
  }
});

test('small cloud-init carries private files and invokes installer without exposing source URL/token in argv', async () => {
  const output = await createCloudInit({ sourceUrl, sourceSha256, adminToken, hostname: 'hoyoung.example.com' });
  assert.ok(Buffer.byteLength(output) < MAX_USER_DATA_BYTES);
  assert.equal(output.includes(sourceUrl), false);
  assert.equal(output.includes(adminToken), false);
  const config = JSON.parse(output.slice('#cloud-config\n'.length));
  assert.deepEqual(config.runcmd, [['bash', '/usr/local/sbin/hoyoung-bootstrap']]);
  const file = path => config.write_files.find(file => file.path === path);
  assert.equal(file('/opt/hoyoung/.env').permissions, '0600');
  assert.equal(Buffer.from(file('/opt/hoyoung/.env').content, 'base64').toString(), `APP_AUTH_TOKEN=${adminToken}\n`);
  const bootstrap = Buffer.from(file('/usr/local/sbin/hoyoung-bootstrap').content, 'base64').toString();
  assert.ok(bootstrap.includes('curl --config'));
  execFileSync('bash', ['-n'], { input: bootstrap });
  await assert.rejects(createCloudInit({ sourceUrl, sourceSha256, adminToken: 'a'.repeat(MAX_USER_DATA_BYTES) }), /raw limit/);
});

test('first-boot source validation accepts one complete root and records success only after genuine app health', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'cloud-init-stage-fixture-'));
  try {
    const bootstrap = await readFile(new URL('../deploy/oracle/cloud-init.bootstrap.sh', import.meta.url), 'utf8');
    const blocks = [...bootstrap.matchAll(/python3 - "\$BOOT_DIR" <<'PY'\n([\s\S]*?)\nPY/g)];
    const stagePath = join(directory, 'install-stage.py');
    await writeFile(stagePath, blocks[2][1]);
    const harness = String.raw`
import ast, json, pathlib, subprocess, sys, time, urllib.request
stage_path, fixture_root, kind = sys.argv[1:]
stage = pathlib.Path(stage_path).read_text()
required = next(ast.literal_eval(node.value) for node in ast.parse(stage).body if isinstance(node, ast.Assign) and any(isinstance(target, ast.Name) and target.id == 'required' for target in node.targets))
root = pathlib.Path(fixture_root)
root.mkdir()
extracted = root / 'source'
extracted.mkdir()
if kind == 'wrapper':
    sources = [extracted / 'repository']
elif kind == 'ambiguous':
    sources = [extracted / 'one', extracted / 'two']
else:
    sources = [extracted]
for source in sources:
    for name in required:
        path = source / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('fixture source')
if kind == 'missing-evidence':
    (sources[0] / 'docs/latest-requirements.json').unlink()
elif kind == 'missing-module':
    (sources[0] / 'deploy/codex-storage.mjs').unlink()
installation = root / 'installation'
installation.mkdir()
(installation / '.env').write_text('APP_AUTH_TOKEN=private-fixture-not-in-process-arguments\n')
(root / 'settings.json').write_text(json.dumps({'installDir':str(installation),'hostname':'hoyoung.example.com','publicIp':''}))
commands, requests = [], []
subprocess.run = lambda command, check: commands.append(command)
clock = [0]
time.monotonic = lambda: clock[0]
time.sleep = lambda seconds: clock.__setitem__(0, clock[0] + seconds)
class HealthResponse:
    status = 200
    def __enter__(self): return self
    def __exit__(self, *args): pass
    def read(self, maximum):
        assert maximum == 16384
        return json.dumps({'ok': True, 'service': 'unrelated-server' if kind == 'unhealthy' else 'autonomous-assistant'}).encode()
class HealthOpener:
    def open(self, url, timeout):
        requests.append(url)
        assert url == 'http://127.0.0.1:8080/api/health' and timeout == 3
        if len(requests) == 1:
            raise OSError('fixture: app is still starting')
        return HealthResponse()
def opener(handler):
    assert handler.proxies == {}, 'local health must bypass inherited proxy settings'
    return HealthOpener()
urllib.request.build_opener = opener
sys.argv = ['bootstrap', str(root)]
try:
    exec(compile(stage, stage_path, 'exec'))
except SystemExit as error:
    if kind in ['missing-evidence','missing-module']:
        assert 'Source bundle is incomplete' in str(error)
        assert not commands and not requests
    elif kind == 'ambiguous':
        assert 'one repository root' in str(error)
        assert not commands and not requests
    elif kind == 'unhealthy':
        assert 'Application health did not become ready' in str(error)
        assert len(commands) == 1 and clock[0] >= 120
    else:
        raise
    assert not (installation / '.bootstrap-success').exists()
else:
    assert kind in ['direct','wrapper']
    assert len(commands) == 1 and len(requests) == 2
    assert commands[0][0] == 'bash'
    assert commands[0][1] == str(sources[0] / 'deploy/oracle/install.sh')
    assert '--source' in commands[0] and '--install-dir' in commands[0]
    assert (installation / '.bootstrap-success').is_file()
    assert 'private-fixture' not in json.dumps(commands)
`;
    for (const kind of ['direct', 'wrapper', 'missing-evidence', 'missing-module', 'ambiguous', 'unhealthy']) {
      execFileSync('python3', ['-c', harness, stagePath, join(directory, kind), kind], { stdio: 'pipe' });
    }
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test('bootstrap verification actually checks SHA-256 and rejects traversal/symlink archives before extraction', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'cloud-init-archive-fixture-'));
  try {
    const bootstrap = await readFile(new URL('../deploy/oracle/cloud-init.bootstrap.sh', import.meta.url), 'utf8');
    const blocks = [...bootstrap.matchAll(/python3 - "\$BOOT_DIR" <<'PY'\n([\s\S]*?)\nPY/g)];
    const verify = blocks[1][1];
    const archive = join(directory, 'source.tar.gz');
    const makeArchive = `import io,sys,tarfile\nkind=sys.argv[2]\nwith tarfile.open(sys.argv[1],'w:gz') as bundle:\n entry=tarfile.TarInfo('../outside.txt' if kind=='traversal' else 'readme.txt')\n if kind=='symlink':\n  entry.type=tarfile.SYMTYPE;entry.linkname='/etc/passwd';bundle.addfile(entry)\n else:\n  value=b'fixture source';entry.size=len(value);bundle.addfile(entry,io.BytesIO(value))\n`;
    const prepare = async kind => {
      await rm(join(directory, 'source'), { recursive: true, force: true });
      execFileSync('python3', ['-c', makeArchive, archive, kind]);
      const sha = createHash('sha256').update(await readFile(archive)).digest('hex');
      await writeFile(join(directory, 'settings.json'), JSON.stringify({ sourceSha256: sha }));
    };
    await prepare('safe');
    execFileSync('python3', ['-c', verify, directory]);
    assert.equal(await readFile(join(directory, 'source/readme.txt'), 'utf8'), 'fixture source');
    for (const kind of ['traversal', 'symlink']) {
      await prepare(kind);
      assert.throws(() => execFileSync('python3', ['-c', verify, directory], { stdio: 'pipe' }), /Unsafe source archive entry rejected/);
    }
    await prepare('safe');
    await writeFile(join(directory, 'settings.json'), JSON.stringify({ sourceSha256: '0'.repeat(64) }));
    assert.throws(() => execFileSync('python3', ['-c', verify, directory], { stdio: 'pipe' }), /SHA-256 verification failed/);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test('generated artifacts require private external files and mode0600 without overwriting keys', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'cloud-init-fixture-'));
  try {
    const urlFile = join(directory, 'source.par-url');
    await writeFile(urlFile, sourceUrl, { mode: 0o600 });
    const out = join(directory, 'user-data.yaml');
    const result = await writeCloudInit({ sourceUrlFile: urlFile, sourceSha256, out });
    assert.equal((await stat(result.outputPath)).mode & 0o777, 0o600);
    assert.equal((await stat(result.tokenPath)).mode & 0o777, 0o600);
    assert.equal((await readFile(result.tokenPath, 'utf8')).trim().length, 64);
    await assert.rejects(writeCloudInit({ sourceUrlFile: urlFile, sourceSha256, out }), /EEXIST/);
    await assert.rejects(writeCloudInit({ sourceUrlFile: urlFile, sourceSha256, out: new URL('../private-user-data.yaml', import.meta.url).pathname }), /outside the source repository/);
    const publicFile = join(directory, 'public-url'); await writeFile(publicFile, sourceUrl, { mode: 0o644 });
    await chmod(publicFile, 0o644);
    await assert.rejects(writeCloudInit({ sourceUrlFile: publicFile, sourceSha256, out: join(directory, 'rejected.yaml') }), /group\/other permissions/);
    const unrelated = join(directory, 'unrelated-repo');
    await mkdir(join(unrelated, '.git'), { recursive: true });
    await assert.rejects(writeCloudInit({ sourceUrlFile: urlFile, sourceSha256, out: join(unrelated, 'user-data.yaml') }), /outside Git checkouts/);
  } finally { await rm(directory, { recursive: true, force: true }); }
});
