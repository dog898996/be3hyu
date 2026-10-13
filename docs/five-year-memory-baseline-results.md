# Six calendar years: frozen production baseline

This run **does not pass the user's final five-year-old recall gate**. It executes chronological storage and bounded source delivery using the real production `HoyoungStore` and `MemoryRetriever` at commit `f2e67c6fced3db82b1950c7c9382e57094fd2b6e`. No language model answered the questions in this run. A successful JUnit execution means the harness finished and integrity assertions passed; it does not mean memory quality passed.

The preregistered v2 matrix remains unchanged, SHA-256 `2821867ebb826cb2b873ef5d95e5abbbbf4b9a60923a2755f681e6c26e6ae1c8`. Sources span all twelve months of 2021. The final checkpoint is 2026-12-31, so every first-year source is at least five whole calendar years old. The synthetic replay covers 2,191 days, including the leap day in 2024. This is not five actual years of phone use.

| Checkpoint | Cases with all required original texts delivered | Required original texts delivered | Raw-only exact-detail cases delivered | Cases exposing an excluded secret |
|---|---:|---:|---:|---:|
| 2021-12-31 | 131/144 | 331/348 | 11/12 | 0 |
| 2023-12-31 | 130/144 | 378/396 | 10/12 | 6 |
| 2026-12-31 | 130/144 | 378/396 | 10/12 | 6 |

All 432 checkpoint observations were attempted. They are three observations of the same 144 scenarios, not 432 independent questions. Required sources that had not yet occurred were omitted from that checkpoint's denominator. For post-deletion questions, the protected historical value is not a required delivered source; the current owner deletion request is. Retrieval of that control request does not excuse exposure of the protected source.

The final source-delivery score is 90.28% of scenarios and 95.45% of individual required source records. Neither percentage measures grounded answer accuracy. Twelve alias questions lacked one or both required original sources: a first source defines the alias, while a separate source identifies the person. Two raw-only questions asked for an identification number using `식별번호`, while the original calls it `접수번호`; the bounded packet selected other subtasks sharing the topic prefix. These failures show the need for evidence-derived multi-hop recovery and more precise entity/task ranking.

Six owner-requested message-forgetting cases exposed synthetic protected values in both candidate output and the emitted context. The frozen implementation has no canonical message forgetting API, so the fixture deliberately persisted deletion requests without pretending to execute them through test-only SQL. Six device revocation cases used the production retriever's visibility predicate with fixture-supplied source exclusions; this tests that predicate, not automatic phone permission parsing, a durable deletion ledger or restore replay. No leak, cache or restore test inherits a pass from a reader refusing to repeat a secret.

The corpus contains 184,044 deterministic non-owner distractor messages and 408 original sources: 390 messages and 18 device events. The final database has 184,434 messages, 18 device events and **zero derived memory notes**. Every supplied original text and canonical record hash was checked after the complete replay. Native production close/reopen occurred at all three checkpoints. A separately opened exported SQLite copy reports `integrity_check=ok`, matching message, event and FTS counts. The copy is 136,040,448 bytes, excluding photo/video payloads and neural indexes. The corpus construction digest is `9aa2228ab3f1eae27de0f817791bd80e9a911f9f1ff774c5b3a8bec6e87b7d6a`.

The host used JDK 17 and Robolectric SDK 28 with native SQLite. The actual production lexical fallback was run with no installed semantic encoder, source limit 12 and a 12,000 UTF-16-character context budget. There were no oracle entity filters, answer-bearing derived notes, manually supplied aliases, query rewrites or model follow-up searches. Query `asOf` and record cutoff were the checkpoint's next UTC midnight. The retriever may select up to twice its source limit. A fourteen-record range therefore happened to fit in this fixture; that is not proof of general exhaustive range pagination. The final query latency was p50 237 ms and p95 742 ms on this host; real phone, model response, cold startup and Work wire latency were not measured. The replay completed in 104.834 seconds.

No paid API, deployed Work call or physical phone execution occurred. The original RFC describes algorithms and contracts but has no independently measured reference accuracy; this baseline must not be renamed “RFC accuracy.” No doubled accuracy, halved grounded-answer error, perfect long-term personalization or complete physical erasure is established here. The full registered acceptance gate remains unmet.

The first complete replay reached all checkpoints and verified original hashes, then encountered a harness-only artifact-export error: SQLite's row-returning WAL checkpoint pragma had been called through `execSQL`. Changing that call to `rawQuery` and rerunning the identical corpus/questions/configuration completed successfully. No gold answers, question selection or acceptance thresholds changed.

Artifacts are in [five-year-memory-f2-baseline](benchmarks/five-year-memory-f2-baseline/report.json): production source hashes, the exact fixture used, all checkpoint scoring rows, final per-stratum results, execution log and stripped final reader packets. Reader packets contain opaque IDs, ordinary questions, dates and actual emitted evidence, without gold answers or scenario labels. A fresh independent reader must still answer and be scored against the frozen oracle. The exported database is retained locally under `/tmp/hoyoung-five-year-f2-baseline/canonical-corpus.sqlite` and is not committed.

Run with the build owner's provisioned SDK/JDK/dependency cache, without Gradle:

```sh
python3 mobile/tools/run-five-year-memory-simulation.py --output /tmp/five-year-baseline
```

The runner freezes every production store/memory source file before compiling. `--source-ref WORKTREE` supports a separately hashed improved arm. `--notes-file FILE` is a separate writer-derived-note arm: it validates original references, rejects derived notes linked to raw-only sources, and replays notes at the latest referenced date. A full-timeline writer can know later facts; replaying its output at reference dates does not establish a causally isolated incremental extraction process. Writer execution, semantic extraction correctness and reader answer quality require their own evidence.
