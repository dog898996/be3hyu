#!/usr/bin/env bash
# Build a transparent personal-app release with one stable private signing key.
# Usage: build-release.sh [--prepare-signing] [mobile-project-directory] [tasks...]
# The private PKCS12 key and its random passwords are never stored in Git.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PREPARE_ONLY=false
if [[ "${1:-}" == --prepare-signing ]]; then
  PREPARE_ONLY=true
  shift
fi
PROJECT_DIR="${1:-$(dirname -- "$SCRIPT_DIR")}"
if [[ $# -gt 0 ]]; then shift; fi
if [[ $# -eq 0 ]]; then set -- assembleRelease; fi
"$SCRIPT_DIR/build-android.sh" --prepare "$PROJECT_DIR"

exec python3 - "$SCRIPT_DIR" "$PROJECT_DIR" "$PREPARE_ONLY" "$@" <<'PY'
import base64
import datetime
import fcntl
import hashlib
import json
import os
import pathlib
import secrets
import subprocess
import sys
import tempfile

script_dir = pathlib.Path(sys.argv[1]).resolve()
project_dir = pathlib.Path(sys.argv[2]).resolve()
prepare_only = sys.argv[3] == 'true'
tasks = sys.argv[4:]
repository_dir = script_dir.parent.parent.resolve()
tools_dir = pathlib.Path(os.environ.get('HOYOUNG_ANDROID_TOOLS_DIR', '/workspace/.tools/android-build')).resolve()
keytool = tools_dir / 'jdk-17.0.16+8' / 'bin' / 'keytool'
signing_dir = pathlib.Path(os.environ.get('HOYOUNG_SIGNING_DIR', '/workspace/.credentials/hoyoung-signing')).resolve()
if signing_dir == repository_dir or repository_dir in signing_dir.parents:
    raise SystemExit('Private signing directory must be outside the source repository.')

signing_dir.mkdir(parents=True, mode=0o700, exist_ok=True)
os.chmod(signing_dir, 0o700)
manifest_path = signing_dir / 'signing.json'
keystore_path = signing_dir / 'hoyoung-release.p12'
public_certificate_path = project_dir / 'signing' / 'hoyoung-release-cert.pem'
public_fingerprint_path = project_dir / 'signing' / 'hoyoung-release-cert.sha256'

def run_keytool(arguments, signing_env):
    # Password arguments contain only environment VARIABLE NAMES, never values.
    return subprocess.run([str(keytool), *arguments], env=signing_env, check=True)

with (signing_dir / '.signing.lock').open('a') as lock:
    os.chmod(signing_dir / '.signing.lock', 0o600)
    fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
    if manifest_path.exists():
        with manifest_path.open() as stream:
            manifest = json.load(stream)
        if manifest.get('version') != 1 or pathlib.Path(manifest.get('keystore', '')).resolve() != keystore_path:
            raise SystemExit('Unexpected signing manifest; restore the original signing manifest instead of generating a replacement key.')
        if not keystore_path.is_file():
            raise SystemExit('Existing signing key is missing. Restore the original private key to preserve future app updates.')
        for name in ('alias', 'storePassword', 'keyPassword'):
            if not isinstance(manifest.get(name), str) or not manifest[name]:
                raise SystemExit('Incomplete private signing manifest; restore it instead of changing the app signer.')
        print('Reusing the existing private release signing key.')
    else:
        if keystore_path.exists():
            raise SystemExit('Existing signing key has no manifest. Restore its original manifest instead of replacing the key.')
        if public_certificate_path.exists():
            raise SystemExit('This checkout already has a release certificate. Restore its original private signing key; a replacement signer cannot update installed releases.')
        password = secrets.token_urlsafe(48)
        manifest = {
            'version': 1,
            'keystore': str(keystore_path),
            'alias': 'hoyoung-release',
            'storePassword': password,
            'keyPassword': password,
            'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat()
        }
        signing_env = dict(os.environ,
            HOYOUNG_SIGNING_STORE_PASSWORD=manifest['storePassword'],
            HOYOUNG_SIGNING_KEY_PASSWORD=manifest['keyPassword'])
        old_mask = os.umask(0o077)
        try:
            run_keytool([
                '-genkeypair', '-storetype', 'PKCS12', '-keystore', str(keystore_path),
                '-alias', manifest['alias'], '-keyalg', 'RSA', '-keysize', '3072',
                '-validity', '10000', '-dname', 'CN=Hoyoung Personal Assistant, OU=Personal App, O=Hoyoung',
                '-storepass:env', 'HOYOUNG_SIGNING_STORE_PASSWORD',
                '-keypass:env', 'HOYOUNG_SIGNING_KEY_PASSWORD', '-noprompt'
            ], signing_env)
            with manifest_path.open('x') as stream:
                json.dump(manifest, stream, indent=2)
                stream.write('\n')
        finally:
            os.umask(old_mask)
        print('Created a stable private release signing key outside the repository.')

    os.chmod(keystore_path, 0o600)
    os.chmod(manifest_path, 0o600)
    signing_env = dict(os.environ,
        HOYOUNG_SIGNING_KEYSTORE=str(keystore_path),
        HOYOUNG_SIGNING_ALIAS=manifest['alias'],
        HOYOUNG_SIGNING_STORE_PASSWORD=manifest['storePassword'],
        HOYOUNG_SIGNING_KEY_PASSWORD=manifest['keyPassword'])
    public_certificate_path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='certificate-', dir=signing_dir) as temporary_dir:
        temporary_certificate = pathlib.Path(temporary_dir) / 'release-cert.pem'
        run_keytool([
            '-exportcert', '-rfc', '-keystore', str(keystore_path),
            '-alias', manifest['alias'], '-file', str(temporary_certificate),
            '-storepass:env', 'HOYOUNG_SIGNING_STORE_PASSWORD'
        ], signing_env)
        certificate_pem = temporary_certificate.read_text()
        der = base64.b64decode(''.join(line for line in certificate_pem.splitlines() if not line.startswith('-----')))
        fingerprint = hashlib.sha256(der).hexdigest()
        if public_certificate_path.exists() and public_certificate_path.read_text() != certificate_pem:
            raise SystemExit('Public release certificate does not match the private signer. Preserve the original key/certificate pair.')
        public_certificate_path.write_text(certificate_pem)
        public_fingerprint_path.write_text(fingerprint + '\n')
    print('Public release certificate SHA256:', fingerprint)
    print('Public certificate:', public_certificate_path)

if prepare_only:
    raise SystemExit(0)
result = subprocess.run([str(script_dir / 'build-android.sh'), str(project_dir), *tasks], env=signing_env)
raise SystemExit(result.returncode)
PY
