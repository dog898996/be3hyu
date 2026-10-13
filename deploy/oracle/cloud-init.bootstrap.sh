#!/usr/bin/env bash
set +x
set -Eeuo pipefail
umask 077

BOOT_DIR=/var/lib/hoyoung-bootstrap
install -d -m 0700 "$BOOT_DIR"
install -m 0600 /dev/null /var/log/hoyoung-bootstrap.log
exec >>/var/log/hoyoung-bootstrap.log 2>&1
trap 'printf "Bootstrap failed at line %s; private input values were not printed.\n" "$LINENO"' ERR

python3 - "$BOOT_DIR" <<'PY'
import json, pathlib, sys
root = pathlib.Path(sys.argv[1])
url = (root / 'source-url').read_text().strip()
# The URL is kept in a private curl config, not in argv or shell tracing.
(root / 'curl.conf').write_text('url = ' + json.dumps(url) + '\n')
(root / 'curl.conf').chmod(0o600)
PY
trap 'rm -f "$BOOT_DIR/curl.conf" "$BOOT_DIR/source-url"' EXIT
curl --config "$BOOT_DIR/curl.conf" --fail --silent --show-error \
  --proto '=https' --tlsv1.2 --retry 5 --retry-delay 2 --connect-timeout 15 --max-time 180 \
  --output "$BOOT_DIR/source.tar.gz"

python3 - "$BOOT_DIR" <<'PY'
import hashlib, json, pathlib, tarfile, sys
root = pathlib.Path(sys.argv[1])
settings = json.loads((root / 'settings.json').read_text())
archive = root / 'source.tar.gz'
if hashlib.file_digest(archive.open('rb'), 'sha256').hexdigest() != settings['sourceSha256']:
    raise SystemExit('Source archive SHA-256 verification failed.')
destination = root / 'source'
destination.mkdir(mode=0o700, exist_ok=True)
with tarfile.open(archive, 'r:gz') as bundle:
    members = bundle.getmembers()
    if sum(member.size for member in members) > 256 * 1024 * 1024:
        raise SystemExit('Source archive is too large; supply a source-only bundle.')
    for member in members:
        name = pathlib.PurePosixPath(member.name)
        if name.is_absolute() or '..' in name.parts or not (member.isfile() or member.isdir()):
            raise SystemExit('Unsafe source archive entry rejected.')
    bundle.extractall(destination, members=members, filter='data')
PY

python3 - "$BOOT_DIR" <<'PY'
import json, pathlib, subprocess, sys, time, urllib.request
root = pathlib.Path(sys.argv[1])
settings = json.loads((root / 'settings.json').read_text())
extracted = root / 'source'
candidates = [extracted] + [path for path in extracted.iterdir() if path.is_dir()]
candidates = [path for path in candidates if (path / 'Dockerfile').is_file() and (path / 'deploy/oracle/install.sh').is_file()]
if len(candidates) != 1:
    raise SystemExit('Source bundle must contain one repository root, directly or in one wrapper directory.')
source = candidates[0]
required = [
    'Dockerfile', '.dockerignore', 'package.json', 'package-lock.json',
    'src/server.mjs', 'src/runtime.mjs', 'src/store.mjs', 'src/tools.mjs',
    'src/extensions.mjs', 'src/model.mjs', 'src/model-login.mjs',
    'src/channels.mjs', 'src/defaults.mjs',
    'public/index.html', 'public/app.js', 'public/styles.css',
    'deploy/run.mjs', 'deploy/codex-storage.mjs', 'deploy/r2-backup.mjs',
    'deploy/snapshot-format.mjs', 'deploy/oracle/install.sh',
    'deploy/oracle/compose.yaml', 'deploy/oracle/Caddyfile.template',
    'docs/source-transcript.txt', 'docs/latest-requirements.json',
]
missing = [name for name in required if not (source / name).is_file()]
if missing:
    raise SystemExit('Source bundle is incomplete: ' + ', '.join(missing))
command = ['bash', str(source / 'deploy/oracle/install.sh'), '--source', str(source), '--install-dir', settings['installDir']]
if settings.get('hostname'):
    command.extend(['--hostname', settings['hostname']])
if settings.get('publicIp'):
    command.extend(['--public-ip', settings['publicIp']])
# Existing installer verifies Ubuntu24 ARM64 and the local free-size target.
# Tenancy aggregate quotas must already have been reviewed in the owner console.
subprocess.run(command, check=True)
# An installer exit is not proof that the application started. Local health is
# public and requires no token; bypass proxy environment variables explicitly.
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
deadline = time.monotonic() + 120
while True:
    try:
        with opener.open('http://127.0.0.1:8080/api/health', timeout=3) as response:
            health = json.loads(response.read(16384))
            if response.status == 200 and health.get('ok') is True and health.get('service') == 'autonomous-assistant':
                break
    except Exception:
        pass
    if time.monotonic() >= deadline:
        raise SystemExit('Application health did not become ready; bootstrap success was not recorded.')
    time.sleep(2)
(pathlib.Path(settings['installDir']) / '.bootstrap-success').write_text('Cloud-init source bootstrap completed.\n')
PY
printf '%s\n' 'Source bootstrap completed; inspect the application HTTPS health endpoint.'
