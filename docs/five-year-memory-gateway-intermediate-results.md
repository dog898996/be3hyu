# Intermediate Gateway measurements: unfinished final phase

The obsolete V2 Gateway runs were stopped when the parent prioritized the newer combined physical-deletion and Korean alias fixes. **They did not complete the final five-year recall or privacy replay gate.** The exact old production sources, completed checkpoint outputs and termination markers are retained in [the V2 artifacts](benchmarks/five-year-memory-gateway-intermediate-v2/paired-summary.json). Stopping this obsolete version does not count as a passed question, a passed replay, or an improved answer score.

| Arm | First-year complete source delivery | Three-year complete source delivery | Three-year exact originals delivered | Rare raw cases at three years |
|---|---:|---:|---:|---:|
| Raw originals | 122/144 | 128/144 | 376/396 | 12/12 |
| Same independent writer output | 102/144 | 108/144 | 248/396 | 8/12 |

All completed checkpoint cases had zero blocked queries and zero protected-value exposures in the checked candidates/context. That result does **not** establish final output, old cached-scope rejection or restored-backup exclusion: those phases were not reached. This version exercised explicit production retrieval exclusions, with physical deletion excluded from its compile manifest. Its limitations must not be attributed to the later frozen physical deletion coordinator.

Both arms ingested the full 2,191 synthetic days and 184,044 distractors according to execution progress, but were interrupted while evaluating the final questions. The last persisted reports and scoring rows cover only the completed first- and third-year checkpoints. There is no retained complete final result or canonical final database copy from these interrupted runs. All 288 completed checkpoint rows per arm are preserved without dropping failures, compressed as `scoring-rows.json.gz` with original and compressed byte hashes.

The observed packing faults motivated independent generic changes: old DEVICE candidates were not hydrated with the same priority as MESSAGE candidates; derived notes displaced original source text; and joined Korean group names could miss an alias stem. The frozen matrix, questions, source texts, gold values, writer output, query limits and evidence budget were unchanged. Fixes must be measured in their own complete frozen runs. One source score cannot inherit another version's regression test pass.

The first-year raw preparation p50/p95 was 672/1,465 ms; the third-year raw preparation was 2,993/9,276 ms. Writer preparation was 722/1,606 ms then 3,877/10,250 ms. These are native retrieval/Gateway timings on a shared, contended host, excluding model reasoning and phone/provider execution. Two live stack samples during the final phase found CPU work in the full-message SQL LIKE/ranking scan. This is a scalability concern; it is not evidence that model reasoning caused the delay.

No independent final answers, twofold answer-quality gain, physical-phone behavior or actual elapsed five years were measured in this intermediate phase. Paid API calls remained zero.
