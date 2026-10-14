# Final-source V6: unchanged questions, two traced packet differences

The latest release-source snapshot completed all **432 checkpoint attempts per arm** on the unchanged six-calendar-year workload. It freezes Gateway `8a242b67…`, Retriever `d41d1481…`, SourceRecordVisibility `66695bec…` and the unchanged Korean compound-alias tokenizer. The shared freeze manifest SHA-256 is `3a24c793f7d4c865e570cfd0821be6734c1d438738265883f6169461dd8fdb31`. No query, source value, writer note or matrix was adjusted.

| Final native measurement | Raw originals | Same independent writer notes |
|---|---:|---:|
| Complete source delivery | 143/144 | 132/144 |
| Required exact original bodies | 395/396 | 383/396 |
| Raw-only first-year cases | 12/12 | 12/12 |
| Candidate/context leaks; blocked cases | 0; 0 | 0; 0 |
| Native preparation p50/p95 | 494/1,460 ms | 535/1,602 ms |
| JUnit workload duration | 256.522 s | 310.074 s |

The matched f2 source-delivery error ratios are 0.0714 for raw and 0.3636 for writer. Both halve **source-delivery errors**, not automatically semantic answer errors. Writer omissions remain recoverable through actual bounded native follow-ups; derived source validation alone does not prove model correctness. Shared-host timing includes concurrent load and excludes model reasoning, physical phone and deployed Work calls.

The final raw regression is one alias question. Its actual explicit-relationship query started, then crossed a 350 ms wall deadline before consuming returned rows. Native diagnostics show one query, three initial hydrated sources, zero added candidates, `limitReached=true` and no privacy exclusion. The alias cue remains visible; the related owner original did not reach that packet. The current loop checks the wall deadline inside the row loop after SQL returns, although its report calls it an operation-start budget. This is a traced availability limit, not lost canonical data. An actual fresh reader/search trace is required for the changed packet; it cannot inherit the older answer by assumption.

Exact final packet comparison against V5 used **no normalization**. Raw has 143 byte-identical evidence contexts and one genuinely changed context. Writer also has 143 byte-identical contexts; its remaining difference is confined to the footer: `sourceGraphRead.cachedVerdictHits` changes 262→266 and `omittedOptionalItems` changes 72→73. All writer bytes before `MEMORY_LIMITS`, all source/date/hash/status/constraint rows, all non-evidence packet fields, and all 408 canonical source mappings/hashes are unchanged. The exact differences and changed reader packets are retained. Any answer carry-forward must identify its actual context equivalence or obtain a new independent answer; the simulation assigns none itself.

All twelve actual first-year cached scopes were rejected after privacy changed in both arms. The actual first-year backup could not revert current policy; six restored originals were re-purged, with zero pending managed-local deletions; all twelve restored queries completed without protected-value exposure or blocking. This verified scope excludes already delivered bytes, RAM, provider archives, external/untracked copies and policy after reinstall/data clear.

Each run still covers 2,191 days, 184,044 distractors and 408 original definitions. Six explicit deletions leave 402 hash-verified originals, 184,428 messages and 18 device events. Writer retains 180 accepted notes, with the same twelve raw-only notes rejected. Database and FTS counts and integrity checks pass. The source-only writer saw the whole timeline together; causal incremental extraction and actual years of deployment remain unverified.

[Artifacts](benchmarks/five-year-memory-final-source-v6/paired-summary.json) retain both exact source/fixture manifests, all 432 rows per arm, final and changed blinded packets, candidate recall, paired source outcomes, source integrity counts, cache/restore receipts, latency and exact V5 comparison. Local native replay state is `/tmp/hoyoung-five-year-final-gateway-{raw,writer}-v6`. Final semantic scores, bounded follow-up traces and counterfactual judgments are separate independent-review artifacts; this source report does not close the user's final gate.
