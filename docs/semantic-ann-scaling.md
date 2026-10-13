# Long-lived semantic search without an age-biased full-vector scan

The SQLite/source archive remains authoritative. `SemanticAnnCache` builds a private, disposable
native HNSW graph from the canonical float32 source chunks. Search selects a bounded approximate
shortlist, reranks that shortlist with the exact canonical float32 vectors, then verifies current
source hashes, dates and device access. The independent FTS, pinned-profile and current/history
paths remain active. ANN relevance scores are not factual confidence.

The previous fallback reads float vectors in primary-key order and stops after its 1.5-second
budget. At large sizes that can miss later rows even when they are the best matches. A graph search
can traverse the whole indexed corpus without reading every float vector for every query. It still
can miss true nearest neighbors; exact reranking corrects quantization order inside the shortlist,
and cannot recover a neighbor that never entered the shortlist.

## Primary sources and implementation

- Malkov and Yashunin, [Efficient and robust approximate nearest neighbor search using Hierarchical
  Navigable Small World graphs](https://arxiv.org/abs/1603.09320), published in IEEE TPAMI. This is
  the algorithm paper, not evidence of this app's quality or Samsung latency.
- [nmslib/hnswlib](https://github.com/nmslib/hnswlib/tree/3f3429661187e4c24a490a0f148fc6bc89042b3d),
  v0.8.0 pinned at `3f3429661187e4c24a490a0f148fc6bc89042b3d`. The actual pinned LICENSE is
  **Apache-2.0**, despite earlier planning that described it as MIT. Unmodified headers, license,
  parameter guide and per-file SHA-256 provenance are vendored under `mobile/app/src/main/cpp/third_party/hnswlib`.
- [Official parameter guide](https://github.com/nmslib/hnswlib/blob/3f3429661187e4c24a490a0f148fc6bc89042b3d/ALGO_PARAMS.md)
  explains the memory/recall tradeoff of `M`, construction `ef` and search `ef`. Our measured profile
  uses `M=16`, construction `ef=200`, search `ef>=512`, and at least 200 rerank candidates.
- [Related upstream issue #107](https://github.com/nmslib/hnswlib/issues/107) reports an
  AddressSanitizer heap overread with SSE enabled and says disabling that path avoids the failure.
  It concerns another upstream revision; our v0.8.0 stack trace and fixed sanitizer run are
  independent measurements, rather than treating an issue report as proof of this implementation.
- The public source embeddings use the pinned multilingual local ONNX model documented in
  `mobile/app/src/main/assets/semantic-model.json`; no paid API produces embeddings or benchmarks.

Each normalized 512-dimensional vector is rounded to signed int8 with scale 127 and stores an
inverse quantized norm. ARM64 NEON and x86-64 SSE2 compute the same int32 dot product. The full
float32 cache is retained in SQLite. This saves graph memory, not the size of originals or the full
canonical cache. Native links and labels add overhead; it is not a claim that years of exact memory
can fit in 20 MB.

The cache has an encoder/pipeline identity, mutation generation and SHA-256 fingerprint of ordered
SQLite row IDs, source hashes, ranges, dimensions and encoding. Inserts can be added incrementally;
replacement/deletion causes a rebuild. A graph that covers only part of the canonical corpus is not
advertised as ready. A checksummed atomic manifest is written after the graph is flushed; loading
also validates allocation bounds, link references and norms before native deserialization. A bad,
missing or incompatible cache falls back to the canonical search; original records are untouched.
The cache is not imported from backups and can be rebuilt after cache deletion.

Current date and device-grant exclusions apply before native top-k selection. Full returned records
are checked again. The optional source/category policy cache is query-local and is enabled only
for the current `PhoneTools.canReadStoredSource` contract; generic predicates use the full device
record. Entity filters use bounded adaptive overfetch and explicitly report an exhausted shortlist.

## Measurements made during development

These are **host CPU measurements**, not measurements of a Galaxy S24 Ultra or model answer
accuracy. The reproducible scripts are `mobile/tools/run-native-ann-host.sh` and
`mobile/tools/benchmark-native-ann.py`. Reports are saved under `/workspace/artifacts` during the
session. Reviewed source-free summaries are retained in `docs/benchmarks/native-ann-public-holdout.json`
and `docs/benchmarks/native-ann-100k-clustered.json`; the independent production SQLite results
are also recorded in `docs/memory-benchmark-results.json`.

| Corpus/query scope | Native int8 top-20 recall of exhaustive float32 neighbors | With 200-candidate float32 rerank, ef 512 | Gold source recall, semantic path only |
| --- | --- | --- | --- |
| 6,940 real ONNX public source chunks; 40 held-out public questions | mean 0.97, worst 0.90 | mean 1.00, worst 1.00 in this sample | exhaustive 0.740833; raw ANN 0.736667; reranked ANN 0.740833 |
| 100,000 synthetic perturbations of those source embeddings; same 40 held-out questions | mean 0.9125, worst 0.70 | mean 1.00, worst 1.00 in this sample | not measured: synthetic rows are not real conversations |

The real public sample uses oracle-selected sessions and pooled distractors and is **not** the
standard complete LongMemEval benchmark. Source recall is not final-answer accuracy. The native
top-20 shortfall on the 100k case motivated exact reranking; the sample's 1.00 does not establish a
universal guarantee. Broader corpus shapes, especially many almost-equal vectors, may require
larger shortlists or a different graph profile.

The 100k graph was 66,444,136 bytes and built in 21.62 seconds. Verified native cache load took
0.080 seconds. Native search plus in-memory float32 shortlist rerank had host P95 0.654 ms at ef
512; this excludes SQLite reads, current-policy filtering and Android scheduling. Exhaustive NumPy
float32 cosine had P95 12.77 ms; NumPy memory scans do not represent SQLite's disk/cursor overhead.
Process peak RSS was 466,352 KiB, including NumPy canonical vectors and two native graphs during
the persistence check; that value is not a native-graph-only memory measurement. An unexpanded
public graph was 4,613,204 bytes. These native timing reports precede the later prefetch-disable
and cache-loader hardening; their JSON records explicitly identify that implementation scope.

Nine meaningful native host checks passed: random-pair quantization error, self match, exclusion,
persistence equivalence, invalid expected count/truncated cache, nonfinite vector rejection,
zero-vector rejection, integer labels above 2^53 and a small-dimensional aligned-label regression. On 1,000 random 512-d comparisons, mean
absolute cosine error was 0.00256 and worst 0.01146. Both packaged Android ABIs compiled with the
official NDK r28c; ELF load segments are 16 KiB compatible. The separate `NativeAnnDeviceTest`
requires actual packaged JNI execution on the CI AOSP emulator before Android execution is claimed.

An additional ASan/UBSan run found a real out-of-bounds read in upstream's optional x86 SSE
prefetch of the next neighbor beyond a full upper-layer link list. The wrapper now disables that
upstream prefetch path while retaining its own bounded SSE2/NEON int8 dot product; vendored files
stay unchanged. Generic small-dimensional test data is padded to align uint64 labels. After those
fixes, 500 deterministic corrupt-cache trials completed with no sanitizer errors: 442 structurally
invalid files were rejected and 58 structurally valid mutations remained safe to search. Java's
SHA check rejects altered cache bytes before either case can be loaded in the app.

The separate actual SQLite/JNI/retriever paired test on the same 40 held-out public questions found
lexical source recall 0.4625, exhaustive-vector hybrid RRF recall 0.76625 and ANN-reranked hybrid
RRF recall 0.76625, with identical per-question recall. Its initial host SQLite source search median
was 25 ms with ANN versus 66 ms with the full vector scan, and it read 491,520 canonical vector
bytes (240 candidates) versus 14,213,120 bytes (6,940 vectors). Complete retrieval medians were
227 versus 267 ms. These timings preceded the metadata-filter and upstream-prefetch changes;
the full app pipeline and Samsung performance require their own measurements.

The production SQLite/JNI path was also tested with 100,000 distinct synthetic Gaussian
512-dimensional vectors and 20 self-match probes spread from the oldest to the newest source.
Top-1 self recall was 1.00, source-search median 37 ms and P95 131 ms. Each query read 409,600
canonical float bytes (200 candidates); the database plus WAL was 493,951,824 bytes, and the native
cache directory was 66,444,563 bytes. Populating SQLite took 17.04 seconds. Building this much less
clustered graph took **405.66 seconds**, about 6.8 minutes of host CPU time, so first build/rebuild is
substantial and belongs to the background maintenance worker. This test did not measure top-20
neighbor recall, neural answer quality or phone latency. It used the optimized metadata filter and
the pre-sanitizer-fix host library; final packaged Android JNI has separate execution checks.

Cache construction/index DDL is deferred from the application constructor to its maintenance
worker. Diagnostic reads use a completed immutable snapshot so the UI does not wait for a native
build. Unknown corpus size is reported as unknown rather than 100% covered, and graph coverage
means the currently generated canonical chunks; raw-source embedding coverage remains separate.

`semantic_vectors` for derived memory entries still uses its older bounded exact scan; this module
accelerates raw source chunks. Neither a million real source vectors nor 1–2 years of actual
usage, autonomous extraction quality or perfect semantic recall has been validated by these tests.
