#!/usr/bin/env python3
"""Build a local, non-standard pooled retrieval fixture from the official MIT oracle split.

Input is downloaded separately from the public pinned dataset. No LLM/API calls.
The script copies no dataset into the app/repo unless the caller explicitly chooses that path.
"""
import argparse
import hashlib
import json
from collections import OrderedDict, Counter
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("source", type=Path)
parser.add_argument("output", type=Path)
parser.add_argument("--questions", type=int, default=40)
parser.add_argument("--pool", type=int, default=100)
parser.add_argument("--question-offset", type=int, default=0)
args = parser.parse_args()
if not 1 <= args.questions <= args.pool <= 500 or args.question_offset < 0 or args.question_offset + args.questions > args.pool:
    parser.error("Require 1 <= questions <= pool <= 500 and offset+questions<=pool")
raw = args.source.read_bytes()
data = json.loads(raw)
groups = OrderedDict()
for case in data:
    if case.get("answer_session_ids"):
        groups.setdefault(case["question_type"], []).append(case)
balanced = []
while any(groups.values()) and len(balanced) < args.pool:
    for group in groups.values():
        if group and len(balanced) < args.pool:
            balanced.append(group.pop(0))
messages, probes, registered = [], [], {}
for case_number, case in enumerate(balanced):
    gold = []
    answer_sessions = set(case.get("answer_session_ids", []))
    for session_id, date, session in zip(case["haystack_session_ids"], case["haystack_dates"], case["haystack_sessions"]):
        for index, turn in enumerate(session):
            key = f"{session_id}:{index}"
            text = f"[public session={session_id} sourceDate={date}]\n{turn['content']}"
            previous = registered.get(key)
            if previous is not None and previous != text:
                key = f"{case['question_id']}:{key}"
            if key not in registered:
                registered[key] = text
                messages.append({"key": key, "role": turn["role"], "text": text})
            if session_id in answer_sessions and turn.get("has_answer") is True:
                gold.append(key)
    if args.question_offset <= case_number < args.question_offset + args.questions and gold:
        probes.append({"id": case["question_id"], "type": case["question_type"], "query": case["question"], "gold": gold})
result = {
    "dataset": "LongMemEval cleaned oracle, non-standard pooled raw-turn retrieval subset",
    "source": "https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned/resolve/98d7416/longmemeval_oracle.json",
    "sourceSha256": hashlib.sha256(raw).hexdigest(),
    "license": "MIT; Copyright (c) 2024 Di Wu; https://github.com/xiaowu0162/LongMemEval/blob/main/LICENSE",
    "standardBenchmark": False,
    "oracleSessionSelection": True,
    "pooledDistractorsAcrossScenarios": True,
    "goldAnswerNotInSearchQuery": True,
    "goldNotesSeeded": False,
    "sampling": "deterministic round-robin by question_type, answer-source-annotated cases only",
    "questionTypeCounts": dict(Counter(probe["type"] for probe in probes)),
    "abstentionIncluded": False,
    "questionOffset": args.question_offset,
    "actualLmMeasured": False,
    "messages": messages,
    "questions": probes,
}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
print(json.dumps({"questions": len(probes), "questionTypeCounts": result["questionTypeCounts"], "messages": len(messages), "fixtureBytes": args.output.stat().st_size, "sourceSha256": result["sourceSha256"], "standardBenchmark": False}))
