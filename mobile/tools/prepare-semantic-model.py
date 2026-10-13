#!/usr/bin/env python3
"""Fetch pinned Apache-2.0 public model inputs and verify bytes; no account or API key."""
import argparse
import hashlib
import json
import pathlib
import time
import urllib.request

REPOSITORY = 'sentence-transformers/distiluse-base-multilingual-cased-v2'
REVISION = 'bfe45d0732ca50787611c0fe107ba278c7f3f889'
INPUTS = [
    ('onnx/model_qint8_arm64.onnx', 'transformer.onnx', 135336307,
     'c78c64bb0446ceb7086d2de24c2f4297e7a909eb457b4f68e5fdb6ab19f285c0', None),
    ('2_Dense/model.safetensors', 'dense.safetensors', 1575104,
     '0a21b1ce908e772ebf09f93c20ca09524c32706e9918d9c0169a3f0663b191ed', None),
    ('vocab.txt', 'vocab.txt', 995526, None, 'e837bab60a5d204e29622d127c2dafe508aa0731'),
]

def verify(path, size, sha256, git_sha1):
    if not path.is_file() or path.stat().st_size != size:
        return False
    content = path.read_bytes()
    digest = hashlib.sha256(content).hexdigest()
    if sha256 and digest != sha256:
        return False
    if git_sha1 and hashlib.sha1(f'blob {size}\0'.encode() + content).hexdigest() != git_sha1:
        return False
    return digest

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', default='/workspace/.tools/hoyoung-semantic-model')
    parser.add_argument('--manifest-output')
    args = parser.parse_args()
    target = pathlib.Path(args.output).resolve()
    source_repo = pathlib.Path(__file__).resolve().parents[2]
    if target == source_repo or source_repo in target.parents:
        raise SystemExit('Large model weights must stay outside the Git source repository.')
    target.mkdir(parents=True, exist_ok=True)
    items = []
    for remote, name, size, sha256, git_sha1 in INPUTS:
        url = f'https://huggingface.co/{REPOSITORY}/resolve/{REVISION}/{remote}'
        path = target / name
        digest = verify(path, size, sha256, git_sha1)
        if not digest:
            temporary = target / (name + '.partial')
            for attempt in range(3):
                try:
                    with urllib.request.urlopen(url, timeout=60) as response, temporary.open('wb') as stream:
                        written = 0
                        while chunk := response.read(1024 * 1024):
                            written += len(chunk)
                            if written > size:
                                raise ValueError('Model download is larger than its pinned manifest.')
                            stream.write(chunk)
                    digest = verify(temporary, size, sha256, git_sha1)
                    if not digest:
                        raise ValueError('Model checksum or size does not match the pinned revision.')
                    temporary.replace(path)
                    break
                except Exception as error:
                    temporary.unlink(missing_ok=True)
                    if attempt == 2:
                        raise SystemExit(f'{name}: download failed ({type(error).__name__}).')
                    time.sleep(2 ** attempt)
        items.append({'name': name, 'url': url, 'size': size, 'sha256': digest})
        print(json.dumps({'file': name, 'verifiedBytes': size, 'sha256': digest}), flush=True)
    manifest = {'modelId': REPOSITORY + '@' + REVISION, 'license': 'Apache-2.0',
                'dimensions': 512, 'maxSequenceTokens': 128,
                'pooling': 'mean-attention-mask', 'projection': 'dense-768-512-tanh',
                'files': items}
    (target / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    if args.manifest_output:
        pathlib.Path(args.manifest_output).write_text(json.dumps(manifest, indent=2) + '\n')

if __name__ == '__main__':
    main()
