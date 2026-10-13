#!/usr/bin/env python3
"""Actual native HNSW/int8 benchmark against exhaustive float32 cosine, no model/API calls.

Public mode uses previously measured, integrity-checked pinned ONNX public vectors. Synthetic
mode makes a larger clustered corpus by perturbing those source vectors; it is not real memory
quality. No ANN recall metric establishes correct final answers or perfect recall.
"""
import argparse
import ctypes as c
import hashlib
import json
import os
import resource
import time
from pathlib import Path
os.environ.setdefault("OPENBLAS_NUM_THREADS", "1")
import numpy as np

parser = argparse.ArgumentParser()
parser.add_argument("--library", type=Path, required=True)
parser.add_argument("--vectors", type=Path, required=True, help="prepare-public-semantic-benchmark.py JSON sidecar")
parser.add_argument("--fixture", type=Path, required=True)
parser.add_argument("--mode", choices=["public", "synthetic"], default="public")
parser.add_argument("--count", type=int, default=100_000)
parser.add_argument("--m", type=int, default=16)
parser.add_argument("--construction-ef", type=int, default=200)
parser.add_argument("--efs", default="100,200,512,1000")
parser.add_argument("--query-count", type=int, default=40)
parser.add_argument("--rerank-candidates", type=int, default=200, help="Native shortlist; exact canonical f32 reranks this bounded list")
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
metadata = json.loads(args.vectors.read_text())
vector_path = args.vectors.parent / metadata["vectorFile"]
if hashlib.sha256(vector_path.read_bytes()).hexdigest() != metadata["vectorFileSha256"]:
    raise ValueError("Canonical host vector integrity failure")
fixture_bytes = args.fixture.read_bytes()
fixture = json.loads(fixture_bytes)
if hashlib.sha256(fixture_bytes).hexdigest() != metadata["fixtureSha256"]:
    raise ValueError("Fixture differs from vector measurement")
d = metadata["dimensions"]
vectors = np.memmap(vector_path, dtype="<f4", mode="r").reshape((-1, d))
source_indices = [chunk["vectorIndex"] for source in metadata["sources"] for chunk in source["chunks"]]
if source_indices != list(range(len(source_indices))):
    raise ValueError("Expected source rows before query rows")
source_keys = [source["key"] for source in metadata["sources"] for chunk in source["chunks"]]
queries = np.ascontiguousarray(vectors[[q["vectorIndex"] for q in metadata["queries"][:args.query_count]]], dtype=np.float32)
if args.mode == "synthetic":
    if not 1_000 <= args.count <= 1_000_000:
        raise ValueError("Synthetic size outside 1000..1000000")
    rng = np.random.default_rng(452031)
    # Public source embeddings define natural anisotropic clusters; rows are *synthetic* variants.
    parents = rng.integers(0, len(source_indices), size=args.count)
    corpus = np.asarray(vectors[parents], dtype=np.float32).copy()
    for base in range(0, len(corpus), 1000):
        end = min(len(corpus), base + 1000)
        corpus[base:end] += rng.normal(0, .004, size=(end-base,d)).astype(np.float32)
    corpus /= np.linalg.norm(corpus, axis=1, keepdims=True)
else:
    corpus = np.ascontiguousarray(vectors[:len(source_indices)], dtype=np.float32)
labels = np.arange(1, len(corpus) + 1, dtype=np.uint64)
library = c.CDLL(str(args.library.resolve()))
fp, up = c.POINTER(c.c_float), c.POINTER(c.c_uint64)
library.hoyoung_ann_create.argtypes = [c.c_int]*4;library.hoyoung_ann_create.restype=c.c_void_p
library.hoyoung_ann_add.argtypes=[c.c_void_p,fp,up,c.c_int];library.hoyoung_ann_add.restype=c.c_int
library.hoyoung_ann_search.argtypes=[c.c_void_p,fp,c.c_int,c.c_int,up,fp];library.hoyoung_ann_search.restype=c.c_int
library.hoyoung_ann_save.argtypes=[c.c_void_p,c.c_char_p];library.hoyoung_ann_save.restype=c.c_int
library.hoyoung_ann_load.argtypes=[c.c_void_p,c.c_char_p,c.c_int];library.hoyoung_ann_load.restype=c.c_int
library.hoyoung_ann_close.argtypes=[c.c_void_p]
library.hoyoung_ann_error.restype=c.c_char_p
def check(code):
    if code < 0: raise RuntimeError(library.hoyoung_ann_error().decode())
    return code
