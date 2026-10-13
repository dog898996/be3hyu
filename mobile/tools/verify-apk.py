#!/usr/bin/env python3
"""Verify public APK identity, signature, manifest permissions, and native packaging."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import ssl
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile


def require(condition, message):
    if not condition:
        raise ValueError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--release', action='store_true', help='Require the existing public release certificate and a non-debuggable APK.')
    parser.add_argument('--output', type=Path, help='Optional public JSON verification report; never reads a private keystore.')
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    tools = Path(os.environ.get('HOYOUNG_ANDROID_TOOLS_DIR', '/workspace/.tools/android-build'))
    build_tools = tools / 'sdk/build-tools/35.0.0'
    jdk = tools / 'jdk-17.0.16+8'
    apk = args.apk.resolve()
    require(apk.is_file(), 'The APK does not exist.')
    environment = dict(os.environ, JAVA_HOME=str(jdk))
    environment['PATH'] = str(jdk / 'bin') + os.pathsep + environment.get('PATH', '')

    def execute(command):
        return subprocess.run(command, check=True, text=True, capture_output=True, env=environment).stdout

    badging = execute([str(build_tools / 'aapt2'), 'dump', 'badging', str(apk)])
    permissions_dump = execute([str(build_tools / 'aapt2'), 'dump', 'permissions', str(apk)])
    signing = execute([str(build_tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)])
    package_line = next(line for line in badging.splitlines() if line.startswith('package:'))
    fields = dict(item.split('=', 1) for item in shlex.split(package_line)[1:])
    gradle = (project / 'app/build.gradle').read_text()
    expected_id = re.search(r"applicationId\s+'([^']+)'", gradle).group(1)
    expected_version = re.search(r"versionName\s+'([^']+)'", gradle).group(1)
    expected_code = re.search(r'versionCode\s+(\d+)', gradle).group(1)
    expected_target = re.search(r'targetSdk\s+(\d+)', gradle).group(1)
    require(fields['name'] == expected_id, 'APK application ID differs from its source.')
    require(fields['versionName'] == expected_version and fields['versionCode'] == expected_code, 'APK version differs from its source.')
    target = re.search(r"^targetSdkVersion:'(\d+)'", badging, re.MULTILINE).group(1)
    require(target == expected_target, 'APK target API differs from its source.')
    debuggable = 'application-debuggable' in badging.splitlines()
    require(not args.release or not debuggable, 'A release APK must not be debuggable.')

    namespace = '{http://schemas.android.com/apk/res/android}'
    manifest = ET.parse(project / 'app/src/main/AndroidManifest.xml').getroot()
    expected_permissions = sorted((item.get(namespace + 'name'), item.get(namespace + 'maxSdkVersion', ''),
                                   item.get(namespace + 'usesPermissionFlags', '')) for item in manifest.findall('uses-permission'))
    actual_permissions = []
    for line in permissions_dump.splitlines():
        if line.startswith('uses-permission:'):
            attributes = dict(item.split('=', 1) for item in shlex.split(line)[1:])
            actual_permissions.append((attributes['name'], attributes.get('maxSdkVersion', ''), attributes.get('usesPermissionFlags', '')))
    require(sorted(actual_permissions) == expected_permissions, 'APK permission declarations differ from the complete source manifest.')
    certs = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$', signing, re.MULTILINE)
    require(len(certs) == 1, 'The APK must have exactly one verified signing identity.')
    cert_sha = certs[0].lower()
    if args.release:
        public_pem = (project / 'signing/hoyoung-release-cert.pem').read_text()
        public_sha = hashlib.sha256(ssl.PEM_cert_to_DER_cert(public_pem)).hexdigest()
        declared_sha = (project / 'signing/hoyoung-release-cert.sha256').read_text().strip().lower()
        require(cert_sha == public_sha == declared_sha, 'Release signer differs from the existing public certificate; this is not an update signed by the stable key.')
        require('Verified using v2 scheme (APK Signature Scheme v2): true' in signing, 'Release V2 signature verification failed.')
        require('Verified using v3 scheme (APK Signature Scheme v3): true' in signing, 'Release V3 signature verification failed.')

    libraries = []
    with zipfile.ZipFile(apk) as archive, apk.open('rb') as raw:
        require(not any(Path(item.filename).name in ('transformer.onnx', 'dense.safetensors', 'vocab.txt') for item in archive.infolist()),
                'The separately downloaded model weights must not be bundled in the APK.')
        for info in archive.infolist():
            if not info.filename.startswith('lib/') or not info.filename.endswith('.so'):
                continue
            data = archive.read(info)
            require(data[:6] == b'\x7fELF\x02\x01', 'Native libraries must be little-endian ELF64 for the two supported ABIs.')
            offset = struct.unpack_from('<Q', data, 32)[0]
            size, number = struct.unpack_from('<HH', data, 54)
            alignments = [struct.unpack_from('<Q', data, offset + index * size + 48)[0] for index in range(number)
                          if struct.unpack_from('<I', data, offset + index * size)[0] == 1]
            require(alignments and all(alignment >= 16384 for alignment in alignments), 'Native ELF alignment is incompatible with 16 KiB pages.')
            raw.seek(info.header_offset + 26)
            name_size, extra_size = struct.unpack('<HH', raw.read(4))
            data_offset = info.header_offset + 30 + name_size + extra_size
            require(info.compress_type == 0 and data_offset % 16384 == 0, 'Native libraries must be uncompressed and ZIP-aligned for 16 KiB pages.')
            libraries.append({'name': info.filename, 'bytes': info.file_size, 'elfLoadAlignments': alignments, 'zip16KiBAligned': True})
    required = {'lib/' + abi + '/' + name for abi in ('arm64-v8a', 'x86_64')
                for name in ('libonnxruntime.so', 'libonnxruntime4j_jni.so', 'libhoyoung_ann.so')}
    require({item['name'] for item in libraries} == required, 'Both supported ABIs must contain the real ONNX and ANN native libraries.')
    label = re.search(r"^application-label:'([^']*)'", badging, re.MULTILINE).group(1)
    with apk.open('rb') as stream:
        apk_sha = hashlib.file_digest(stream, 'sha256').hexdigest()
    report = {'scope': 'Static APK signature, source identity, complete manifest permissions, and native packaging only.',
              'applicationId': fields['name'], 'label': label, 'versionName': fields['versionName'], 'versionCode': int(fields['versionCode']),
              'targetSdk': int(target), 'debuggable': debuggable, 'releaseIdentityVerified': args.release,
              'certificateSha256': cert_sha, 'apkSha256': apk_sha, 'apkBytes': apk.stat().st_size,
              'sourcePermissionDeclarationsExactlyMatch': True, 'permissionCount': len(actual_permissions),
              'permissions': [{'name': name, 'maxSdkVersion': cap or None, 'flags': flags or None} for name, cap, flags in sorted(actual_permissions)],
              'nativeLibraries': libraries, 'modelWeightsBundled': False,
              'physicalPhoneInstallationMeasured': False, 'realChatGPTAccountMeasured': False, 'continuous24HourRuntimeMeasured': False}
    content = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content)
    print(content, end='')


if __name__ == '__main__':
    main()
