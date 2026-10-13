#!/usr/bin/env python3
"""Prepare blinded synthetic inputs / score mechanical evidence; never calls an LLM.

Semantic extraction and answer correctness require a separate reviewed rubric.
The scorer cannot prove how an operator obtained a supplied result file.
"""
import argparse
import hashlib
import json
from pathlib import Path

DEFAULT_FIXTURE = Path(__file__).resolve().parents[1] / "tests/fixtures/model-memory-evaluation.json"


def utf16_length(text):
    return len(text.encode("utf-16-le")) // 2


def source_map(case):
    sources = {("message", m["id"]): m for m in case["messages"]}
    sources.update({("device_event", e["id"]): e for e in case["deviceEvents"]})
    return sources


def validate_fixture(fixture):
    ids = set()
    for case in fixture["cases"]:
        if case["caseId"] in ids:
            raise ValueError("Duplicate case ID")
        ids.add(case["caseId"])
        sources = source_map(case)
        if len(sources) != len(case["messages"]) + len(case["deviceEvents"]):
            raise ValueError("Duplicate original source ID")
        for ref in case["golden"]["supportSources"]:
            if (ref["type"], ref["id"]) not in sources:
                raise ValueError("Golden source does not exist")
    return fixture


def prepare(fixture, folder):
    validate_fixture(fixture)
    folder.mkdir(parents=True, exist_ok=True)
    inputs = []
    questions = []
    golden = []
    for case in fixture["cases"]:
        # The writer receives no expected answer, source labels or rubric.
        inputs.append({k: case[k] for k in ("caseId", "messages", "deviceEvents", "archiveCoverage")})
        questions.append({"caseId": case["caseId"], "question": case["question"]})
        golden.append({"caseId": case["caseId"], **case["golden"]})
    artifacts = {"writer-inputs.json": inputs, "questions.json": questions, "golden.json": golden}
    hashes = {}
    for name, data in artifacts.items():
        raw = (json.dumps(data, ensure_ascii=False, indent=2) + "\n").encode()
        (folder / name).write_bytes(raw)
        hashes[name] = hashlib.sha256(raw).hexdigest()
    manifest = {"schemaVersion": fixture["schemaVersion"], "caseCount": len(inputs),
                "synthetic": True, "standardLongMemEval": False, "actualLmMeasured": False,
                "actualWorkCalls": 0, "paidApiCalls": 0, "artifactSha256": hashes}
    (folder / "preparation-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


def exact_quote(ref, sources):
    source = sources.get((ref.get("type"), ref.get("id")))
    quote, offset = ref.get("quote"), ref.get("offsetChars", 0)
    if not source or not isinstance(quote, str) or not quote.strip():
        return False
    if type(offset) is not int or offset < 0:
        return False
    text = source["text"].encode("utf-16-le")
    expected = quote.encode("utf-16-le")
    start = offset * 2
    try:
        excerpt = text[start:start + len(expected)].decode("utf-16-le", errors="strict")
    except UnicodeError:
        return False
    return excerpt == quote


def score(fixture, predictions, k=5):
    validate_fixture(fixture)
    indexed = {}
    for prediction in predictions:
        if prediction.get("caseId") in indexed:
            raise ValueError("Duplicate prediction case ID")
        indexed[prediction.get("caseId")] = prediction
    wanted_ids = {case["caseId"] for case in fixture["cases"]}
    if set(indexed) - wanted_ids:
        raise ValueError("Unknown prediction case ID")
    metrics = []
    for case in fixture["cases"]:
        prediction = indexed.get(case["caseId"])
        gold = {(r["type"], r["id"]) for r in case["golden"]["supportSources"]}
        if prediction is None:
            metrics.append({"caseId": case["caseId"], "resultMissing": True,
                            "goldSourceCount": len(gold),
                            "fractionalSourceRecallAtK": 0.0 if gold else None,
                            "anyGoldSourceHitAtK": False if gold else None,
                            "allGoldSourcesHitAtK": False if gold else None})
            continue
        sources = source_map(case)
        retrieval_measured = isinstance(prediction.get("rankedSources"), list)
        ranked = prediction.get("rankedSources", [])
        top = {(r.get("type"), r.get("id")) for r in ranked[:k]}
        hits = len(gold & top)
        refs = [r for m in prediction.get("memories", []) for r in m.get("sourceRefs", [])]
        quotes = [ref for ref in refs if "quote" in ref]
        invalid_sources = sum((r.get("type"), r.get("id")) not in sources for r in refs)
        verified_quotes = sum(exact_quote(ref, sources) for ref in quotes)
        dangerous_coverage = not case["archiveCoverage"]["complete"] and prediction.get("coverage", {}).get("completeArchive") is True
        metrics.append({"caseId": case["caseId"], "resultMissing": False,
                        "retrievalMeasured": retrieval_measured,
                        "fractionalSourceRecallAtK": hits / len(gold) if gold and retrieval_measured else None,
                        "anyGoldSourceHitAtK": bool(hits) if gold and retrieval_measured else None,
                        "allGoldSourcesHitAtK": hits == len(gold) if gold and retrieval_measured else None,
                        "goldSourceCount": len(gold), "invalidSourceReferences": invalid_sources,
                        "quoteCount": len(quotes), "exactQuoteCount": verified_quotes,
                        "exactQuoteReferenceAccuracy": verified_quotes / len(quotes) if quotes else None,
                        "requestedActionCount": len(prediction.get("actions", [])),
                        "partialArchiveFalselyReportedComplete": dangerous_coverage,
                        "semanticExtractionAccuracy": None, "answerAccuracy": None})
    recall_rows = [r for r in metrics if r.get("goldSourceCount", 0) > 0 and r.get("fractionalSourceRecallAtK") is not None]
    return {"schemaVersion": fixture["schemaVersion"], "synthetic": True,
            "standardLongMemEval": False, "generationVerifiedByThisScorer": False,
            "actualWorkCallsByThisScorer": 0, "paidApiCallsByThisScorer": 0,
            "k": k, "caseCount": len(metrics),
            "missingCases": sum(r["resultMissing"] for r in metrics),
            "macroFractionalSourceRecallAtK": sum(r["fractionalSourceRecallAtK"] for r in recall_rows) / len(recall_rows) if recall_rows else None,
            "macroAllGoldSourcesHitAtK": sum(r["allGoldSourcesHitAtK"] for r in recall_rows) / len(recall_rows) if recall_rows else None,
            "semanticExtractionAccuracy": None, "answerAccuracy": None, "cases": metrics}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["prepare", "score"])
    parser.add_argument("--fixture", type=Path, default=DEFAULT_FIXTURE)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--predictions", type=Path)
    parser.add_argument("--k", type=int, default=5)
    args = parser.parse_args()
    fixture = json.loads(args.fixture.read_text())
    if args.mode == "prepare":
        result = prepare(fixture, args.output)
    else:
        if args.predictions is None or args.k < 1:
            parser.error("score requires --predictions and --k >= 1")
        result = score(fixture, json.loads(args.predictions.read_text()), args.k)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: result[key] for key in result if key not in {"cases", "artifactSha256"}}, ensure_ascii=False))


if __name__ == "__main__":
    main()
