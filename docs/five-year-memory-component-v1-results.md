# Intermediate retrieval and privacy component: failed delivery target

This intermediate version is **not the final release gate**. Two identical frozen Store/Memory source snapshots were measured before further independently reviewed alias fixes and Gateway integration. `MemoryGateway.java` and the unfinished `SourcePayloadDeletion.java` were explicitly excluded from compilation and named in the source manifests. The real retriever, privacy ledger and source-visibility helper ran against the unchanged six-year corpus and all 432 checkpoint questions per arm. The final model-answer score remains unmeasured.

| Matched preprocessing | Frozen f2 complete source delivery | Intermediate complete source delivery | Final rare raw-detail delivery | Protected-value leak cases before → after |
|---|---:|---:|---:|---:|
| Raw originals only | 130/144 | 125/144 | 9/12 | 6 → 0 |
| Same independent writer output | 111/144 | 107/144 | 8/12 | 6 → 0 |

The complete-source-delivery error ratio is **1.357** in the raw pair and **1.121** in the writer pair: this metric worsened and did not halve. Some missing originals were unsupported historical assistant assertions omitted while a user's denial remained available; this can improve truthfulness while reducing an all-support-source score. These cases still count as measured delivery failures. It would be wrong to promote the unsupported assertions merely to improve the score, or to infer grounded-answer improvement without an independent reader.

Twelve explicit fixture owner controls were applied through the production exclusion ledger; all protected-value candidate/context exposures stopped, with zero blocked query cases. Six physical deletion requests were explicitly rejected as unsupported by that frozen component. Payloads remained locally retained. This run measures source exclusion once the owner control has been resolved; it does not measure a model correctly interpreting arbitrary deletion requests, physical erasure, managed cache rejection or old-backup replay. The final Gateway harness separately performs those replay checks using actual old prepared scopes and a streaming backup.

Both arms use the same 12,000-character budget, source limit 12, corpus digest and questions as their respective f2 comparators. The writer output and twelve raw-only-note rejections are unchanged. The two arms executed concurrently on this host; their latency cannot isolate code cost from CPU/I/O contention. Source integrity and harness completion passed, while the final quality gate remains unmet.

All checkpoint rows and exact source manifests are in [five-year-memory-sem-component-v1](benchmarks/five-year-memory-sem-component-v1/paired-summary.json). Original packets and database copies remain under `/tmp/hoyoung-five-year-sem-frozen-{raw,writer}-v1`. The subsequent alias repair was prompted by an independent generic two-hop regression, not a changed gold question or answer. Its results must be measured in a new frozen run before claiming a gain.
