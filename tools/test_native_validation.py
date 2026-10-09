"""Instrumentation shell exit zero alone must never count as assertion success."""
import unittest
import io
from pathlib import Path
import subprocess
import tempfile
from unittest.mock import patch

from run_native_validation import compose_screen_contract, front_screen_contract, require_instrument_success, w3_upload_index, release_capture_inputs, transfer_private_fixture, digest, read_w3_ready


class BinaryFixtureTransferTest(unittest.TestCase):
    @staticmethod
    def adb_result(argv, **kwargs):
        if "ls" in argv:
            return subprocess.CompletedProcess(argv, 1, b"", ("ls: " + argv[-1] + ": No such file or directory\n").encode())
        return subprocess.CompletedProcess(argv, 0, b"", b"")

    def test_binary_fixture_uses_push_and_verifies_private_sha(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "binary.webm"
            source.write_bytes(b"\x1a\x00\r\n\xffbinary")
            destination = "files/media-fixtures/binary.webm"
            with patch("run_native_validation.subprocess.run") as run, patch("run_native_validation.subprocess.Popen") as popen:
                run.side_effect = self.adb_result
                process = popen.return_value.__enter__.return_value
                process.stdout = io.BytesIO(source.read_bytes())
                process.wait.return_value = 0
                receipt = transfer_private_fixture(["adb", "-s", "emulator-5554"], source, destination)
                commands = [call.args[0] for call in run.call_args_list]
                self.assertEqual(commands[0][3], "push")
                self.assertEqual(commands[1][-3:], ["cp", commands[0][-1], destination])
                self.assertEqual(popen.call_args.args[0][-5:], ["exec-out", "run-as", "com.inkforge.note4.debug", "cat", destination])
                self.assertEqual(commands[2][-3:], ["rm", "-f", commands[0][-1]])
                self.assertTrue(all("stdin" not in call.kwargs for call in run.call_args_list))
                self.assertEqual(receipt["sha256"], digest(source))
                self.assertEqual(receipt["bytes"], source.stat().st_size)

    def test_mismatch_and_copy_failure_are_rejected_and_temporary_is_removed(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "fixture"
            source.write_bytes(b"\x1a\x00\xff")
            for copy_failure in (False, True):
                with self.subTest(copy_failure=copy_failure), patch("run_native_validation.subprocess.run") as run, patch("run_native_validation.subprocess.Popen") as popen:
                    if copy_failure:
                        def failed_copy(argv, **kwargs):
                            if "cp" in argv:
                                raise subprocess.CalledProcessError(1, argv)
                            return self.adb_result(argv, **kwargs)
                        run.side_effect = failed_copy
                    else:
                        run.side_effect = self.adb_result
                        process = popen.return_value.__enter__.return_value
                        process.stdout = io.BytesIO(b"cat: missing fixture\n")
                        process.wait.return_value = 0  # Old ADB can report zero despite remote failure.
                    with self.assertRaises((RuntimeError, subprocess.CalledProcessError)):
                        transfer_private_fixture(["adb"], source, "files/fixture")
                    self.assertEqual(run.call_args_list[-2].args[0][-3:-1], ["rm", "-f"])

    def test_private_readback_failure_and_same_size_corruption_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "fixture"
            source.write_bytes(b"original")
            for data, exit_code in [(b"changed!", 0), (b"original", 1)]:
                with self.subTest(data=data, exit_code=exit_code), patch("run_native_validation.subprocess.run", side_effect=self.adb_result), patch("run_native_validation.subprocess.Popen") as popen:
                    process = popen.return_value.__enter__.return_value
                    process.stdout = io.BytesIO(data)
                    process.wait.return_value = exit_code
                    with self.assertRaises(RuntimeError):
                        transfer_private_fixture(["adb"], source, "files/fixture")

    def test_empty_source_remote_error_and_hidden_cleanup_failure_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "fixture"
            source.write_bytes(b"")
            for read_error in (False, True):
                with self.subTest(read_error=read_error), patch("run_native_validation.subprocess.run") as run, patch("run_native_validation.subprocess.Popen") as popen:
                    def result(argv, **kwargs):
                        if "ls" in argv and not read_error:
                            return subprocess.CompletedProcess(argv, 0, b"still present", b"")
                        return self.adb_result(argv, **kwargs)
                    run.side_effect = result
                    process = popen.return_value.__enter__.return_value
                    process.stdout = io.BytesIO(b"")
                    process.wait.return_value = 0
                    def start(*args, **kwargs):
                        if read_error:
                            kwargs["stderr"].write(b"cat: permission denied")
                        return popen.return_value
                    popen.side_effect = start
                    with self.assertRaisesRegex(RuntimeError, "readback reported|cleanup was not verified"):
                        transfer_private_fixture(["adb"], source, "files/fixture")

    def test_destination_cannot_escape_private_files(self):
        for destination in ("/sdcard/fixture", "files/../fixture", "files/x;rm", "other/fixture"):
            with self.subTest(destination=destination), patch("run_native_validation.subprocess.run") as run:
                with self.assertRaises(ValueError):
                    transfer_private_fixture(["adb"], Path("unused"), destination)
                run.assert_not_called()


class W3ReadyQueryTest(unittest.TestCase):
    def test_not_yet_created_file_is_pending_with_remote_exit_status(self):
        with patch("run_native_validation.subprocess.run") as run:
            run.return_value = subprocess.CompletedProcess([], 1, b"", b"cat: files/w3/ready.json: No such file or directory\r\n")
            self.assertIsNone(read_w3_ready(["adb", "-s", "emulator-5554"]))
            self.assertEqual(run.call_args.args[0][3:5], ["shell", "-T"])

    def test_completed_request_is_parsed_without_suppressing_corrupt_json(self):
        with patch("run_native_validation.subprocess.run") as run:
            run.return_value = subprocess.CompletedProcess([], 0, b'{"runId":"current","index":0}\r\n', b"")
            self.assertEqual(read_w3_ready(["adb"]), {"runId": "current", "index": 0})
            for data in (b"", b"cat: missing", b"[]"):
                run.return_value = subprocess.CompletedProcess([], 0, data, b"")
                with self.subTest(data=data), self.assertRaises((ValueError, RuntimeError)):
                    read_w3_ready(["adb"])

    def test_device_or_permission_failures_are_not_treated_as_pending(self):
        with patch("run_native_validation.subprocess.run") as run:
            for error in (b"error: device offline", b"cat: files/w3/ready.json: Permission denied"):
                run.return_value = subprocess.CompletedProcess([], 1, b"", error)
                with self.subTest(error=error), self.assertRaises(RuntimeError):
                    read_w3_ready(["adb"])


class InstrumentationReceiptTest(unittest.TestCase):
    marker = "INSTRUMENTATION_RESULT: brushRaster=36_native_bitmap_cases_written"

    def test_release_capture_keeps_all_production_source_sets_and_build_inputs(self):
        for path in ("android/app/src/main/java/Database.kt", "android/app/src/main/cpp/adapter.cpp",
                     "android/app/src/nativeAssets/locales/ko.json", "android/app/src/update/res/values/x.xml",
                     "android/app/src/sideBySide/java/Flavor.kt", "android/app/build.gradle",
                     "android/gradle.properties", "tools/pdfium.lock.json", "tools/prepare_pdfium.py",
                     "web/locales/ko.js"):
            with self.subTest(path=path):
                self.assertEqual(release_capture_inputs({path: "old"}), {path: "old"})
                self.assertNotEqual(release_capture_inputs({path: "old"}), release_capture_inputs({path: "new"}))
                self.assertNotEqual(release_capture_inputs({path: "old"}), release_capture_inputs({}))

    def test_test_runner_changes_do_not_change_installed_release_inputs(self):
        production = {"android/app/src/main/java/Database.kt": "same"}
        for path in ("android/app/src/androidTest/java/Smoke.kt", "android/app/src/test/java/Unit.kt",
                     "tools/run_native_validation.py", "tools/test_native_validation.py", "docs/Results.md"):
            with self.subTest(path=path):
                self.assertEqual(release_capture_inputs({**production, path: "old"}),
                                 release_capture_inputs({**production, path: "new"}))

    def test_completed_assertions(self):
        require_instrument_success(self.marker + "\nINSTRUMENTATION_RESULT: passed=true\nINSTRUMENTATION_CODE: 0\n",
                                   self.marker)

    def test_marker_without_completed_assertions(self):
        with self.assertRaises(RuntimeError):
            require_instrument_success(self.marker + "\nINSTRUMENTATION_CODE: 0\n", self.marker)

    def test_nonzero_instrumentation_code(self):
        for code in ("-1", "1", "01"):
            with self.subTest(code=code), self.assertRaises(RuntimeError):
                require_instrument_success(self.marker + f"\nINSTRUMENTATION_RESULT: passed=true\nINSTRUMENTATION_CODE: {code}\n",
                                           self.marker)

    def test_process_crash_with_zero_shell_code(self):
        text = (self.marker + "\nINSTRUMENTATION_RESULT: passed=true\n"
                "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n")
        with self.assertRaises(RuntimeError):
            require_instrument_success(text, self.marker)

    def test_conflicting_failure_result(self):
        text = (self.marker + "\nINSTRUMENTATION_RESULT: passed=true\n"
                "INSTRUMENTATION_RESULT: passed=false\nINSTRUMENTATION_CODE: 0\n")
        with self.assertRaises(RuntimeError):
            require_instrument_success(text, self.marker)

    def test_fallback_screens_bind_fixed_pen_coordinate(self):
        text = ("INSTRUMENTATION_RESULT: s5bScreenDirectory=/data/user/0/com.inkforge.note4.debug/files/s5b-fallback/run-123\n"
                "INSTRUMENTATION_RESULT: s5bPenProbeScreenXY=210,520\n")
        directory, names, point = front_screen_contract(text, 23)
        self.assertTrue(directory.endswith("/run-123"))
        self.assertEqual(names[:2], ["00-baseline.png", "01-committed.png"])
        self.assertEqual(point, (210, 520))

    def test_multiple_completion_codes_are_rejected(self):
        text = self.marker + "\nINSTRUMENTATION_RESULT: passed=true\nINSTRUMENTATION_CODE: 0\n"
        for suffix in ("INSTRUMENTATION_CODE: -1\n", "INSTRUMENTATION_CODE: 0\n"):
            with self.subTest(suffix=suffix), self.assertRaises(RuntimeError):
                require_instrument_success(text + suffix, self.marker)

    def test_screenshot_path_cannot_escape_attempt(self):
        for directory in ("/sdcard/screens", "/data/user/0/com.inkforge.note4.debug/files/s5b-fallback/run-123/..",
                          "/data/user/0/com.inkforge.note4.debug/files/s5b-screens/run-123"):
            with self.subTest(directory=directory), self.assertRaises(RuntimeError):
                front_screen_contract("INSTRUMENTATION_RESULT: s5bScreenDirectory=" + directory + "\n"
                                      "INSTRUMENTATION_RESULT: s5bPenProbeScreenXY=210,520\n", 23)

    def test_unbound_or_duplicated_pen_probe_is_rejected(self):
        text = "INSTRUMENTATION_RESULT: s5bScreenDirectory=/data/user/0/com.inkforge.note4.debug/files/s5b-fallback/run-123\n"
        for suffix in ("", "INSTRUMENTATION_RESULT: s5bPenProbeScreenXY=1,2\n" * 2):
            with self.subTest(suffix=suffix), self.assertRaises(RuntimeError):
                front_screen_contract(text + suffix, 30)

    def test_w3_ignores_other_attempt_and_consuming_request(self):
        self.assertIsNone(w3_upload_index({"runId": "old", "index": 299}, "current", 0))
        self.assertIsNone(w3_upload_index({"runId": "current", "index": 0}, "current", 1))
        self.assertEqual(w3_upload_index({"runId": "current", "index": 1}, "current", 1), 1)

    def test_w3_never_skips_or_returns_to_old_page(self):
        for index, uploaded in ((1, 0), (0, 2), (299, 298)):
            with self.subTest(index=index, uploaded=uploaded), self.assertRaises(RuntimeError):
                w3_upload_index({"runId": "current", "index": index}, "current", uploaded)

    def test_w3_index_must_be_an_actual_bounded_integer(self):
        for index in (True, "0", None, -1, 300):
            with self.subTest(index=index), self.assertRaises(RuntimeError):
                w3_upload_index({"runId": "current", "index": index}, "current", 0)

    def test_compose_binds_four_locale_screens_to_one_attempt(self):
        text = "\n".join("INSTRUMENTATION_RESULT: " + key + "=compose-ui/run-123/" + name
            for key, name in (("uiLibraryScreenshot", "library.png"), ("uiSettingsScreenshot", "settings.png"),
                              ("uiEnglishLibraryScreenshot", "library-en.png"), ("uiEnglishSettingsScreenshot", "settings-en.png")))
        self.assertEqual(len(compose_screen_contract(text)), 4)
        for changed in (text.replace("settings-en.png", "../settings-en.png"),
                        text.replace("run-123/settings-en.png", "run-124/settings-en.png"),
                        text.replace("uiEnglishLibraryScreenshot", "unrelatedKey")):
            with self.subTest(changed=changed), self.assertRaises(RuntimeError):
                compose_screen_contract(changed)


if __name__ == "__main__":
    unittest.main()
