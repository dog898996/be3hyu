#!/usr/bin/env python3
"""Compare actual host ONNX/Rust/Java implementations using public synthetic inputs."""
import argparse
import base64
import hashlib
import json
import os
import pathlib
import struct
import subprocess
import tempfile
import time
import urllib.request

REVISION = 'bfe45d0732ca50787611c0fe107ba278c7f3f889'
TOKENIZER_SHA256 = '5b4e1a8171c81dfd666ae40265b9530c6e0b3d53923fe8ac493dcc84229adf81'
TOKENIZER_GIT_BLOB = 'f930bdc88c40a163884a6264ebd3bd10688ae1af'
TOKENIZER_SIZE = 1961847
STRAWBERRY = [
    '나는 딸기 알레르기가 있어.',
    '딸기를 먹으면 알레르기 반응이 나.',
    'I am allergic to strawberries.',
    'A rocket launched into orbit.',
]
SCENARIOS = [
    ('spicy', '나는 매운 음식을 잘 못 먹어.', '매운 음식은 피하고 싶어.', '컴퓨터 메모리를 업그레이드했어.'),
    ('cat_name', '우리 고양이 이름은 나비야.', '내 반려묘는 나비라고 불러.', '내일 기차표를 예약해야 해.'),
    ('walking', '아침마다 공원에서 산책해.', '나는 매일 아침 공원에 걸으러 가.', '회의록을 이메일로 보냈어.'),
    ('birthday', '엄마 생일은 5월 3일이야.', '어머니의 생일은 5월 3일이다.', '배터리가 부족해서 충전했어.'),
    ('meeting', '금요일 오후에 팀 회의가 있어.', '이번 금요일 오후 팀원들과 회의해.', '나는 초콜릿 아이스크림을 좋아해.'),
    ('answer_style', '답변은 짧고 핵심부터 말해 줘.', '나는 간결하게 결론부터 듣고 싶어.', '우리집 식탁을 새로 샀어.'),
    ('crosslingual_cat', '우리 고양이 이름은 나비야.', 'My cat is named Nabi.', 'The train arrives at seven.'),
    ('crosslingual_city', '나는 부산에 살고 있어.', 'I live in Busan.', 'The battery needs charging.'),
]


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def verified_tokenizer(directory):
    path = directory / 'tokenizer.json'
    if not path.is_file() or path.stat().st_size != TOKENIZER_SIZE or sha256(path) != TOKENIZER_SHA256:
        url = f'https://huggingface.co/sentence-transformers/distiluse-base-multilingual-cased-v2/resolve/{REVISION}/tokenizer.json'
        temporary = directory / 'tokenizer.json.partial'
        with urllib.request.urlopen(url, timeout=60) as source, temporary.open('wb') as output:
            count = 0
            while chunk := source.read(64 * 1024):
                count += len(chunk)
                if count > TOKENIZER_SIZE:
                    raise ValueError('Tokenizer exceeded its pinned official size.')
                output.write(chunk)
        if temporary.stat().st_size != TOKENIZER_SIZE or sha256(temporary) != TOKENIZER_SHA256:
            temporary.unlink(missing_ok=True)
            raise ValueError('Tokenizer does not match its pinned SHA256.')
        temporary.replace(path)
    content = path.read_bytes()
    if hashlib.sha1(f'blob {len(content)}\0'.encode() + content).hexdigest() != TOKENIZER_GIT_BLOB:
        raise ValueError('Tokenizer differs from the Git blob published by the immutable official model revision.')
    return path