handle = library.hoyoung_ann_create(d, len(corpus), args.m, args.construction_ef)
if not handle: raise RuntimeError(library.hoyoung_ann_error().decode())
started = time.perf_counter()
for base in range(0,len(corpus),1000):
    end=min(len(corpus),base+1000)
    check(library.hoyoung_ann_add(handle,corpus[base:end].ctypes.data_as(fp),labels[base:end].ctypes.data_as(up),end-base))
    if end % 10_000 == 0 or end == len(corpus):
        print(json.dumps({"stage":"native_graph_build","inserted":end,"seconds":round(time.perf_counter()-started,3)}),flush=True)
build_seconds=time.perf_counter()-started
index_path=args.output.with_suffix(".ann-cache.bin")
check(library.hoyoung_ann_save(handle,str(index_path).encode()))
index_bytes=index_path.stat().st_size
# Exhaustive float32 reference scans the entire corpus, without the old 1.5-second age-biased cap.
exact_ids, exact_latencies=[],[]
for query in queries:
    started=time.perf_counter();scores=corpus @ query;top=np.argpartition(scores,-20)[-20:];top=top[np.argsort(scores[top])[::-1]];exact_ids.append(top+1);exact_latencies.append((time.perf_counter()-started)*1000)
settings=[]
for ef in [int(x) for x in args.efs.split(",")]:
    ids=np.empty(20,dtype=np.uint64);similarities=np.empty(20,dtype=np.float32)
    recall,latencies,gold_exact,gold_ann,quant_errors=[],[],[],[],[]
    rerank_recalls,rerank_latencies,rerank_gold=[],[],[]
    for i,query in enumerate(queries):
        started=time.perf_counter();n=check(library.hoyoung_ann_search(handle,query.ctypes.data_as(fp),20,ef,ids.ctypes.data_as(up),similarities.ctypes.data_as(fp)));latencies.append((time.perf_counter()-started)*1000)
        selected=ids[:n].copy();recall.append(len(set(selected.tolist())&set(exact_ids[i].tolist()))/20)
        exact_scores=(corpus[selected-1]@query).astype(np.float64);quant_errors.extend(np.abs(exact_scores-similarities[:n]).tolist())
        if args.mode=="public":
            gold=set(fixture["questions"][i]["gold"])
            gold_exact.append(len(gold & {source_keys[int(label)-1] for label in exact_ids[i]})/len(gold))
            gold_ann.append(len(gold & {source_keys[int(label)-1] for label in selected})/len(gold))
        shortlist_ids=np.empty(args.rerank_candidates,dtype=np.uint64);shortlist_scores=np.empty(args.rerank_candidates,dtype=np.float32)
        started=time.perf_counter();candidate_n=check(library.hoyoung_ann_search(handle,query.ctypes.data_as(fp),args.rerank_candidates,max(args.rerank_candidates,ef),shortlist_ids.ctypes.data_as(up),shortlist_scores.ctypes.data_as(fp)))
        candidate_labels=shortlist_ids[:candidate_n];exact_shortlist=corpus[candidate_labels-1]@query;order=np.argsort(exact_shortlist)[::-1][:20];reranked=candidate_labels[order];rerank_latencies.append((time.perf_counter()-started)*1000)
        rerank_recalls.append(len(set(reranked.tolist())&set(exact_ids[i].tolist()))/20)
        if args.mode=="public":rerank_gold.append(len(gold & {source_keys[int(label)-1] for label in reranked})/len(gold))
    setting={"efSearch":ef,"topK":20,"meanExactF32NeighborRecallAt20":float(np.mean(recall)),"worstExactF32NeighborRecallAt20":float(min(recall)),"latencyMedianMillis":float(np.median(latencies)),"latencyP95Millis":float(np.percentile(latencies,95)),"latencyMaxMillis":float(max(latencies)),"quantizedHitCosineMeanAbsoluteError":float(np.mean(quant_errors)),"quantizedHitCosineMaxAbsoluteError":float(max(quant_errors))}
    setting.update({"canonicalF32RerankCandidates":args.rerank_candidates,"rerankedExactF32NeighborRecallAt20":float(np.mean(rerank_recalls)),"rerankedWorstExactF32NeighborRecallAt20":float(min(rerank_recalls)),"rerankedLatencyMedianMillis":float(np.median(rerank_latencies)),"rerankedLatencyP95Millis":float(np.percentile(rerank_latencies,95))})
    if gold_exact:setting.update({"goldSourceRecallExactSemanticOnly":float(np.mean(gold_exact)),"goldSourceRecallAnnSemanticOnly":float(np.mean(gold_ann)),"goldSourceRecallRerankedAnnSemanticOnly":float(np.mean(rerank_gold)),"lexicalRrfFusionMeasured":False})
    settings.append(setting);print(json.dumps({"stage":"query_measurement",**setting}),flush=True)
