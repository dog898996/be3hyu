#!/usr/bin/env python3
"""Real pinned local ONNX embeddings for public retrieval evaluation; no paid API.
Host tokenization/pooling match the intended model profile, not a measurement of Android parity.
Binary float32 vectors and source UTF-16 spans stay outside the app/repository.
"""
import argparse
import bisect
import hashlib
import json
import struct
import time
from pathlib import Path
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

parser = argparse.ArgumentParser()
parser.add_argument("fixture", type=Path)
parser.add_argument("output", type=Path)
parser.add_argument("--model-dir", type=Path, default=Path("/workspace/.tools/hoyoung-semantic-model"))
parser.add_argument("--reuse", type=Path, help="Reuse measured source vectors from the same model and exact message bodies")
args = parser.parse_args()
model = args.model_dir
manifest = json.loads((model / "manifest.json").read_text())
for item in manifest["files"]:
    path = model / item["name"]
    if path.stat().st_size != item["size"] or hashlib.sha256(path.read_bytes()).hexdigest() != item["sha256"]:
        raise SystemExit("Pinned model integrity check failed")
tokenizer_path = model / "tokenizer.json"
tokenizer = Tokenizer.from_file(str(tokenizer_path))
tokenizer.no_truncation()
tokenizer.no_padding()
cls, sep = tokenizer.token_to_id("[CLS]"), tokenizer.token_to_id("[SEP]")
raw = (model / "dense.safetensors").read_bytes()
size = struct.unpack("<Q", raw[:8])[0]
header = json.loads(raw[8:8 + size])
def tensor(name):
    spec = header[name]
    if spec["dtype"] != "F32":
        raise ValueError("Unexpected dense weight dtype")
    start, end = spec["data_offsets"]
    return np.frombuffer(raw[8 + size + start:8 + size + end], dtype="<f4").reshape(spec["shape"])
weight, bias = tensor("linear.weight"), tensor("linear.bias")
options = ort.SessionOptions()
options.intra_op_num_threads = 1
options.inter_op_num_threads = 1
session = ort.InferenceSession(str(model / "transformer.onnx"), options, providers=["CPUExecutionProvider"])

def ids(text):
    return tokenizer.encode(text, add_special_tokens=False).ids
def encode(text):
    tokens = ids(text)
    if not tokens:
        raise ValueError("Empty semantic input")
    result = np.zeros(512, dtype=np.float64)
    for start in range(0, len(tokens), 110):
        part = tokens[start:start + 126]
        input_ids = np.asarray([[cls, *part, sep]], dtype=np.int64)
        hidden = session.run(["last_hidden_state"], {"input_ids": input_ids, "attention_mask": np.ones_like(input_ids)})[0][0]
        pooled = hidden.mean(axis=0, dtype=np.float64)
        result += np.tanh(weight.astype(np.float64) @ pooled + bias)
        if start + 126 >= len(tokens):
            break
    return (result / np.linalg.norm(result)).astype("<f4")

fixture_bytes = args.fixture.read_bytes()
fixture = json.loads(fixture_bytes)
args.output.parent.mkdir(parents=True, exist_ok=True)
binary_path = args.output.with_suffix(".f32")
sources, queries, vector_count = [], [], 0
started = time.perf_counter()
reuse = None
if args.reuse:
    reuse = json.loads(args.reuse.read_text())
    if reuse["modelId"] != manifest["modelId"] or reuse["modelFiles"] != manifest["files"] or reuse["tokenizerSha256"] != hashlib.sha256(tokenizer_path.read_bytes()).hexdigest():
        raise ValueError("Reuse encoder pipeline differs")
    previous = {source["key"]: source for source in reuse["sources"]}
    if set(previous) != {m["key"] for m in fixture["messages"]}:
        raise ValueError("Reuse corpus differs")
    for message in fixture["messages"]:
        if hashlib.sha256(message["text"].encode()).hexdigest() != previous[message["key"]]["bodySha256"]:
            raise ValueError("Reuse original body differs")
    copied = (args.reuse.parent / reuse["vectorFile"]).read_bytes()
    if hashlib.sha256(copied).hexdigest() != reuse["vectorFileSha256"]:
        raise ValueError("Reuse vector sidecar integrity failure")
    vector_count = reuse["vectorCount"]
    sources = reuse["sources"]
with binary_path.open("wb") as binary:
    if reuse:
        binary.write(copied)
    def save(text):
        global vector_count
        index = vector_count
        binary.write(encode(text).tobytes())
        vector_count += 1
        return index
    for number, message in enumerate([] if reuse else fixture["messages"]):
        text, ranges = message["text"], []
        offsets = [0]
        for character in text:
            offsets.append(offsets[-1] + (2 if ord(character) > 0xFFFF else 1))
        cursor = 0
        while cursor < len(text):
            buffer_end = bisect.bisect_right(offsets, offsets[cursor] + 8192) - 1
            buffer = text[cursor:buffer_end]
            if not buffer.strip():
                cursor = buffer_end
                continue
            low, high = 1, len(buffer)
            while low < high:
                middle = (low + high + 1) // 2
                if len(ids(buffer[:middle])) <= 126:
                    low = middle
                else:
                    high = middle - 1
            while low > 0 and len(ids(buffer[:low])) > 126:
                low -= 1
            if low == 0:
                raise ValueError("No fitting token prefix")
            end = cursor + low
            length = offsets[end] - offsets[cursor]
            ranges.append({"offsetChars": offsets[cursor], "lengthChars": length, "vectorIndex": save(text[cursor:end])})
            if end == len(text):
                break
            overlap = min(40, length // 5)
            cursor = bisect.bisect_left(offsets, offsets[end] - overlap)
        sources.append({"key": message["key"], "bodySha256": hashlib.sha256(text.encode()).hexdigest(), "totalChars": offsets[-1], "chunks": ranges})
        if number % 200 == 0:
            print(json.dumps({"sources": number + 1, "vectors": vector_count, "elapsedSeconds": round(time.perf_counter() - started, 2)}), flush=True)
    for question in fixture["questions"]:
        queries.append({"query": question["query"].strip(), "vectorIndex": save(question["query"].strip())})
result = {"modelId": manifest["modelId"], "dimensions": 512, "modelFiles": manifest["files"],
          "tokenizerSha256": hashlib.sha256(tokenizer_path.read_bytes()).hexdigest(), "fixtureSha256": hashlib.sha256(fixture_bytes).hexdigest(),
          "vectorFile": binary_path.name, "vectorFileSha256": hashlib.sha256(binary_path.read_bytes()).hexdigest(), "vectorCount": vector_count,
          "encoder": "real-local-onnx-cpu-public-input-only", "onnxruntimeVersion": ort.__version__, "profile": "cased-WordPiece-mean768-dense512-tanh-unit;query126stride110;UTF16-token-fit-source-chunks",
          "androidTokenizationParityMeasured": False, "realDeviceLatencyMeasured": False, "actualLmMeasured": False,
          "elapsedSeconds": time.perf_counter() - started, "sources": sources, "queries": queries}
if reuse:
    result["sourceVectorsReusedFromSha256"] = reuse["vectorFileSha256"]
    result["originalSourceEmbeddingElapsedSeconds"] = reuse["elapsedSeconds"]
args.output.write_text(json.dumps(result, ensure_ascii=False))
print(json.dumps({"output": str(args.output), "sources": len(sources), "vectors": vector_count, "vectorBytes": binary_path.stat().st_size,
                  "elapsedSeconds": result["elapsedSeconds"], "actualLmMeasured": False}), flush=True)
