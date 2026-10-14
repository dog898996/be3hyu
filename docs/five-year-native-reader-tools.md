# Independent native reader follow-ups

The reader receives only `id`, dated question, and actual emitted evidence. It must request its own searches or source reads. The helper never reads the gold matrix, case labels, source-map fixture labels, scoring rows, or expected answers. Primary packet correctness and retrieval-only source delivery remain separate scores.

`mobile/tools/run-five-year-memory-reader.py` reopens the run's exact canonical native SQLite corpus with its saved privacy ledger and device visibility. It compiles the helper against the **already frozen production classes**. It does not rebuild the app or call a paid API. The simulation must have exported `canonical-corpus.sqlite`, `native-reader-control-snapshot.json`, and `native-reader-files` before the destructive old-backup replay. Older benchmark runs without this state must be replayed from their frozen production source snapshot; policy must not be reconstructed from a gold matrix.

```sh
python3 mobile/tools/run-five-year-memory-reader.py \
  --run /tmp/hoyoung-five-year-final-gateway-raw-v5 \
  --requests /tmp/independent-reader-requests.jsonl \
  --output /tmp/independent-reader-responses.jsonl
```

Each request is one JSON object. The `id` must be the primary packet's opaque ID. Native source IDs and hashes can be taken from that packet or a prior returned source; they cannot be selected from gold.

```json
{"id":"opaque-packet-id","op":"search","args":{"query":"reader-chosen search wording","limit":8}}
{"id":"opaque-packet-id","op":"read","args":{"type":"message","sourceId":17,"expectedHash":"native-record-sha256","offsetChars":0,"maxChars":4000}}
{"id":"opaque-packet-id","op":"enumerate","args":{"type":"message","fromInclusive":"2021-01-01","toExclusive":"2022-01-01","containsLiteral":"reader-chosen literal","limit":20}}
```

`search` invokes actual `MemoryRetriever.search` with the frozen 2026-12-31 upper date fence; a reader's narrower historical `asOf`/`toExclusive` bounds are retained. `read` invokes actual original hydration after native identity and privacy checks. `enumerate` invokes actual `MemorySourceEnumerator`, including its scope fingerprint, cutoff, continuation cursor, and explicit completion limits. A literal SQL match is not a guarantee of semantic completeness. Long-original matching coverage can be unverified; sources may also have partial text.

For each packet the helper enforces at most **three search/enumeration requests and twenty returned/read source rows**. Budgets persist in the measured run's separate `native-reader-budget.json` across invocations, under a process lock. The search group limit is bounded by half the remaining row allowance because the production retriever may return two originals per group; its first-call maximum is ten groups. Returned search candidates count toward the row cap, including repeated reads. The limit concerns sources delivered to the reader; internal production retrieval may inspect additional candidate records. Primary packet evidence does not consume these follow-up allowances. Failed attempts remain logged. The helper refuses to overwrite earlier response artifacts.

Every invocation produces the response JSONL, execution log, and a receipt with the production manifest, helper, request, response, and before/after budget hashes. The evaluator must join responses back to primary opaque IDs, record actual follow-ups, check cited native sources/quotes, and grade answer content independently. Successful helper execution does not mean an answer or the final five-year acceptance gate passed.

An independent SDK28 native SQLite smoke test checks allowed original reads, forgotten original read and enumeration exclusions, the three-search/twenty-row limit, and limit persistence across invocations. Its fixture uses unrelated synthetic names and values; it does not read or tune benchmark questions.

The [frozen f2 comparator](five-year-memory-tool-comparator-results.md) can use the same helper and allowances with `/tmp/hoyoung-five-year-f2-writer-tool-original-state`. It retains the original database and packets. f2 supports actual search/read but lacks `MemorySourceEnumerator`; unsupported enumeration is logged rather than implemented in the harness. Equal tool allowances do not imply equal production capabilities, and a packet-only baseline answer score cannot be used as the paired semantic comparison for a tool-enabled improved reader.