load_handle=library.hoyoung_ann_create(d,1,args.m,args.construction_ef)
started=time.perf_counter();check(library.hoyoung_ann_load(load_handle,str(index_path).encode(),len(corpus)));load_seconds=time.perf_counter()-started
original_ids=np.empty(20,dtype=np.uint64);restored_ids=np.empty(20,dtype=np.uint64);scores=np.empty(20,dtype=np.float32)
check(library.hoyoung_ann_search(handle,queries[0].ctypes.data_as(fp),20,200,original_ids.ctypes.data_as(up),scores.ctypes.data_as(fp)))
check(library.hoyoung_ann_search(load_handle,queries[0].ctypes.data_as(fp),20,200,restored_ids.ctypes.data_as(up),scores.ctypes.data_as(fp)))
restore_identical=bool(np.array_equal(original_ids,restored_ids))
library.hoyoung_ann_close(load_handle);library.hoyoung_ann_close(handle)
result={"benchmark":"actual-native-int8-cosine-hnsw-vs-exhaustive-f32","hnswlibCommit":"3f3429661187e4c24a490a0f148fc6bc89042b3d","hnswlibLicense":"Apache-2.0","mode":args.mode,"storedVectors":len(corpus),"dimensions":d,"sourceVectorFileSha256":metadata["vectorFileSha256"],"fixtureSha256":metadata["fixtureSha256"],"modelId":metadata["modelId"],"m":args.m,"efConstruction":args.construction_ef,"buildSeconds":build_seconds,"nativeIndexBytes":index_bytes,"nativeCacheLoadSeconds":load_seconds,"nativeCacheRoundTripFirstQueryIdentical":restore_identical,"processPeakRssKiB":resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,"exactF32LatencyMedianMillis":float(np.median(exact_latencies)),"exactF32LatencyP95Millis":float(np.percentile(exact_latencies,95)),"queries":len(queries),"settings":settings,"realDeviceLatencyMeasured":False,"androidAbiMeasured":False,"actualLmMeasured":False,"standardLongMemEvalBenchmark":False,"syntheticExpansionSeed":452031 if args.mode=="synthetic" else None,"queryGeneration":"held-out-public-question-embeddings-from-real-local-ONNX"}
args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2));index_path.unlink()
print(json.dumps({"report":str(args.output),"nativeCacheRoundTripFirstQueryIdentical":restore_identical,"processPeakRssKiB":result["processPeakRssKiB"]}),flush=True)
