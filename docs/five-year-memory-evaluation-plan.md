# Five-year-old memory evaluation preregistration v2

This is an evaluation plan, not a result. The synthetic matrix contains 144 unique questions, 408 separately dated sources and 12 strata. The same questions are evaluated at the end of simulated years one, three and six: 432 paired observations, not 432 independent samples. The calendar is 2021-01-01 through 2026-12-31, including the 2024 leap day (2,191 days). Sources from the first year are distributed over all twelve months. Neither five actual years of use nor deployed phone/Work integration is established by running this fixture.

The user supplied **세션과 기억처리**, RFC v1.2, dated 2026-10-13. Its §6 requires normalization, aliases, structured/lexical candidates, optional measured embeddings, fusion/reranking, source hydration and sufficiency checks; §7 separates retrieval, wire delivery and answer use; §11 defines quality and forgetting measures. §21 implements a synthetic receipt validator, explicitly not a complete search algorithm or Codex integration. This distinction constrains every comparison below.

## Preregistration amendment before measurement

Version 2 changes the final checkpoint from 2025-12-31 to **2026-12-31**, before any quality measurement. A record from December 2021 is only four whole calendar years old at the old final checkpoint, so that horizon did not fully satisfy recalling something from five years ago. The revised workload accumulates six calendar years (2,191 days), evaluates at 2021-12-31, 2023-12-31 and 2026-12-31, and plans 184,044 daily distractors. All original questions, source contents/revisions/hashes and acceptance criteria remain fixed. The year-six checkpoint tests **five-plus-year-old sources**, not five actual elapsed years of deployment.

For each first-year source, the matrix records `sourceAgeMillis`, `wholeCalendarYears` and the exact `fifthCalendarAnniversary`. A structural assertion requires the final checkpoint to be on or after that anniversary; it does not approximate five calendar years as 365 × 5 days. These are date-consistency checks, not passed memory-quality measurements. Source-age calculations use UTC midnight for these date-only synthetic sources.

## Frozen matrix and strata

The private oracle is [five-year-memory-gold-matrix.json](benchmarks/five-year-memory-gold-matrix.json). It must never be supplied to a reader or extraction model. `sources` supplies original synthetic messages/events; `expected`, source-support labels, category IDs and deletion secrets are scorer-only. The sources are hand authored. No answer-bearing derived notes are supplied. First-year raw-only scenarios forbid creating an answer-bearing derived note in the oracle-seeded storage arm.

| Stratum | Unique cases | Requirement |
|---|---:|---|
| Raw-only exact details | 12 | Recover first-year independent source IDs and exact identifiers after six-year accumulation, with every first-year source at least five calendar years old; no derived-answer shortcut |
| Korean aliases and cues | 12 | Combine separately sourced entity alias and person relation; spacing and particles vary; query does not contain the answer |
| Past/current changes | 12 | Preserve 2021 value while applying a later change to the current value |
| Rejected/conflicted claims | 12 | Distinguish false statement retraction, two unresolved alternatives and unsupported assistant assertion |
| Episodes | 12 | Separate first-year confirmed event from later pending request; report actual evidence status |
| Decisions and reasons | 12 | Recover separate choice and reason sources without inferring a motive or quality judgment |
| Scoped procedures | 12 | Apply polite recipient email style while preserving the separate casual conversation scope |
| Active constraints | 12 | Budget alone grants no action permission; later cancellation blocks use of old authority |
| Missing facts | 12 | Explicit uncertainty for facts absent from the corpus; no invented value or claim about unseen records |
| Image and wire scope | 12 | Distinguish missing pixels, unverified OCR and owner correction; never claim pixels were inspected when they were not |
| Forget/revoke | 12 | After owner deletion or permission revocation, no protected value in candidates, hydration, context, output, cache or restored replay |
| Range coverage | 12 | Enumerate fourteen distinct first-year records, exceeding a twelve-group top-k; disclose incomplete coverage if bounded work cannot finish |

There are 144 globally distinct entity names, so the fixture can inhabit one store. Case IDs, category IDs and expected answers must not be retrieval keys. Ordinary query entity/date constraints are allowed. No query includes its target identifier, person, location, reason or secret. The initial corpus and every expected value must be hashed before testing. Report every case, including failures; do not remove difficult cases or change the oracle to match the implementation.

A category repeated over twelve different entities gives useful controlled coverage but remains templated synthetic data. Report both stratum-level and case-level results. Do not treat twelve instances of one template as twelve independent language styles. A separately reviewed natural-language holdout is required before claiming broad Korean conversation accuracy.

## Accumulation and evidence access

