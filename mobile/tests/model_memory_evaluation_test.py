"""Tests evidence arithmetic/blinding; does not exercise or score a language model."""
import importlib.util
import json
import pathlib
import tempfile
import unittest

TOOLS = pathlib.Path(__file__).resolve().parents[1] / "tools/model-memory-evaluation.py"
SPEC = importlib.util.spec_from_file_location("memory_evaluation", TOOLS)
EVALUATION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EVALUATION)


class MemoryEvaluationTest(unittest.TestCase):
    def setUp(self):
        self.fixture = json.loads(EVALUATION.DEFAULT_FIXTURE.read_text())

    def test_blinded_inputs_contain_neither_golden_answers_nor_source_labels(self):
        with tempfile.TemporaryDirectory() as folder:
            manifest = EVALUATION.prepare(self.fixture, pathlib.Path(folder))
            writer = json.loads((pathlib.Path(folder) / "writer-inputs.json").read_text())
            self.assertEqual(12, len(writer))
            self.assertFalse(manifest["actualLmMeasured"])
            for case in writer:
                self.assertEqual({"caseId", "messages", "deviceEvents", "archiveCoverage"}, set(case))

    def test_retrieved_sources_and_answer_correctness_are_not_the_same_metric(self):
        case = self.fixture["cases"][1]
        scores = EVALUATION.score({**self.fixture, "cases": [case]}, [{
            "caseId": case["caseId"], "rankedSources": [{"type": "message", "id": 201}],
            "answer": "부산만 기억남", "memories": []}])
        row = scores["cases"][0]
        self.assertEqual(0.5, row["fractionalSourceRecallAtK"])
        self.assertTrue(row["anyGoldSourceHitAtK"])
        self.assertFalse(row["allGoldSourcesHitAtK"])
        self.assertIsNone(row["answerAccuracy"])
        self.assertIsNone(row["semanticExtractionAccuracy"])

    def test_exact_quote_support_does_not_claim_that_a_wrong_memory_is_true(self):
        case = self.fixture["cases"][0]
        quote = "내 첫 자전거 이름은 달토끼7호야."
        row = EVALUATION.score({**self.fixture, "cases": [case]}, [{
            "caseId": case["caseId"], "memories": [{"content": "이름은 사실 태양9호", "sourceRefs": [
                {"type": "message", "id": 101, "offsetChars": 0, "quote": quote}]}]}])["cases"][0]
        self.assertEqual(1.0, row["exactQuoteReferenceAccuracy"])
        self.assertIsNone(row["semanticExtractionAccuracy"])

    def test_offsets_follow_android_utf16_and_unknown_sources_fail(self):
        sources = {("message", 1): {"text": "😀나비"}}
        self.assertTrue(EVALUATION.exact_quote({"type": "message", "id": 1, "offsetChars": 2, "quote": "나비"}, sources))
        self.assertFalse(EVALUATION.exact_quote({"type": "message", "id": 1, "offsetChars": 1, "quote": "나비"}, sources))
        self.assertFalse(EVALUATION.exact_quote({"type": "message", "id": 99, "offsetChars": 0, "quote": "나비"}, sources))

    def test_writer_without_a_retrieval_run_has_no_retrieval_score(self):
        case = self.fixture["cases"][0]
        row = EVALUATION.score({**self.fixture, "cases": [case]}, [{"caseId": case["caseId"], "memories": []}])["cases"][0]
        self.assertFalse(row["retrievalMeasured"])
        self.assertIsNone(row["fractionalSourceRecallAtK"])
        self.assertIsNone(row["allGoldSourcesHitAtK"])

    def test_unanswerable_cases_do_not_get_vacuous_perfect_source_recall(self):
        case = self.fixture["cases"][9]
        row = EVALUATION.score({**self.fixture, "cases": [case]}, [{"caseId": case["caseId"], "rankedSources": []}])["cases"][0]
        self.assertIsNone(row["fractionalSourceRecallAtK"])
        self.assertIsNone(row["allGoldSourcesHitAtK"])

    def test_partial_coverage_and_missing_output_are_explicit(self):
        result = EVALUATION.score(self.fixture, [{"caseId": self.fixture["cases"][-1]["caseId"], "coverage": {"completeArchive": True}}])
        self.assertEqual(11, result["missingCases"])
        self.assertTrue(result["cases"][-1]["partialArchiveFalselyReportedComplete"])
        self.assertEqual(0, result["macroFractionalSourceRecallAtK"], 'missing source-bearing cases count as failures')


if __name__ == "__main__":
    unittest.main()
