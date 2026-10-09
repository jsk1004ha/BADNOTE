#!/usr/bin/env python3
"""Regression checks for evaluation denominators, order and provenance."""
import copy
import pathlib
import tempfile
import unittest

from evaluate_ocr import aggregate, attach_results, edits, evaluate, load_samples, normalize, validate_truth


def sample(texts=("가 나",), outputs=None, status="complete"):
    outputs = texts if outputs is None else outputs
    return {
        "schemaVersion": 1, "sampleId": "synthetic-metrics", "pageDigest": "source-v1",
        "provenance": {"kind": "synthetic", "language": "ko"},
        "truthRegions": [
            {"id": f"t{i}", "role": "text", "strokeIds": [f"s{i}"], "text": text}
            for i, text in enumerate(texts)
        ],
        "readingOrder": [f"t{i}" for i in range(len(texts))],
        "result": {"status": status, "regions": [
            {"id": f"r{i}", "strokeIds": [f"s{i}"], "selectedText": text}
            for i, text in enumerate(outputs)
        ]},
    }


class EvaluationTest(unittest.TestCase):
    def test_counts_and_retention_do_not_call_one_minus_cer_accuracy(self):
        self.assertEqual(edits("abc", "axcd").report(), {
            "reference": 3, "substitutions": 1, "deletions": 0, "insertions": 1,
            "cer": 2 / 3, "referenceRetention": 2 / 3,
        })
        result = edits("a", "abcd").report()
        self.assertEqual(result["cer"], 3)
        self.assertEqual(result["referenceRetention"], 1)

    def test_alignment_tie_is_reproducible(self):
        result = edits("ab", "ba")
        self.assertEqual((result.substitutions, result.deletions, result.insertions), (2, 0, 0))

    def test_nfc_jamo_and_case_punctuation_spaces(self):
        self.assertEqual(normalize("가"), "가")
        self.assertEqual(edits(normalize("가"), normalize("가")).distance, 0)
        self.assertEqual(len(normalize("각", jamo=True)), 3)
        self.assertEqual(edits(normalize("AI 10:30"), normalize("ai10.30")).distance, 4)
        self.assertEqual(normalize("Ａ"), "Ａ")  # NFKC is not quality normalization.

    def test_page_failure_counts_every_reference_character(self):
        failed = evaluate(sample(("가", "나"), status="failed"))
        self.assertEqual(failed["metrics"]["nfc"]["deletions"], 3)
        self.assertEqual(failed["metrics"]["nfc"]["cer"], 1)
        self.assertEqual(failed["exactLines"], 0)
        self.assertEqual(aggregate([failed])["failureRate"], 1)

    def test_partial_and_layout_ambiguous_not_removed(self):
        fixture = sample(("가나", "다라"), ("가나", ""), "partial")
        fixture["result"]["regions"][1]["status"] = "layoutAmbiguous"
        row = evaluate(fixture)
        self.assertEqual(row["metrics"]["nfc"]["reference"], 5)
        self.assertEqual(row["exactLines"], 1)
        self.assertGreater(row["metrics"]["nfc"]["deletions"], 0)

    def test_reading_order_is_not_repaired_using_stroke_matching(self):
        fixture = sample(("left", "right"))
        fixture["result"]["regions"].reverse()
        row = evaluate(fixture)
        self.assertEqual(row["lineExactRate"], 1)
        self.assertFalse(row["pageExact"])
        self.assertGreater(row["metrics"]["nfc"]["cer"], 0)

    def test_merging_or_splitting_lines_fails_exact_matching(self):
        fixture = sample(("가", "나"))
        fixture["result"]["regions"] = [{"id": "merged", "strokeIds": ["s0", "s1"], "rawText": "가\n나"}]
        row = evaluate(fixture)
        self.assertEqual(row["metrics"]["nfc"]["cer"], 0)
        self.assertEqual(row["exactLines"], 0)
        fixture = sample(("ab",))
        fixture["truthRegions"][0]["strokeIds"] = ["s0", "s1"]
        fixture["result"]["regions"] = [
            {"id": "a", "strokeIds": ["s0"], "rawText": "a"},
            {"id": "b", "strokeIds": ["s1"], "rawText": "b"},
        ]
        self.assertEqual(evaluate(fixture)["exactLines"], 0)

    def test_empty_correction_is_preserved(self):
        fixture = sample(("",), ("unexpected",))
        fixture["result"]["regions"][0]["correctedText"] = ""
        self.assertTrue(evaluate(fixture, "corrected")["pageExact"])
        self.assertTrue(evaluate(fixture, "raw")["emptyTruthFalsePositive"] is False)
        self.assertTrue(evaluate(fixture, "selected")["emptyTruthFalsePositive"])

    def test_empty_truth_no_division_by_zero(self):
        row = evaluate(sample(("",), ("hallucination",)))
        report = aggregate([row])
        self.assertIsNone(report["metrics"]["nfc"]["cer"])
        self.assertEqual(report["emptyTruthFalsePositiveRate"], 1)
        self.assertEqual(report["metrics"]["nfc"]["insertions"], 13)

    def test_explicit_cancellation_separate_from_system_failure(self):
        report = aggregate([evaluate(sample(status="cancelled")), evaluate(sample(status="failed"))])
        self.assertEqual(report["pages"], 2)
        self.assertEqual(report["evaluatedPages"], 1)
        self.assertEqual(report["cancelledPages"], 1)
        self.assertEqual(report["failedPages"], 1)
        self.assertEqual(report["metrics"]["nfc"]["deletions"], 3)

    def test_stale_digest_treated_as_failed_instead_of_valid_quality(self):
        fixture = sample()
        fixture["result"]["pageDigest"] = "old"
        row = evaluate(fixture)
        self.assertTrue(row["stale"])
        self.assertEqual(row["metrics"]["nfc"]["cer"], 1)

    def test_invalid_truth_is_not_silently_repaired(self):
        fixture = sample(("a", "b"))
        fixture["truthRegions"][1]["strokeIds"] = ["s0"]
        with self.assertRaisesRegex(ValueError, "exactly one"):
            validate_truth(fixture)
        fixture = sample()
        fixture["readingOrder"] = []
        with self.assertRaisesRegex(ValueError, "readingOrder"):
            validate_truth(fixture)

    def test_source_disposition_conservation_includes_nontext_unreadable(self):
        fixture = sample(("a",))
        fixture["truthRegions"].extend([
            {"id": "drawing", "role": "nonText", "strokeIds": ["arrow"]},
            {"id": "illegible", "role": "unreadable", "strokeIds": ["u"]},
        ])
        fixture["readingOrder"] += ["drawing", "illegible"]
        fixture["result"]["nonTextPreserved"] = ["arrow"]
        fixture["result"]["unresolved"] = ["u"]
        row = evaluate(fixture)
        self.assertTrue(row["strokeConservation"])
        self.assertEqual(row["unreadableStrokes"], 1)
        fixture["result"]["regions"].append(copy.deepcopy(fixture["result"]["regions"][0]))
        self.assertFalse(evaluate(fixture)["strokeConservation"])

    def test_real_provenance_never_inferred_from_synthetic(self):
        fixture = sample()
        fixture["provenance"]["kind"] = "real"
        with self.assertRaisesRegex(ValueError, "provenance"):
            evaluate(fixture)

    def test_failure_remains_in_micro_average_denominator(self):
        passing = evaluate(sample(("a",)))
        failure = evaluate(sample(("b" * 9,), status="failed"))
        self.assertEqual(aggregate([passing, failure])["metrics"]["nfc"]["cer"], .9)

    def test_join_missing_runtime_output_stays_in_quality_denominator(self):
        first, second = sample(("a",)), sample(("b" * 9,))
        first["sampleId"], second["sampleId"] = "first", "second"
        actual = {"sampleId": "first", "pageDigest": first["pageDigest"], **first["result"]}
        rows = [evaluate(s) for s in attach_results([first, second], [actual])]
        self.assertEqual(aggregate(rows)["metrics"]["nfc"]["cer"], .9)

    def test_join_requires_actual_result_digest_and_identity(self):
        with self.assertRaisesRegex(ValueError, "pageDigest"):
            attach_results([sample()], [{"sampleId": "synthetic-metrics", "status": "complete"}])
        with self.assertRaisesRegex(ValueError, "IDs"):
            attach_results([sample()], [{"sampleId": "different", "pageDigest": "x"}])

    def test_empty_runtime_batch_counts_every_annotation_as_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            results = pathlib.Path(directory) / "results.json"
            results.write_text("[]")
            with self.assertRaisesRegex(ValueError, "at least one"):
                load_samples(results)
            joined = attach_results([sample()], load_samples(results, allow_empty=True))
            report = aggregate([evaluate(s) for s in joined])
            self.assertEqual(report["failedPages"], 1)
            self.assertEqual(report["metrics"]["nfc"]["cer"], 1)

    def test_runtime_unresolved_region_counted_once(self):
        fixture = sample()
        fixture["result"]["regions"][0]["status"] = "unresolved"
        row = evaluate(fixture)
        self.assertTrue(row["strokeConservation"])
        self.assertEqual(row["unresolvedStrokes"], 1)


if __name__ == "__main__":
    unittest.main()
