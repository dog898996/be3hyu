# Frozen f2 native follow-up comparator

The original f2 + writer baseline was replayed with **only native reader state export added** to its frozen fixture. The unchanged production Store/Retriever completed 2,191 days, 184,044 distractors and all 432 checkpoint attempts in 121.101 seconds. Final delivery remained **111/144 cases, 218/396 required originals, 8/12 raw-only cases and six protected-value exposures**. This is the measured baseline gap, not a passed privacy or model-answer gate.

All production source hashes, 408 canonical source mappings/hashes, query/matrix bytes, writer notes, corpus digest, ranked original IDs, delivered source rows and scoring outcomes match the earlier baseline. The fixture changes from `dd6fc122…` to `86ab811e…` solely to copy actual private originals and export the existing hidden-device predicate. f2 has no retrieval-exclusion ledger or native source enumerator; those capabilities were not backported or fabricated.

Replaying f2 writer notes uses actual insertion timestamps. Fifty-five of 432 emitted context hashes changed through tied note ordering; sixteen context lengths changed. The source scoring and required delivery stayed unchanged. These differences are retained rather than presenting the rerun as byte-identical. For a fair continuation of the already frozen packet-only reader baseline, the preferred tool replay uses the **original canonical database and original packets**, binding only the verified export controls from this replay. The old database has no external original-file references; all 408 source identities/hashes match the export run. No source state or permissions were reconstructed from expected answers.

| Comparator state | Native replay directory | Final packet SHA-256 |
|---|---|---|
| Original baseline database/packets | `/tmp/hoyoung-five-year-f2-writer-tool-original-state` | `1fc53e1215fbd331bfb669d5e156e11b3169b017574839bb697b65e460b6e129` |
| Actual export-only rerun | `/tmp/hoyoung-five-year-f2-writer-tool-baseline` | `60915e0a53e034558282522682a53effb99b55c0cfc998e181854565fe0890c1` |

The original canonical SQLite hash is `5dc3c26bb60132f08ab5844bdb0732a349ae271b5498b7e2a6592d51ec522fc7`. Its 184,434 messages, 18 device events, 180 notes and 184,434 FTS message rows pass native integrity checking. Each reader receives the same registered allowance as V5: at most three search/enumeration attempts and twenty delivered/read source rows per opaque question, persisted between invocations. A new search or source read uses actual frozen f2 production code. An enumeration attempt produces an honest `ClassNotFoundException`, consumes an attempt, and does not fabricate a completed range. That is a measured product capability difference under equal allowances.

A separate-budget native smoke check compiled against exact f2 classes and verified search/read success plus unsupported enumeration in 6.683 seconds. It did not consume the actual comparator reader budget. Mechanical helper execution establishes tool availability, not correct model answers.

The previous packet-only semantic score cannot establish error halving against a V5 reader that receives follow-up tools. A tool-enabled independent baseline reader and separate semantic grading are still required. The existing source-delivery error ratio is explicitly a source metric. No new answer score, counterfactual answer pass or deployed-phone claim is assigned here.

The [artifact directory](benchmarks/five-year-memory-f2-tool-comparator/original-state-replay-receipt.json) retains the exact export-only patch/fixture, source manifest, corpus report, all 432 rows, both final packet sets, native controls, equivalence differences and smoke receipts. Canonical native databases remain local to avoid committing large execution files. See [native reader tools](five-year-native-reader-tools.md) and the separately preregistered [counterfactual protocol](five-year-memory-counterfactual-protocol.md).