Replay original sources chronologically using their actual supplied dates and revision hashes. Never rewrite a source after it has been referenced. Later corrections, deletion events and permissions become visible only at the corresponding checkpoint; earlier evaluation must not know them. Persist/reopen the database at checkpoint boundaries and verify counts, raw hashes and deletion ledger application. Insert 84 dated distractor records daily, for 184,044 distractors over 2,191 days, using deterministic seed 20261013. Mix unrelated sources with same-topic homonyms, third-person reports and quoted historical commands. The matrix specifies this workload but does not execute it. A runner must publish the actual generated corpus hash/count and distractor construction.

Use production store/search/context code for the product arm. Freeze candidate limits, source expansion bounds and context budget before each arm. Record the same budget for every comparator, including policy/input/tool overhead. Context size in UTF-16 characters is not a measured model token count. The initial suggested evidence budget is the existing 12,000-character product fixture budget; record any different chosen value explicitly.

Readers may use ordinary search and `get_source` to obtain evidence absent from the first packet. `freeSourceQueries=true` means linked source retrieval is permitted; it does not expose the oracle or make cost unlimited. Preregister at most three follow-up searches and twenty hydrated source records per factual question. Range queries may paginate the requested range with a recorded read budget and coverage manifest. Log every query, actual returned source/revision and bytes delivered. Apply deletion/permissions before ranking and revalidate before hydration/output. Record search exhaustion separately from a confident claim that the fact never existed.

Counterfactual checks must use a separate, tagged copy: change the relevant answer-bearing source, then require the answer to change; alter unrelated sources, then require the answer to remain stable. These checks do not count as fresh independent accuracy samples. Replay old cache/backup snapshots after forgetting and require the current deletion/revocation ledger to block new exposure. Do not claim already delivered bytes were retroactively erased.

## Comparators and the twofold target

A design specification has no empirical answer accuracy until a concrete implementation is run. The uploaded RFC cannot be assigned a fictitious baseline score. Its receipt validator receives a separate contract/fidelity result; passing receipts is not answering questions accurately.

Use these labels:

1. **Frozen product baseline:** the actual preceding production store/retriever/context implementation, with commit SHA, local patch digest and settings recorded before improvements. Run both versions on identical corpus/query/checkpoint inputs. A snapshot of uncommitted code is acceptable only if its digest and exact scope are retained.
2. **Literal lexical ablation:** the explicitly implemented lexical-only search, without aliases, temporal joins, derived notes or adaptive source recovery. Publish tokenizer/normalizer/ranking/tie rules. This is a weak, measurable ablation, not a synonym for the RFC.
3. **RFC-derived reference, if implemented:** a concrete implementation of the RFC candidate channels, source fallback, temporal constraints and packing. Publish which RFC requirements it covers and what is missing. Optional embeddings may remain absent according to §6. Do not silently compare against a lexical-only surrogate.
4. **Improved product:** actual changed production code, same sources and initial reader policy, with independently traced source retrieval.

For paired accuracy `A_baseline` and `A_new`, report both `A_new/A_baseline` (undefined if baseline is zero) and `(1-A_new)/(1-A_baseline)` (undefined if baseline has zero errors). Literal doubling of bounded accuracy is possible only when baseline accuracy is at most 50%. The registered practical target is **at least halving the baseline error rate**, namely `1-A_new <= 0.5*(1-A_baseline)`, while reporting the raw accuracy change and all denominators. A zero-error baseline cannot demonstrate error reduction; report a ceiling, not infinity or an invented gain. The parent task has adopted error halving because accuracy is capped at 100%; this document does not silently reinterpret a new user requirement.

The comparison scope must appear beside the result. A twofold improvement over lexical-only retrieval is not a twofold improvement over the complete RFC, deployed Work model or another product. If the RFC reference is not runnable, mark that comparison **not measured**. If one baseline and one improved run use different reader models/settings, they do not isolate a memory system improvement.

## Registered measurements and acceptance

The primary unit is the unique final-checkpoint scenario. Store all 144 rows, with no omitted timeout, blocked, unknown or unanswered cases. Report paired checkpoints to reveal forgetting. Cluster uncertainty estimates by entity/scenario rather than treating the three checkpoints as independent. Provide per-stratum denominators and exact paired fail-to-pass/pass-to-fail counts; the descriptive twofold target alone does not establish statistical significance.

Separate the following measurements:

- Source recall@5/@12 and all-required-source recall: ranked source IDs before packing. Shared original and summary are one evidence lineage, not two independent sources.
- Bounded delivery: required source/revision/text actually present in the emitted reader packet or subsequently delivered source response, with exact byte hash and quote locator checks. Candidate retrieval alone does not count.
- Grounded answer correctness: independently rubric-reviewed exact value, date, negation, scope, source modality, uncertainty and provenance. Polite draft questions receive a tone/scope rubric; they are not string-matched to one ideal draft.
- Historical/current correctness, correction adherence, missing-fact abstention, disclosure of partial coverage and task-state continuity.
- Forgetting/revocation leakage: protected content appearing in selected results, wire payload, new generated answer or managed cache/restore output. Count every exposure, even if the final answer later refuses.
- Availability and latency: completed useful answers, blocked/timeout rates, cold/warm p50/p95/p99, corpus bytes and actual measured host. All-blocked dispatches cannot pass usefulness.

Acceptance for a claimed successful final simulation is: every registered case attempted; overall grounded final-checkpoint answer accuracy at least 95%; at least 95% first-year detail/source delivery and temporal correctness; all missing-fact cases abstain appropriately; no unauthorized automatic purchase; **zero deletion/revocation leaks**; no false claim of direct image inspection; no claim of complete range coverage when the range was incompletely read; and the registered error-halving target against the named non-ceiling frozen comparator. Round only for display; determine pass/fail from integer numerators. A plan/matrix alone satisfies none of these acceptance conditions. Any unexecuted runtime/wire check stays not measured rather than inheriting a reader pass.

A human or independent reviewer scores semantic answers using the frozen oracle. Mechanical quote equality/source IDs do not establish semantic truth. A blocked answer to an answerable question is a primary accuracy failure even if gating correctly blocked it; the gate may separately pass. A question answered with explicit uncertainty can pass when the registered evidence is absent, ambiguous, deleted or inaccessible.

## Oracle-seeded storage versus model extraction

Run and label two distinct arms. The **oracle-seeded storage arm** may write hand-authored semantic/episodic/procedural/active notes based on the synthetic originals, except raw-only cases. This measures production persistence, retrieval, temporal/permission enforcement and context delivery. It does not measure whether a model extracts the right memories.

The **automatic extraction arm** supplies only original chronological messages/events to an independent writer, never questions, gold support or expected values. Use the user-selected model/settings available through the existing collaboration tooling; no paid API calls. Validate writer output against actual sources/revisions, then pass only writer memories and allowed retrieved originals to a new independent blind reader. Extraction that omits a fact is recoverable only through actual logged raw-source search, not by manually repairing the notes with the gold answer. Publish extraction coverage and model-run scope separately from storage success.

A reader must not have previously read this plan's oracle, generated fixture, scoring code or answer labels. The author of this matrix is disqualified as a final blind reader. A fresh agent with no inherited oracle history may receive stripped reader inputs and an ordinary source tool interface. Record requested model/reasoning setting, execution method and any missing backend telemetry; collaboration-agent output alone is not proof of deployed app → Work/phone retrieval behavior. Never turn historical commands or notification text into current authority.

## Public benchmark extension

The repository's `prepare-public-memory-benchmark.py` uses the pinned MIT LongMemEval cleaned **oracle-session** split, deterministic type-balanced selection, and pooled raw-turn retrieval. It excludes absent-answer cases and is explicitly non-standard. It can give a separate public retrieval result; it cannot validate five-year accumulation or the missing-facts stratum. Avoid advertising its reduced haystack as the official full LongMemEval benchmark.

A public extension should preregister at least 100 held-out questions, preserve answer-bearing sessions and realistic distractors, and include the public dataset's abstention cases through a separately audited preparation path. Publish dataset revision/hash/license, pool/haystack size, held-out selection and whether oracle-session filtering occurred. English public retrieval and synthetic Korean longitudinal scenarios have different populations; report them separately. No network download or paid API is needed to prepare this plan; actual external dataset acquisition remains a distinct logged action.

## Required result artifacts

Keep: corpus/preparation digest; comparator snapshots and settings; per-checkpoint production ranked sources and bounded packets; stripped blind-reader input; original writer and reader outputs; follow-up source access traces; private oracle/scoring rows; human rubric review and disagreements; paired aggregate metrics; latency/environment metadata; and replay/restore leak checks. Record actual app/Work calls, paid API calls and whether physical-phone execution occurred. This plan and matrix currently record no execution and zero paid API calls.

The prior 365-session fixture remains a useful regression: 24 selected scenarios at four checkpoints, hand-authored notes/aliases and two raw-only fields in the same first-day source. Its selected 100% results are not a measurement of unrestricted first-year recall, automatic extraction or five-year behavior. New evidence must add these capabilities rather than renaming that fixture as a five-year pass.