def compare_java_tokenizer(repo, model, official, cases):
    root = pathlib.Path(os.environ.get('HOYOUNG_ANDROID_TOOLS_DIR', '/workspace/.tools/android-build'))
    jdk = root / 'jdk-17.0.16+8'
    if not (jdk / 'bin/javac').is_file():
        raise ValueError('Prepare the pinned Java compiler with mobile/tools/build-android.sh --prepare first.')
    source = repo / 'mobile/app/src/main/java/com/hoyoung/assistant/memory/local/BertWordPieceTokenizer.java'
    runner = '''package com.hoyoung.assistant.memory.local;
import java.io.*; import java.nio.charset.StandardCharsets; import java.util.*;
public final class OfficialGoldenRunner {
 public static void main(String[] args) throws Exception {
  BertWordPieceTokenizer tokenizer = new BertWordPieceTokenizer(new InputStreamReader(new FileInputStream(args[0]), StandardCharsets.UTF_8));
  for(int i=1;i<args.length;i++) {
   String text=new String(Base64.getDecoder().decode(args[i]),StandardCharsets.UTF_8);
   System.out.println(Arrays.toString(tokenizer.tokens(text)));
  }
 }
}'''
    for case in cases:
        ids = official.encode(case['text'], add_special_tokens=False).ids
        if ids != case['tokenIds']:
            raise ValueError('Checked-in tokenizer golden differs from the actual official Rust tokenizer.')
    with tempfile.TemporaryDirectory(prefix='hoyoung-tokenizer-golden-') as temporary:
        directory = pathlib.Path(temporary)
        java_source = directory / 'OfficialGoldenRunner.java'
        java_source.write_text(runner)
        subprocess.run([str(jdk / 'bin/javac'), '-encoding', 'UTF-8', '-d', str(directory), str(source), str(java_source)], check=True)
        encoded = [base64.b64encode(case['text'].encode()).decode() for case in cases]
        completed = subprocess.run([str(jdk / 'bin/java'), '-cp', str(directory),
            'com.hoyoung.assistant.memory.local.OfficialGoldenRunner', str(model / 'vocab.txt'), *encoded],
            text=True, capture_output=True, check=True)
        actual = [json.loads(line) for line in completed.stdout.splitlines() if line.strip()]
    if len(actual) != len(cases):
        raise ValueError('Java tokenizer did not emit every golden result.')
    for case, ids in zip(cases, actual):
        if ids != case['tokenIds']:
            raise ValueError('Java tokenizer differs from the official Rust tokenizer on a synthetic Unicode fixture.')
    return {'cases': len(cases), 'passed': len(cases), 'officialRustCompared': True, 'standaloneJavaCompiled': True}


