# Actual V5 writer follow-ups replayed on V7

The independent writer reader's **23 actual V5 source operations** were replayed unchanged against a copied, exact V7 native snapshot: **eleven original reads and twelve range enumerations**, each with its original opaque ID and arguments. All succeeded in **4.359 seconds**, delivering 179 source rows. This is a native comparator replay, **not another model-answer run or a reset of a reader's allowance**.

All delivered source objects are exactly equal: native type/ID, full text bytes, canonical/content hashes, role, recorded date, offsets, lengths, stored-original and completion flags. All request dates/literals/limits, returned query scopes/cutoff IDs, matching/scanned/excluded counts, continuation positions, range completion/coverage flags, and per-question search/row counters also match. Each source is checked against both actual canonical databases. No evidence text or provenance was normalized.

| Actual response comparison | Count |
|---|---:|
| Entire typed read response identical | 11/11 |
| Enumeration source rows and every non-fingerprint field identical | 12/12 |
| Successful actual native operations | 23/23 |

Each enumeration differs **only** in `privacyFingerprint` and `scopeFingerprint`. Both snapshots have policy version 12 and the same typed exclusion IDs/canonical hashes, modes, reasons and hidden-device predicate IDs. Their actual exclusion-recording timestamps differ. The ledger's fingerprint includes those timestamps, while native source availability uses the exclusion identities; it does not expire exclusions by those timestamps. The exact old/new policies and every difference are retained. Both enumeration scope hashes were independently recomputed from the actual respective policy fingerprint, unchanged query/counts/cutoff and the identical canonical cutoff record hash. These are different authenticated snapshot identities, not interchangeable pagination tokens. No old pagination token was reused.

The replay uses `/tmp/hoyoung-five-year-writer-tool-comparator-v5-v7/comparator-only-v7-state`, containing a **copy** of V7's database, private original/policy files, frozen classes and source manifest. Original V5/V7 budget files were locked during replay and have the exact same before/after hashes. The new namespace has its own recorded comparator budget. Replayed results were not delivered to the blind reader as extra evidence, and the replay generated no expected answer or semantic score.

This supplies the follow-up provenance missing from primary-packet equality alone. It supports independent review of the 142 unchanged V7 writer primary packets together with their actual prior tool evidence; the two changed primaries have separate actual re-evaluation. It does not itself establish answer correctness, physical phone behavior, deployed Work use or application-wide permission guarantees.

[Artifacts](benchmarks/five-year-memory-writer-followup-equivalence-v5-v7/followup-equivalence-report.json) retain the exact original 23 requests, original actual responses and native receipts, actual V7 replay responses/log/receipt, both source/control/policy manifests, unchanged original budget hashes, and a per-operation source/coverage/scope comparison. All benchmark content is synthetic; the large native database remains outside Git.
