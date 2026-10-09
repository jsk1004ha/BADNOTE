#!/usr/bin/env python3
"""Tool boundary regressions; all generated ink is explicitly synthetic."""
import argparse
import hashlib
import json
import pathlib
import tempfile
import unittest

from annotate_ocr import TEMPLATE, prepare
from bench_ocr import generate, instrumentation_metadata, parse_memory, percentile, replay_output_path, runtime_timing, summarize


class OcrToolsTest(unittest.TestCase):
    def test_warm_label_requires_actual_same_process_warmup(self):
        diagnostic = {"measurements": {"totalMs": 20, "replayTotalMs": 1019}, "result": {"status": "complete"}}
        with self.assertRaisesRegex(ValueError, "same-process"):
            runtime_timing(diagnostic, "B2", "none", "warm")
        diagnostic["execution"] = {"processId": 123, "warmupPerformed": True,
            "warmupStatus": "failed", "warmupMs": 999}
        with self.assertRaisesRegex(ValueError, "same-process"):
            runtime_timing(diagnostic, "B2", "none", "warm")
        diagnostic["execution"]["warmupStatus"] = "complete"
        timing = runtime_timing(diagnostic, "B2", "none", "warm")
        self.assertEqual((timing["totalMs"], timing["warmupMs"], timing["processId"]), (20, 999, 123))
        self.assertEqual(timing["replayTotalMs"], 1019)

    def test_cache_timing_excludes_cold_replay_setup(self):
        diagnostic = {"measurements": {"totalMs": 8000},
            "result": {"measurements": {"totalMs": 3}}, "execution": {
                "processId": 123, "warmupPerformed": True, "warmupStatus": "complete", "warmupMs": 7000}}
        timing = runtime_timing(diagnostic, "B3", "cache", "cold")
        self.assertEqual((timing["totalMs"], timing["replayTotalMs"]), (3, 8000))
        with self.assertRaisesRegex(ValueError, "nonnegative"):
            runtime_timing({"measurements": {"totalMs": True}}, "B2", "none", "cold")

    def test_workload_preserves_counts_ids_temporal_order_and_provenance(self):
        with tempfile.TemporaryDirectory() as directory:
            output = pathlib.Path(directory)
            generate(argparse.Namespace(workload="W0", seed=77, output=output))
            manifest = json.loads((output / "manifest.json").read_text())
            self.assertEqual(manifest["provenance"]["kind"], "synthetic")
            page_info = manifest["pages"][0]
            raw = (output / page_info["file"]).read_bytes()
            self.assertEqual(hashlib.sha256(raw).hexdigest(), page_info["sha256"])
            page = json.loads(raw)
            strokes = page["objects"]
            self.assertEqual(len(strokes), 100)
            self.assertEqual(sum(len(s["points"]) for s in strokes), 2000)
            self.assertEqual(len({s["id"] for s in strokes}), 100)
            self.assertEqual([s["captureSeq"] for s in strokes], list(range(100)))
            self.assertNotIn("truthRegions", page)

    def test_annotation_rejects_ambiguous_page_and_duplicate_stroke_ids(self):
        with tempfile.TemporaryDirectory() as directory:
            source = pathlib.Path(directory) / "ink.json"
            source.write_text(json.dumps({"pages": [{"id": "a"}, {"id": "b"}]}))
            with self.assertRaisesRegex(ValueError, "page-id"):
                prepare(source)
            self.assertEqual(prepare(source, "b")["sampleId"], "b")
            stroke = {"id": "same", "points": [{"x": 1, "y": 2}]}
            source.write_text(json.dumps({"objects": [stroke, stroke]}))
            with self.assertRaisesRegex(ValueError, "unique"):
                prepare(source)

    def test_annotation_keeps_single_dots_and_records_invalid_without_partial_sanitizing(self):
        with tempfile.TemporaryDirectory() as directory:
            source = pathlib.Path(directory) / "ink.json"
            source.write_text(json.dumps({"objects": [
                {"id": "dot", "points": [{"x": 5, "y": 7}]},
                {"id": "bad", "points": [{"x": 5, "y": 7}, {"x": None, "y": 3}]},
            ]}))
            fixture = prepare(source)
            self.assertEqual([s["id"] for s in fixture["strokes"]], ["dot"])
            self.assertEqual(fixture["invalidStrokeIds"], ["bad"])
            self.assertEqual(fixture["provenance"]["kind"], "synthetic")
            self.assertEqual(fixture["digestBasis"], "source-file-bytes")

    def test_annotation_html_does_not_allocate_original_huge_canvas(self):
        self.assertIn("1800/Math.max(fixture.width,fixture.height)", TEMPLATE)
        self.assertNotIn("canvas.width=fixture.width", TEMPLATE)

    def test_native_diagnostic_binds_source_page_and_runtime_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            source = pathlib.Path(directory) / "diagnostic.json"
            source.write_text(json.dumps({
                "sampleId": "native-page", "pageDigest": "actual-runtime-digest",
                "provenance": {"kind": "synthetic", "language": "ko"},
                "sourcePage": {"id": "p", "width": 1200, "height": 1600,
                    "objects": [{"id": "ink", "type": "stroke", "points": [{"x": 5, "y": 7}]}]},
                "result": {"status": "partial", "regions": []},
            }))
            fixture = prepare(source)
            self.assertEqual(fixture["sampleId"], "native-page")
            self.assertEqual(fixture["pageDigest"], "actual-runtime-digest")
            self.assertEqual(fixture["digestBasis"], "runtime")
            self.assertEqual((fixture["width"], fixture["height"]), (1200, 1600))
            self.assertEqual([s["id"] for s in fixture["strokes"]], ["ink"])
            source.write_text(json.dumps({"sourcePage": []}))
            with self.assertRaisesRegex(ValueError, "sourcePage"):
                prepare(source)

    def test_meminfo_new_and_old_android_formats(self):
        new = """        Native Heap       8192  8192
        Dalvik Heap       4096  4096
                  Graphics:     120
                 TOTAL PSS:    12345            TOTAL RSS:    23456
        """
        self.assertEqual(parse_memory(new), {
            "pssKiB": 12345, "rssKiB": 23456, "nativeHeapPssKiB": 8192,
            "dalvikHeapPssKiB": 4096, "graphicsPssKiB": 120,
        })
        self.assertEqual(parse_memory(" TOTAL 1024 500 100")["pssKiB"], 1024)
        self.assertIsNone(parse_memory("Permission denied")["pssKiB"])

    def test_timing_keeps_failure_and_flags_small_sample_p95(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = pathlib.Path(directory)
            source, target = directory / "runs.json", directory / "report.json"
            records = [{
                "deviceKind": "emulator", "deviceId": "test-avd", "buildSha": "fixture",
                "inputDigest": f"p{i}", "path": "B2", "workload": "W1", "state": "warm",
                "status": status, "totalMs": elapsed,
            } for i, (status, elapsed) in enumerate((("complete", 10), ("failed", 30), ("cancelled", 1)))]
            source.write_text(json.dumps(records))
            summarize(argparse.Namespace(input=source, output=target))
            group = json.loads(target.read_text())["groups"][0]
            self.assertEqual(group["nonCancelledRuns"], 2)
            self.assertEqual(group["failures"], 1)
            self.assertEqual(group["p50Ms"], 20)
            self.assertTrue(group["p95Evidence"].startswith("exploratory"))
            self.assertEqual(group["physicalAcceptance"], "unassessed")

    def test_unobserved_or_invalid_timing_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = pathlib.Path(directory)
            source, target = directory / "runs.json", directory / "report.json"
            source.write_text(json.dumps([{"deviceKind": "guessed"}]))
            with self.assertRaisesRegex(ValueError, "observed"):
                summarize(argparse.Namespace(input=source, output=target))

    def test_timing_separates_cache_policy_and_measurement_scope(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = pathlib.Path(directory)
            source, target = directory / "runs.json", directory / "report.json"
            base = {"deviceKind": "emulator", "deviceId": "test-avd", "buildSha": "fixture",
                    "inputDigest": "same-page", "path": "B3", "workload": "W0", "state": "warm",
                    "status": "complete", "inputKind": "synthetic"}
            source.write_text(json.dumps([
                {**base, "totalMs": 5, "execution": {"feature": "cache", "policy": "ko-primary"},
                 "measurementScope": "coordinator"},
                {**base, "totalMs": 500, "execution": {"feature": "policy", "policy": "mixed-review"},
                 "measurementScope": "replay"},
                {**base, "totalMs": 1000, "execution": {"feature": "cache", "policy": "ko-primary"},
                 "measurementScope": "replay including warmup"},
            ]))
            summarize(argparse.Namespace(input=source, output=target))
            groups = json.loads(target.read_text())["groups"]
            self.assertEqual(len(groups), 3)
            self.assertEqual({group["p50Ms"] for group in groups}, {5, 500, 1000})

    def test_percentile_empty_and_known_interpolation(self):
        self.assertIsNone(percentile([], .95))
        self.assertEqual(percentile([10, 30], .95), 29)

    def test_replay_output_rejects_traversal_and_ambiguous_instrumentation(self):
        valid = ("INSTRUMENTATION_RESULT: ocrReplayOutput=ocr-replay-results/run-123.json\r\n"
                 "INSTRUMENTATION_RESULT: ocrReplay=actual_debug_replay_saved\r\n"
                 "INSTRUMENTATION_RESULT: passed=true\r\nINSTRUMENTATION_CODE: 0\r\n")
        self.assertEqual(replay_output_path(valid), "ocr-replay-results/run-123.json")
        for output in ("INSTRUMENTATION_RESULT: ocrReplayOutput=../private.json", valid + valid,
                       "INSTRUMENTATION_FAILED", valid + "INSTRUMENTATION_RESULT: error=assertion failure"):
            with self.assertRaisesRegex(ValueError, "safe"):
                replay_output_path(output)

    def test_replay_requires_completed_success_not_just_output_path(self):
        path = "INSTRUMENTATION_RESULT: ocrReplayOutput=ocr-replay-results/run-123.json\n"
        complete = path + "INSTRUMENTATION_RESULT: ocrReplay=actual_debug_replay_saved\nINSTRUMENTATION_RESULT: passed=true\nINSTRUMENTATION_CODE: 0\n"
        for output in (path, complete.replace("passed=true", "passed=false"),
                       complete + "INSTRUMENTATION_CODE: -1\n", complete + "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n"):
            with self.subTest(output=output), self.assertRaises(ValueError):
                replay_output_path(output)

    def test_instrumentation_failure_keeps_type_without_private_message(self):
        output = ("INSTRUMENTATION_RESULT: error=java.lang.OutOfMemoryError: PRIVATE handwriting\n"
                  "\tat com.example.Replay.run(Replay.kt:10)\nINSTRUMENTATION_CODE: 0\n")
        metadata = instrumentation_metadata(output)
        self.assertTrue(metadata["failed"])
        self.assertEqual(metadata["errorTypes"], ["java.lang.OutOfMemoryError"])
        self.assertEqual(metadata["stackFrames"], ["com.example.Replay.run(Replay.kt:10)"])
        self.assertNotIn("PRIVATE", json.dumps(metadata))


if __name__ == "__main__":
    unittest.main()
