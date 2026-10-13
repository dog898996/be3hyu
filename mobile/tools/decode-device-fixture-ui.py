#!/usr/bin/env python3
"""Decode chunked UI diagnostics emitted only by the wiped, offline AVD tests.

Usage: decode-device-fixture-ui.py /path/to/github-job.log --output /outside/repo/path
"""
import argparse
import base64
import json
import pathlib
import re


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('log', type=pathlib.Path)
    parser.add_argument('--output', required=True, type=pathlib.Path)
    args = parser.parse_args()
    if args.log.stat().st_size > 64 * 1024 * 1024:
        raise SystemExit('Expected one bounded CI job log, at most 64 MiB.')
    # GitHub timestamps and Android Logcat prefixes may precede these markers.
    expression = re.compile(r'(HOYOUNG_UI_FAILURE_DIAG|HOYOUNG_UI_FAILURE_JPEG_BASE64) ([A-Za-z0-9_-]{1,200}) (\d+)/(\d+) (.*)')
    records = {}
    for line in args.log.read_text(encoding='utf-8', errors='replace').splitlines():
        found = expression.search(line)
        if found is None:
            continue
        kind, test, part, count, payload = found.groups()
        part, count = int(part), int(count)
        if count < 1 or count > 4096 or part >= count or len(payload) > 2000:
            raise SystemExit('Invalid bounded diagnostic chunk metadata.')
        value = records.setdefault((kind, test), {'count': count, 'parts': {}})
        if count != value['count'] or (part in value['parts'] and value['parts'][part] != payload):
            raise SystemExit('Conflicting diagnostic chunks; use one complete job log.')
        value['parts'][part] = payload
    args.output.mkdir(parents=True, exist_ok=True)
    written = []
    for (kind, test), value in sorted(records.items()):
        if len(value['parts']) != value['count']:
            print('Incomplete diagnostics:', test, kind, f"{len(value['parts'])}/{value['count']}")
            continue
        payload = ''.join(value['parts'][part] for part in range(value['count']))
        if kind.endswith('JPEG_BASE64'):
            body = base64.b64decode(payload, validate=True)
            if not body.startswith(b'\xff\xd8') or not body.endswith(b'\xff\xd9') or len(body) > 5 * 1024 * 1024:
                raise SystemExit('Expected a bounded Android fixture JPEG.')
            target = args.output / (test + '.jpg')
            target.write_bytes(body)
        else:
            decoded = json.loads(payload)
            if decoded.get('test') != test:
                raise SystemExit('Diagnostic source/test name mismatch.')
            target = args.output / (test + '.json')
            target.write_text(json.dumps(decoded, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        written.append(str(target))
    print(json.dumps({'decodedFixtureFiles': written, 'source': 'offline disposable-emulator CI diagnostics'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