def projection(path, numpy):
    content = path.read_bytes()
    header_size = struct.unpack('<Q', content[:8])[0]
    if not 2 <= header_size <= 65536:
        raise ValueError('Invalid safetensors projection header.')
    header = json.loads(content[8:8 + header_size])
    arrays = {}
    for name, expected in [('linear.weight', [512, 768]), ('linear.bias', [512])]:
        item = header[name]
        if item['dtype'] != 'F32' or item['shape'] != expected:
            raise ValueError('Projection shape/dtype differs from the trained model.')
        start, end = item['data_offsets']
        if start < 0 or end < start or 8 + header_size + end > len(content):
            raise ValueError('Projection offset is invalid.')
        arrays[name] = numpy.frombuffer(content, dtype='<f4', offset=8 + header_size + start,
            count=(end - start) // 4).reshape(expected)
    return arrays['linear.weight'], arrays['linear.bias']


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model-dir', default='/workspace/.tools/hoyoung-semantic-model')
    parser.add_argument('--output', default='/workspace/artifacts/hoyoung-semantic-host-validation.json')
    args = parser.parse_args()
    repo = pathlib.Path(__file__).resolve().parents[2]
    directory = pathlib.Path(args.model_dir).resolve()
    if directory == repo or repo in directory.parents:
        raise SystemExit('Model weights must remain outside the source repository.')
    import numpy as np
    import onnxruntime as ort
    from tokenizers import Tokenizer
    manifest = json.loads((repo / 'mobile/app/src/main/assets/semantic-model.json').read_text())
    for item in manifest['files']:
        path = directory / item['name']
        if not path.is_file() or path.stat().st_size != item['size'] or sha256(path) != item['sha256']:
            raise ValueError('Model input differs from the actual application manifest; prepare the public model first.')
    tokenizer = Tokenizer.from_file(str(verified_tokenizer(directory)))
    tokenizer.no_truncation()
    cases = json.loads((repo / 'mobile/tests/tokenizer-golden.json').read_text())
    java_result = compare_java_tokenizer(repo, directory, tokenizer, cases)
    weight, bias = projection(directory / 'dense.safetensors', np)
    ort.disable_telemetry_events()
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    session = ort.InferenceSession(str(directory / 'transformer.onnx'), sess_options=options, providers=['CPUExecutionProvider'])
    if set(i.name for i in session.get_inputs()) != {'input_ids', 'attention_mask'}:
        raise ValueError('Actual ONNX inputs differ from the Java inference pipeline.')
    encode_times = []

    def encode(text):
        started = time.perf_counter()
        tokens = tokenizer.encode(text, add_special_tokens=False).ids
        total = np.zeros(512, dtype=np.float64)
        for start in range(0, len(tokens), 110):
            end = min(len(tokens), start + 126)
            ids = np.asarray([[101, *tokens[start:end], 102]], dtype=np.int64)
            mask = np.ones_like(ids)
            hidden = session.run(['last_hidden_state'], {'input_ids': ids, 'attention_mask': mask})[0][0]
            if hidden.shape != (ids.shape[1], 768):
                raise ValueError('Actual model hidden states have the wrong shape.')
            pooled = hidden.mean(axis=0, dtype=np.float32)
            total += np.tanh(weight @ pooled + bias)
            if end == len(tokens):
                break
        norm = np.linalg.norm(total)
        if norm <= 1e-16 or not np.isfinite(total).all():
            raise ValueError('The actual trained model produced an invalid embedding.')
        result = (total / norm).astype(np.float32)
        encode_times.append((time.perf_counter() - started) * 1000)
        return result

    scenarios = []
    for name, original, positive, unrelated in SCENARIOS:
        vectors = [encode(text) for text in (original, positive, unrelated)]
        positive_cosine = float(vectors[0] @ vectors[1])
        unrelated_cosine = float(vectors[0] @ vectors[2])
        scenarios.append({'scenario': name, 'positiveCosine': positive_cosine, 'unrelatedCosine': unrelated_cosine,
            'positiveRanksHigher': positive_cosine > unrelated_cosine})
    vectors = [encode(text) for text in STRAWBERRY]
    strawberry = {'texts': STRAWBERRY, 'koreanParaphraseCosine': float(vectors[0] @ vectors[1]),
        'koreanEnglishCosine': float(vectors[0] @ vectors[2]), 'unrelatedCosine': float(vectors[0] @ vectors[3])}
    strawberry['bothPositiveRankHigher'] = min(strawberry['koreanParaphraseCosine'], strawberry['koreanEnglishCosine']) > strawberry['unrelatedCosine']
    report = {'scope': 'Actual host CPU neural inference and official Rust versus standalone Java tokenizer; not Android/physical-phone/year-scale recall',
        'modelId': manifest['modelId'], 'pipeline': 'bert-wordpiece-cased-v1|mean-dense-tanh512|query-overlap110-v1',
        'runtime': {'onnxruntime': ort.__version__, 'numpy': np.__version__}, 'dimensions': 512,
        'weightsSha256Verified': True, 'tokenizer': java_result, 'scenarios': scenarios,
        'positiveRanksHigher': sum(item['positiveRanksHigher'] for item in scenarios), 'totalScenarios': len(scenarios),
        'strawberry': strawberry, 'encodeMeanMillis': sum(encode_times) / len(encode_times),
        'userDataUsed': False, 'paidApiCalls': 0, 'fullOneYearMemoryAccuracyMeasured': False}
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if report['positiveRanksHigher'] != len(scenarios) or not strawberry['bothPositiveRankHigher']:
        raise SystemExit('At least one actual synthetic semantic sanity case failed its relative-ranking check.')


if __name__ == '__main__':
    main()
