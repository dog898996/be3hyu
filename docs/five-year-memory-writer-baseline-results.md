# Frozen production with independently extracted notes

The second baseline uses the **same frozen production commit, corpus, questions, date cutoffs, source limit and context budget** as [the raw baseline](five-year-memory-baseline-results.md), with an independently authored source-only writer output added. It also **does not pass the final memory gate**. Better-looking derived notes are not sufficient when production ranking and packing fail to deliver the evidence needed to verify them.

The writer received 408 chronological original rows containing only `rawSourceId`, date, role and text; the input SHA is `4cd56fba4e706bbab20374fdbb2f6b6081851a66e258531c08e901251931843a`. Its output contains 192 notes, SHA `22b735a36acaf91a1be030b0610b7502c7d00e231e59157dd7d69f35ffa3edfa`. Exact quote links were mechanically validated against the original sources. The harness rejected all twelve notes referring to raw-only original details, as required by the preregistration, leaving 180 accepted notes. It did not modify a note or add an answer to make a test pass. There are 96 visible notes at the first checkpoint and 180 at later checkpoints.

This writer ran through the existing collaboration workflow and authored a packaging helper to link exact quotes and repeated source patterns. It is not the Android app's deployed Work extractor. Its assertion of source-only access is separate from the harness's checked reference/quote validity. The writer saw the full timeline before producing output; notes were then replayed at their latest referenced date. This does not establish causally isolated incremental model extraction, automatic processing of every real phone event, or measured general extraction accuracy.

| Checkpoint | Complete original delivery | Required originals delivered | Raw-only detail delivery | Protected-value leak cases |
|---|---:|---:|---:|---:|
| 2021-12-31 | 125/144 | 323/348 | 8/12 | 0 |
| 2023-12-31 | 111/144 | 218/396 | 8/12 | 6 |
| 2026-12-31 | 111/144 | 218/396 | 8/12 | 6 |

The final per-stratum source checks show 8/12 complete alias deliveries, 0/12 complete episode deliveries and 0/12 complete range deliveries. The writer sometimes expresses the needed relationship in a note, so a model may answer despite missing source delivery; that answer would require an independent grounded-answer score. Summary references are bounded, linked-source hydration is capped, and notes reserve much of the context before originals. In this run, relevant or merely same-topic notes displaced exact source texts. The fourteen-record ranges delivered only 15 of 168 required originals in total. A summary reporting fourteen steps is not itself proof that every original was read and delivered.

Final host query latency is p50 259 ms and p95 713 ms; complete replay took 114.731 seconds. The exported SQLite copy has 184,434 messages, 18 device events, 180 notes, 184,434 message FTS rows and `integrity_check=ok`. All 408 original record/text hashes remain intact. The chronological corpus digest is exactly the raw baseline's `9aa2228ab3f1eae27de0f817791bd80e9a911f9f1ff774c5b3a8bec6e87b7d6a`; note content is separately covered by the writer output SHA.

Grounded model answer accuracy, automatic forgetting command interpretation, managed cache/restore replay, actual Work calls and physical phone operation remain unmeasured. Successful harness execution is not a passed quality gate. Zero paid APIs were used. A matched improved-with-writer arm must ingest this exact output with the same rejection rules before attributing a difference to the new memory implementation. Comparing improved-with-writer to raw-without-writer would confound preprocessing with retrieval changes.

Evidence: [five-year-memory-f2-writer-baseline](benchmarks/five-year-memory-f2-writer-baseline/report.json), including all 432 scoring rows, source/fixture SHA manifest and 144 stripped final reader packets. The actual database is retained locally under `/tmp/hoyoung-five-year-f2-writer-baseline/canonical-corpus.sqlite` and is not committed.

```sh
python3 mobile/tools/run-five-year-memory-simulation.py \
  --notes-file /tmp/hoyoung-five-year-model-writer-output.json \
  --output /tmp/five-year-writer-baseline
```
