#!/usr/bin/env python3
"""Run assembled native checks with full logs and source-bound local receipts."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import uuid

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android"
SOURCE_PREFIXES = ("android/app/src/", "tools/", "docs/", "web/")
SOURCE_FILES = {"android/app/build.gradle", "android/build.gradle", "android/settings.gradle",
                "android/gradle.properties", "android/gradle/wrapper/gradle-wrapper.properties", "android/app/.gitignore", "README.md", "web/app.js"}


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def transfer_private_fixture(adb: list, source_path: Path, private_path: str,
                             package: str = "com.inkforge.note4.debug") -> dict:
    """ADB push preserves binary bytes on Windows; verify the private copy."""
    if not re.fullmatch(r"files/[A-Za-z0-9_./-]+", private_path) or ".." in private_path.split("/"):
        raise ValueError("Fixture destination must be a bounded private files path")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+\.debug", package):
        raise ValueError("Fixture transfer requires a debug package")
    command = list(map(str, adb))
    temporary = "/data/local/tmp/badnote-fixture-" + uuid.uuid4().hex + ".tmp"
    expected = digest(source_path)
    expected_size = source_path.stat().st_size
    try:
        subprocess.run([*command, "push", str(source_path), temporary], capture_output=True, check=True, timeout=180)
        subprocess.run([*command, "shell", "run-as", package, "cp", temporary, private_path],
                       capture_output=True, check=True, timeout=180)
        # Android 6 has no sha256sum and its shell can hide remote exit failures.
        # exec-out preserves binary bytes; hash the actual private copy in bounded blocks.
        actual = hashlib.sha256()
        size = 0
        with tempfile.TemporaryFile() as errors, subprocess.Popen(
                [*command, "exec-out", "run-as", package, "cat", private_path],
                stdout=subprocess.PIPE, stderr=errors) as process:
            deadline = threading.Timer(180, process.kill)
            deadline.start()
            try:
                for block in iter(lambda: process.stdout.read(1024 * 1024), b""):
                    actual.update(block)
                    size += len(block)
                if process.wait(timeout=30) != 0:
                    raise RuntimeError("Private fixture readback failed: " + private_path)
                errors.seek(0)
                if errors.read(1):
                    raise RuntimeError("Private fixture readback reported an error: " + private_path)
            finally:
                deadline.cancel()
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=30)
        if (actual.hexdigest() != expected or size != expected_size
                or digest(source_path) != expected or source_path.stat().st_size != expected_size):
            raise RuntimeError("Transferred fixture differs from its source: " + private_path)
        return {"sha256": expected, "bytes": expected_size, "destination": private_path,
                "transport": "adb-push-private-copy-sha256"}
    finally:
        subprocess.run([*command, "shell", "rm", "-f", temporary], capture_output=True, check=True, timeout=30)
        missing = subprocess.run([*command, "exec-out", "ls", "-ld", temporary], capture_output=True, timeout=30)
        observed = (missing.stdout + missing.stderr).decode("utf-8", errors="replace").strip()
        if observed not in {temporary + ": No such file or directory",
                            "ls: " + temporary + ": No such file or directory"}:
            raise RuntimeError("Fixture staging cleanup was not verified: " + temporary)


def source_snapshot() -> dict[str, str]:
    names = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                           cwd=ROOT, check=True, capture_output=True).stdout.decode("utf-8").split("\0")
    return {name: digest(ROOT / name) for name in sorted(set(names))
            if name and (name in SOURCE_FILES or name.startswith(SOURCE_PREFIXES)) and (ROOT / name).is_file()}


def release_capture_inputs(files: dict[str, str]) -> dict[str, str]:
    """Installed-release evidence depends on production inputs, not test runners."""
    return {name: sha for name, sha in files.items()
            if (name.startswith("android/app/src/")
                and not name.startswith(("android/app/src/androidTest/", "android/app/src/test/")))
            or name.startswith("web/")
            or (name in SOURCE_FILES and name.startswith("android/"))
            or name in {"tools/build_apk.py", "tools/sync_native_icons.py",
                        "tools/prepare_pdfium.py", "tools/pdfium.lock.json"}}


def sdk_root() -> Path:
    value = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if value:
        path = Path(value)
    elif os.name == "nt" and os.environ.get("LOCALAPPDATA"):
        path = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk"
    else:
        raise ValueError("Set ANDROID_SDK_ROOT to the installed SDK")
    if not path.is_dir():
        raise ValueError("Android SDK does not exist")
    return path


def installed_browser(explicit: Path | None) -> Path:
    if explicit is not None:
        browser = explicit
    else:
        installed = shutil.which("chromium") or shutil.which("chromium-browser") or shutil.which("google-chrome")
        browser = Path(installed) if installed else next((Path(os.environ[key]) / "Google/Chrome/Application/chrome.exe"
            for key in ("PROGRAMFILES", "PROGRAMFILES(X86)", "LOCALAPPDATA")
            if os.environ.get(key) and (Path(os.environ[key]) / "Google/Chrome/Application/chrome.exe").is_file()), None)
    if browser is None or not browser.is_file():
        raise ValueError("Browser regressions require an installed Chromium/Chrome executable")
    return browser


def require_instrument_success(text: str, expected: str) -> None:
    lines = [line.strip() for line in text.splitlines()]
    codes = [line for line in lines if line.startswith("INSTRUMENTATION_CODE:")]
    if (lines.count(expected) != 1 or lines.count("INSTRUMENTATION_RESULT: passed=true") != 1
            or codes != ["INSTRUMENTATION_CODE: 0"] or "INSTRUMENTATION_FAILED" in text
            or "INSTRUMENTATION_RESULT: error=" in text or "shortMsg=Process crashed" in text
            or "INSTRUMENTATION_RESULT: passed=false" in lines):
        raise RuntimeError("Instrumentation did not report successful assertions")


def front_screen_contract(text: str, api: int) -> tuple[str, list[str], tuple[int, int] | None]:
    """Bind screenshot collection to this completed instrumentation attempt."""
    prefix = "s5b-screens" if api >= 31 else "s5b-fallback"
    directories = re.findall(r"^INSTRUMENTATION_RESULT: s5bScreenDirectory=(\S+)\s*$", text, re.MULTILINE)
    if len(directories) != 1 or not re.fullmatch(
            rf"/data/user/0/com\.inkforge\.note4\.debug/files/{prefix}/run-[0-9]+", directories[0]):
        raise RuntimeError("Front-buffer receipt needs one private screenshot directory from this attempt")
    if api >= 31:
        names = ["01-canonical", "02-front-active", "03-multi-committed", "04-alpha-first",
                 "05-alpha-repeat", "06-before-cancel", "07-after-cancel", "08-edge-clipped",
                 "09-highlighter-fallback", "10-highlighter-committed", "11-zoomed", "12-recreated",
                 "13-landscape", "14-portrait-restored"]
        point = None
    else:
        names = ["00-baseline", "01-committed", "02-after-cancel", "03-highlighter", "04-zoomed"]
        points = re.findall(r"^INSTRUMENTATION_RESULT: s5bPenProbeScreenXY=([0-9]+),([0-9]+)\s*$",
                            text, re.MULTILINE)
        if len(points) != 1:
            raise RuntimeError("Canvas fallback receipt needs one fixed pen coordinate for saved screenshots")
        point = tuple(map(int, points[0]))
    return directories[0], [name + ".png" for name in names], point


def w3_upload_index(ready: dict, run_id: str, uploaded: int) -> int | None:
    """Ignore another attempt; never skip/repeat a page in this handshake."""
    if ready.get("runId") != run_id:
        return None
    index = ready.get("index")
    if type(index) is not int or not 0 <= index < 300:
        raise RuntimeError("Invalid W3 page request")
    if index == uploaded - 1:
        return None  # Last incoming file has not yet been consumed.
    if index != uploaded:
        raise RuntimeError("W3 requested a skipped or old page")
    return index


def read_w3_ready(adb: list) -> dict | None:
    # exec-out lacks remote exit status and merges cat errors into JSON stdout.
    query = subprocess.run([*map(str, adb), "shell", "-T", "run-as", "com.inkforge.note4.debug", "cat",
                            "files/w3/ready.json"], capture_output=True, timeout=30)
    if query.returncode:
        error = query.stderr.decode("utf-8", errors="replace")
        if query.returncode == 1 and "files/w3/ready.json: No such file or directory" in error:
            return None
        raise RuntimeError("W3 ready query failed: " + error.strip())
    ready = json.loads(query.stdout)
    if not isinstance(ready, dict):
        raise RuntimeError("W3 ready request must be a JSON object")
    return ready


def compose_screen_contract(text: str) -> list[tuple[str, str]]:
    """Require Korean/English screenshots from one completed UI attempt."""
    result = []
    directories = set()
    for key, name in (("uiLibraryScreenshot", "library.png"), ("uiSettingsScreenshot", "settings.png"),
                      ("uiEnglishLibraryScreenshot", "library-en.png"), ("uiEnglishSettingsScreenshot", "settings-en.png")):
        declared = re.findall(r"^INSTRUMENTATION_RESULT: " + key + r"=(\S+)\s*$", text, re.MULTILINE)
        if len(declared) != 1 or not re.fullmatch(r"compose-ui/run-[0-9]+/" + re.escape(name), declared[0]):
            raise RuntimeError("Compose receipt lacks a bounded canonical Korean/English screenshot")
        directories.add(declared[0].rsplit("/", 1)[0])
        result.append((key, declared[0]))
    if len(directories) != 1:
        raise RuntimeError("Compose screenshots belong to different attempts")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("tools", "web", "build", "instrument", "replay", "apk", "upgrade", "diff"))
    parser.add_argument("--output", type=Path, default=ROOT / "build/native-validation")
    parser.add_argument("--device", help="Explicit isolated emulator/test device; debug package only")
    parser.add_argument("--instrument-mode", choices=("full", "ui", "migration", "legacy", "ocr", "pdf", "pdf100", "pdf300", "pdf-cancel", "s3", "s5a", "front", "brush", "s5c", "media", "w3"), default="full")
    parser.add_argument("--pdf-fixtures", type=Path, default=ROOT / "build/ocr/pdf-fixtures")
    parser.add_argument("--media-fixtures", type=Path, default=ROOT / "build/media/compat-fixtures")
    parser.add_argument("--w3-fixtures", type=Path, default=ROOT / "build/ocr/load-w3")
    parser.add_argument("--upgrade-directory", type=Path, default=ROOT / "build/release-upgrade")
    parser.add_argument("--replay-set", choices=("all", "w0"), default="all")
    parser.add_argument("--brush-fixture", type=Path, default=ROOT / ".adhd/ocr-native-plan/brush-reference.json")
    parser.add_argument("--online", action="store_true", help="Allow Gradle dependency resolution")
    parser.add_argument("--chromium", type=Path, help="Installed Chromium/Chrome executable for existing web regressions")
    parser.add_argument("--guarded-native-library", action="append", default=[], metavar="LIBNAME=API")
    parser.add_argument("--require-asset", action="append", default=[], metavar="APK_PATH")
    args = parser.parse_args()
    os.environ["PYTHONIOENCODING"] = "utf-8"
    output = args.output.resolve()
    if not output.is_relative_to(ROOT / "build"):
        raise ValueError("Validation output must be a generated directory beneath repository/build")
    # Each attempt retains its full logs; a repair must not overwrite the failure.
    output /= datetime.now(timezone.utc).strftime("attempt-%Y%m%dT%H%M%S%fZ")
    output.mkdir(parents=True, exist_ok=False)
    before = source_snapshot()
    receipt = {"schemaVersion": 1, "phase": args.phase, "startedAtUtc": datetime.now(timezone.utc).isoformat(),
               "sourceFiles": before, "sourceDigest": hashlib.sha256(json.dumps(before, sort_keys=True).encode()).hexdigest(),
               "commands": [], "status": "running", "scope": "local execution; no native harness acceptance implied"}
    receipt_path = output / (args.phase + "-receipt.json")

    def run(argv: list[str | Path], name: str, cwd: Path = ROOT) -> str:
        command = list(map(str, argv))
        log_path = output / (name + ".log")
        started = time.monotonic()
        with log_path.open("wb") as log:
            result = subprocess.run(command, cwd=cwd, stdout=log, stderr=subprocess.STDOUT)
        entry = {"argv": command, "cwd": str(cwd), "exitCode": result.returncode,
                 "elapsedMs": round((time.monotonic() - started) * 1000, 2),
                 "log": str(log_path.relative_to(ROOT)), "logSha256": digest(log_path)}
        receipt["commands"].append(entry)
        text = log_path.read_text(encoding="utf-8", errors="replace")
        print(json.dumps({"check": name, "exitCode": result.returncode, "log": entry["log"]}), flush=True)
        if result.returncode:
            print(text[-6000:], file=sys.stderr)
            raise RuntimeError(f"{name} exited {result.returncode}")
        return text

    def transfer(adb: list, source_path: Path, private_path: str) -> None:
        receipt.setdefault("fixtureTransfers", []).append(transfer_private_fixture(adb, source_path, private_path))

    def run_w3(adb: list, command: list[str | Path], fixtures: Path, manifest: dict, run_id: str) -> str:
        log_path = output / "instrumentation.log"
        handshake = output / "w3-transfers.json"
        receipt["w3"] = {"runId": run_id, "manifestSha256": digest(fixtures / "manifest.json"),
                         "transfers": [], "provenance": "synthetic-load-only", "physicalMeasurements": "unmeasured"}
        started = time.monotonic()
        execution = list(map(str, command))
        error = None
        with log_path.open("wb") as log:
            process = subprocess.Popen(execution, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
            try:
                while process.poll() is None:
                    if time.monotonic() - started > 3600:
                        raise TimeoutError("W3 actual persistence/navigation/OCR exceeded 60 minutes")
                    ready = read_w3_ready(adb)
                    if ready is None:
                        time.sleep(.25)
                        continue
                    index = w3_upload_index(ready, run_id, len(receipt["w3"]["transfers"]))
                    if index is None:
                        time.sleep(.25)
                        continue
                    page = manifest["pages"][index]
                    source_path = fixtures / page["file"]
                    actual_sha = digest(source_path)
                    if actual_sha != page["sha256"]:
                        raise ValueError("W3 source no longer matches its manifest")
                    transfer(adb, source_path, "files/w3/incoming.tmp")
                    subprocess.run([*map(str, adb), "shell", "run-as", "com.inkforge.note4.debug", "mv",
                                    "files/w3/incoming.tmp", "files/w3/incoming.json"], capture_output=True, check=True, timeout=30)
                    receipt["w3"]["transfers"].append({"index": index, "file": page["file"], "sha256": actual_sha,
                        "bytes": source_path.stat().st_size, "elapsedMs": round((time.monotonic() - started) * 1000, 2)})
                    handshake.write_text(json.dumps(receipt["w3"], indent=2) + "\n", encoding="utf-8")
                    if index % 25 == 0 or index == 299:
                        print(json.dumps({"w3PagesTransferred": index + 1, "runId": run_id}), flush=True)
                if len(receipt["w3"]["transfers"]) != 300:
                    raise RuntimeError("W3 instrumentation ended before all 300 input pages were transferred")
                if process.returncode:
                    raise RuntimeError("W3 instrumentation command failed")
            except Exception as caught:
                error = caught
                # Stop only this debug test process on the explicit test device.
                subprocess.run([*map(str, adb), "shell", "am", "force-stop", "com.inkforge.note4.debug"],
                               capture_output=True, timeout=30)
                process.terminate()
                process.wait(timeout=30)
        receipt["commands"].append({"argv": execution, "cwd": str(ROOT), "exitCode": process.returncode,
            "elapsedMs": round((time.monotonic() - started) * 1000, 2),
            "log": str(log_path.relative_to(ROOT)), "logSha256": digest(log_path)})
        if error is not None:
            raise error
        return log_path.read_text(encoding="utf-8", errors="replace")

    try:
        if args.phase == "tools":
            run([sys.executable, "-m", "unittest", "discover", "-s", "tools", "-p", "test_ocr*.py"], "ocr-tools")
            for name in ("test_apk_native", "test_pdfium_prepare", "test_native_validation", "test_release_upgrade"):
                run([sys.executable, ROOT / "tools" / (name + ".py")], name)
        elif args.phase == "web":
            browser = installed_browser(args.chromium)
            os.environ["PYTHONIOENCODING"] = "utf-8"
            for name, path in (("test_web", output / "web-results.json"),
                               ("test_input_regressions", output / "input-results.json"),
                               ("test_file_export", output / "file-export")):
                run([sys.executable, ROOT / "tools" / (name + ".py"), "--web", ROOT / "web",
                     "--chromium", browser, "--output", path], name)
        elif args.phase == "build":
            sdk = sdk_root()
            os.environ["ANDROID_HOME"] = os.environ["ANDROID_SDK_ROOT"] = str(sdk)
            os.environ["BADNOTE_PYTHON"] = sys.executable
            wrapper = ANDROID / ("gradlew.bat" if os.name == "nt" else "gradlew")
            run([wrapper, "--no-daemon", "--console=plain", "--max-workers=2",
                 *([] if args.online else ["--offline"]), ":app:testUpdateDebugUnitTest",
                 ":app:assembleUpdateDebug", ":app:assembleUpdateDebugAndroidTest", ":app:lintUpdateDebug",
                 ":app:assembleUpdateRelease", ":app:assembleSideBySideRelease"], "android-build", ANDROID)
        elif args.phase == "instrument":
            if not args.device:
                raise ValueError("Instrumentation needs an explicit isolated test device")
            adb = [sdk_root() / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"), "-s", args.device]
            device_info = run([*adb, "shell", "getprop", "ro.build.fingerprint"], "device-fingerprint").strip()
            receipt["device"] = {"serial": args.device, "fingerprint": device_info,
                                 "api": run([*adb, "shell", "getprop", "ro.build.version.sdk"], "device-api").strip(),
                                 "displaySize": run([*adb, "shell", "wm", "size"], "device-display-size").strip(),
                                 "displayDensity": run([*adb, "shell", "wm", "density"], "device-display-density").strip(),
                                 "fontScale": run([*adb, "shell", "settings", "get", "system", "font_scale"], "device-font-scale").strip()}
            apks = [ANDROID / "app/build/outputs/apk/update/debug/app-update-debug.apk",
                    ANDROID / "app/build/outputs/apk/androidTest/update/debug/app-update-debug-androidTest.apk"]
            receipt["apks"] = {str(path.relative_to(ROOT)): digest(path) for path in apks}
            for index, path in enumerate(apks):
                run([*adb, "install", "-r", path], f"install-{index}")
            if args.instrument_mode == "pdf":
                fixtures = args.pdf_fixtures.resolve()
                if not fixtures.is_relative_to(ROOT / "build"):
                    raise ValueError("PDF fixtures must be generated beneath repository/build")
                manifest = json.loads((fixtures / "manifest.json").read_text(encoding="utf-8"))
                if manifest.get("provenance", {}).get("kind") != "synthetic":
                    raise ValueError("PDF checks require explicit synthetic fixtures")
                run([*adb, "shell", "run-as", "com.inkforge.note4.debug", "mkdir", "-p", "files/pdf-fixtures"], "pdf-fixture-directory")
                names = ["original-text-rotated-blank.pdf", "password-protected.pdf", "malformed.pdf", "original-500-pages.pdf"]
                for name in names:
                    path = fixtures / name
                    if path.stat().st_size != manifest["files"][name]["size"] or digest(path) != manifest["files"][name]["sha256"]:
                        raise ValueError("PDF fixture no longer matches its manifest")
                    transfer(adb, path, "files/pdf-fixtures/" + name)
                receipt["pdfFixtures"] = manifest["files"]
            if args.instrument_mode in ("pdf100", "pdf300", "pdf-cancel"):
                fixtures = args.pdf_fixtures.resolve()
                if not fixtures.is_relative_to(ROOT / "build"):
                    raise ValueError("PDF fixtures must be beneath repository/build")
                manifest_path = fixtures / "manifest.json"
                manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                mib = 300 if args.instrument_mode == "pdf300" else 100
                name = f"original-{mib}MiB.pdf"
                source_path = fixtures / name
                declared = manifest["files"][name]
                if (manifest.get("provenance", {}).get("kind") != "synthetic"
                        or source_path.stat().st_size != declared["size"] or digest(source_path) != declared["sha256"]):
                    raise ValueError("Large original-text PDF fixture no longer matches its manifest")
                run([*adb, "shell", "run-as", "com.inkforge.note4.debug", "mkdir", "-p", "files/pdf-stress"], "pdf-stress-directory")
                transfer(adb, manifest_path, "files/pdf-stress/manifest.json")
                transfer(adb, source_path, "files/pdf-stress/" + name)
                receipt["pdfStressFixture"] = {"manifestSha256": digest(manifest_path), "file": name, **declared}
            if args.instrument_mode == "w3":
                fixtures = args.w3_fixtures.resolve()
                if not fixtures.is_relative_to(ROOT / "build"):
                    raise ValueError("W3 fixtures must be beneath repository/build")
                manifest_path = fixtures / "manifest.json"
                manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                if (manifest.get("provenance", {}).get("kind") != "synthetic" or manifest.get("workload") != "W3"
                        or len(manifest.get("pages", [])) != 300 or any(page.get("file") != f"synthetic-W3-{index}.ink.json"
                        or page.get("strokes") != 2000 or page.get("points") != 40000
                        for index, page in enumerate(manifest["pages"]))):
                    raise ValueError("W3 requires exactly 300 full W1-size synthetic pages")
                run([*adb, "shell", "run-as", "com.inkforge.note4.debug", "mkdir", "-p", "files/w3"], "w3-directory")
                transfer(adb, manifest_path, "files/w3/manifest.json")
                w3_run_id = uuid.uuid4().hex
            if args.instrument_mode == "media":
                fixtures = args.media_fixtures.resolve()
                if not fixtures.is_relative_to(ROOT / "build"):
                    raise ValueError("Media fixtures must be generated beneath repository/build")
                manifest_path = fixtures / "manifest.json"
                manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                if manifest.get("provenance", {}).get("kind") != "synthetic":
                    raise ValueError("Media checks require explicit synthetic fixtures")
                run([*adb, "shell", "run-as", "com.inkforge.note4.debug", "mkdir", "-p", "files/media-fixtures"],
                    "media-fixture-directory")
                names = ["aac.m4a", "vorbis.ogg", "opus.ogg", "vorbis.webm", "opus.webm", "legacy-aac.bin", "malformed.bin"]
                if set(manifest["files"]) != set(names):
                    raise ValueError("Media fixture inventory no longer matches the seven compatibility cases")
                for name in ["manifest.json", *names]:
                    path = fixtures / name
                    if name != "manifest.json" and (path.stat().st_size != manifest["files"][name]["size"]
                            or digest(path) != manifest["files"][name]["sha256"]):
                        raise ValueError("Media fixture no longer matches its manifest")
                    transfer(adb, path, "files/media-fixtures/" + name)
                receipt["mediaFixtures"] = {"manifestSha256": digest(manifest_path), "files": manifest["files"],
                                            "physicalMicrophoneQuality": "unmeasured"}
            if args.instrument_mode == "brush":
                node = shutil.which("node")
                if not node:
                    raise ValueError("Original web brush capture requires installed Node.js")
                captured = output / "brush-source.json"
                run([node, ROOT / "tools/capture_brush_reference.mjs", "--output", captured], "brush-capture")
                if digest(captured) != digest(args.brush_fixture.resolve()):
                    raise ValueError("Current original-JS commands do not match the protected brush fixture")
                reference = output / "browser-reference"
                run([sys.executable, ROOT / "tools/render_brush_reference.py", "--fixtures", captured,
                     "--out", reference, "--chromium", installed_browser(args.chromium)], "brush-browser-render")
                manifest_path = reference / "manifest.json"
                receipt["brushReference"] = {"manifest": str(manifest_path.relative_to(ROOT)),
                                             "sha256": digest(manifest_path), "fixtureSha256": digest(captured)}
                run([*adb, "shell", "run-as", "com.inkforge.note4.debug", "mkdir", "-p", "files/brush-raster"],
                    "brush-fixture-directory")
                transfer(adb, manifest_path, "files/brush-raster/manifest.json")
                receipt["brushReference"]["privateTransferExitCode"] = 0
            extras = [] if args.instrument_mode == "full" else ["-e", {
                "ui": "uiOnly", "migration": "migrationOnly", "legacy": "legacyOnly", "ocr": "ocrOnly", "pdf": "pdfOnly", "s3": "s3Only",
                "s5a": "s5aOnly", "front": "s5bOnly", "brush": "brushRasterOnly", "s5c": "s5cOnly", "media": "mediaOnly", "w3": "w3Only",
                "pdf100": "pdfStressMiB", "pdf300": "pdfStressMiB", "pdf-cancel": "pdfStressMiB"
            }[args.instrument_mode], "true"]
            if args.instrument_mode in ("pdf100", "pdf300", "pdf-cancel"):
                extras[-1] = str(mib)
                if args.instrument_mode == "pdf-cancel":
                    extras.extend(["-e", "pdfStressCancel", "true"])
            if args.instrument_mode == "w3":
                extras.extend(["-e", "w3RunId", w3_run_id])
            command = [*adb, "shell", "am", "instrument", "-w", *extras,
                       "com.inkforge.note4.debug.test/com.inkforge.notesstudio.NativeSmokeInstrumentation"]
            text = (run_w3(adb, command, fixtures, manifest, w3_run_id) if args.instrument_mode == "w3"
                    else run(command, "instrumentation"))
            expected = {
                "migration": "INSTRUMENTATION_RESULT: migration=v1_to_v2_records_null_keys_generation_correction_verified",
                "legacy": "INSTRUMENTATION_RESULT: legacyMigration=blob_origins_mime_bytes_failure_cleanup_verified",
                "ocr": "INSTRUMENTATION_RESULT: ocrTasks=real_task_permit_timeout_recovery_failure_verified",
                "s3": "INSTRUMENTATION_RESULT: s3Closure=held_save_digest_bookmark_phone_status_verified",
                "s5a": "INSTRUMENTATION_RESULT: s5a=single_contact_edit_spatial_erase_bitmap_verified",
                "front": ("INSTRUMENTATION_RESULT: s5b=front_multi_pixel_handoff_fallback_lifecycle_verified"
                          if int(receipt["device"]["api"]) >= 31 else
                          "INSTRUMENTATION_RESULT: s5bFallback=api23_30_canvas_only_pixels_cancel_zoom_verified"),
                "brush": "INSTRUMENTATION_RESULT: brushRaster=36_native_bitmap_cases_written",
                "s5c": "INSTRUMENTATION_RESULT: s5c=raw_samples_ocr_cancel_correction_model_generation_verified",
                "media": "INSTRUMENTATION_RESULT: media=containers_mime_async_lifecycle_verified",
                "ui": "INSTRUMENTATION_RESULT: ui=compose_library_settings_accessibility_navigation_verified",
                "w3": "INSTRUMENTATION_RESULT: w3=300_persisted_w1_pages_lazy_viewport_ocr_verified",
                "pdf100": "INSTRUMENTATION_RESULT: pdfStress=original_100MiB_full_index_tiles_delete_verified",
                "pdf300": "INSTRUMENTATION_RESULT: pdfStress=original_300MiB_full_index_tiles_delete_verified",
                "pdf-cancel": "INSTRUMENTATION_RESULT: pdfStress=original_100MiB_cancelled_deleted_no_resurrection",
                "pdf": "INSTRUMENTATION_RESULT: pdf=api23plus_original_text_import_legacy_roundtrip_lifecycle_verified",
            }.get(args.instrument_mode, "INSTRUMENTATION_RESULT: passed=true")
            require_instrument_success(text, expected)
            if args.instrument_mode == "ui":
                screenshots = output / "compose-screens"
                screenshots.mkdir()
                receipt["composeScreens"] = []
                for key, private in compose_screen_contract(text):
                    path = screenshots / private.rsplit("/", 1)[1]
                    with path.open("wb") as destination:
                        subprocess.run([*map(str, adb), "exec-out", "run-as", "com.inkforge.note4.debug", "cat",
                            "files/" + private], stdout=destination, stderr=subprocess.PIPE, check=True, timeout=60)
                    with Image.open(path) as screenshot:
                        screenshot.load()
                        if screenshot.format != "PNG" or min(screenshot.size) <= 0:
                            raise RuntimeError("Invalid actual Compose screenshot")
                        receipt["composeScreens"].append({"path": str(path.relative_to(ROOT)), "sha256": digest(path),
                            "size": screenshot.size, "privatePath": private, "resultKey": key})
            if args.instrument_mode == "w3":
                for key, value in (("w3RunId", w3_run_id), ("w3PersistedPages", "300"), ("w3PersistedStrokes", "600000"),
                                   ("w3PersistedPoints", "12000000"), ("w3OcrPageStrokes", "2000")):
                    if text.splitlines().count(f"INSTRUMENTATION_RESULT: {key}={value}") != 1:
                        raise RuntimeError("W3 completed receipt lacks exact notebook/input binding: " + key)
            if args.instrument_mode == "front":
                private, names, point = front_screen_contract(text, int(receipt["device"]["api"]))
                screenshots = output / "front-screens"
                screenshots.mkdir()
                receipt["frontScreens"] = {"privateDirectory": private, "files": [], "penProbeScreenXY": point}
                for name in names:
                    path = screenshots / name
                    with path.open("wb") as destination:
                        subprocess.run([*map(str, adb), "exec-out", "run-as", "com.inkforge.note4.debug", "cat",
                            private + "/" + name], stdout=destination, stderr=subprocess.PIPE, check=True, timeout=60)
                    with Image.open(path) as screenshot:
                        screenshot.load()
                        if screenshot.format != "PNG" or min(screenshot.size) <= 0:
                            raise RuntimeError("Invalid composed screenshot: " + name)
                        proof = {"path": str(path.relative_to(ROOT)), "sha256": digest(path), "size": screenshot.size}
                        if point is not None and name in ("00-baseline.png", "01-committed.png"):
                            if not (0 <= point[0] < screenshot.width and 0 <= point[1] < screenshot.height):
                                raise RuntimeError("Saved pen probe falls outside screenshot")
                            rgb = screenshot.convert("RGB").getpixel(point)
                            proof["penProbeRgb"] = rgb
                            if (name == "00-baseline.png" and min(rgb) <= 215) or (
                                    name == "01-committed.png" and not (rgb[0] < 100 and rgb[1] < 130 and rgb[2] < 170)):
                                raise RuntimeError("Saved screenshot does not prove blank-page to ink transition")
                        receipt["frontScreens"]["files"].append(proof)
            if args.instrument_mode == "brush":
                native = output / "native-reference"
                native.mkdir()
                for index in range(36):
                    path = native / f"case-{index:03d}.png"
                    with path.open("wb") as destination:
                        subprocess.run([*map(str, adb), "exec-out", "run-as", "com.inkforge.note4.debug", "cat",
                            f"files/brush-raster/native/{path.name}"], stdout=destination,
                            stderr=subprocess.PIPE, check=True, timeout=60)
                comparison = output / "brush-comparison.json"
                run([sys.executable, ROOT / "tools/compare_brush_rasters.py", "--reference", reference,
                     "--native", native, "--out", comparison], "brush-native-comparison")
                receipt["brushComparison"] = {"path": str(comparison.relative_to(ROOT)), "sha256": digest(comparison)}
        elif args.phase == "replay":
            if not args.device:
                raise ValueError("OCR replay needs an explicit isolated test device")
            adb_path = sdk_root() / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
            adb = [adb_path, "-s", args.device]
            receipt["device"] = {"serial": args.device,
                "fingerprint": run([*adb, "shell", "getprop", "ro.build.fingerprint"], "device-fingerprint").strip(),
                "api": run([*adb, "shell", "getprop", "ro.build.version.sdk"], "device-api").strip()}
            apks = [ANDROID / "app/build/outputs/apk/update/debug/app-update-debug.apk",
                    ANDROID / "app/build/outputs/apk/androidTest/update/debug/app-update-debug-androidTest.apk"]
            receipt["apks"] = {str(path.relative_to(ROOT)): digest(path) for path in apks}
            for index, path in enumerate(apks):
                run([*adb, "install", "-r", path], f"install-{index}")
            cases = [("w0-b2-cold", "W0", "B2", "cold", "ko-primary", "policy", "load-w0/synthetic-W0-0.ink.json"),
                     ("w1-b2-cold", "W1", "B2", "cold", "ko-primary", "policy", "load-w1/synthetic-W1-0.ink.json"),
                     ("w2-b2-cold", "W2", "B2", "cold", "ko-primary", "policy", "load-w2/synthetic-W2-0.ink.json"),
                     ("w0-b1-warm", "W0", "B1", "warm", "ko-primary", "policy", "replay-inputs/w0-geometric-group.json"),
                     ("w0-b2-warm", "W0", "B2", "warm", "ko-primary", "policy", "load-w0/synthetic-W0-0.ink.json"),
                     ("w0-b3-mixed-warm", "W0", "B3", "warm", "mixed-review", "policy", "load-w0/synthetic-W0-0.ink.json"),
                     ("w0-b3-cache", "W0", "B3", "cold", "ko-primary", "cache", "load-w0/synthetic-W0-0.ink.json")]
            receipt["replays"] = []
            for name, workload, mode, state, policy, feature, filename in cases:
                if args.replay_set == "w0" and workload != "W0":
                    continue
                source_path = ROOT / "build/ocr" / filename
                input_sha = digest(source_path)
                destination = output / name
                run([sys.executable, ROOT / "tools/bench_ocr.py", "replay", "--adb", adb_path,
                     "--device", args.device, "--input", source_path, "--path", mode, "--workload", workload,
                     "--state", state, "--policy", policy, "--feature", feature, "--output", destination,
                     *([] if workload == "W2" else ["--require-complete"])], "replay-" + name)
                replay_receipt = json.loads((destination / "replay-receipt.json").read_text(encoding="utf-8"))
                diagnostic_path = destination / "diagnostic.json"
                diagnostic = json.loads(diagnostic_path.read_text(encoding="utf-8"))
                source = json.loads(source_path.read_text(encoding="utf-8"))
                original = source.get("sourcePage", source)
                original_ids = [stroke["id"] for stroke in original["objects"] if stroke.get("type") == "stroke"]
                ids = [identity for region in diagnostic["result"].get("regions", []) for identity in region.get("strokeIds", [])]
                if (replay_receipt.get("buildSha") != digest(apks[0]) or replay_receipt.get("status") != "recorded"
                        or replay_receipt.get("inputSha256") != input_sha or digest(source_path) != input_sha
                        or replay_receipt.get("diagnosticSha256") != digest(diagnostic_path)
                        or diagnostic["result"].get("status") not in ("complete", "partial")
                        or len(ids) != len(original_ids) or len(set(ids)) != len(ids) or set(ids) != set(original_ids)):
                    raise RuntimeError("Replay source/build/diagnostic or exact stroke retention failed: " + name)
                hits = diagnostic.get("execution", {}).get("regionCacheHits", 0)
                if feature == "cache" and hits < 1:
                    raise RuntimeError("Prepared cache replay did not reuse any region")
                receipt["replays"].append({"name": name, "inputSha256": input_sha,
                    "receipt": str((destination / "replay-receipt.json").relative_to(ROOT)),
                    "receiptSha256": digest(destination / "replay-receipt.json"),
                    "diagnostic": str(diagnostic_path.relative_to(ROOT)), "diagnosticSha256": digest(diagnostic_path),
                    "recognitionStatus": diagnostic["result"]["status"], "preservedStrokes": len(ids),
                    "regionCacheHits": hits, "physicalAcceptance": "unmeasured", "provenance": "synthetic-load-only"})
        elif args.phase == "apk":
            sdk = sdk_root()
            os.environ["ANDROID_SDK_ROOT"] = str(sdk)
            for variant in ("update", "sideBySide"):
                path = ANDROID / f"app/build/outputs/apk/{variant}/release/app-{variant}-release.apk"
                package = "com.inkforge.note4" if variant == "update" else "com.inkforge.note5"
                run([sys.executable, ROOT / "tools/verify_apk.py", path, "--native-min-api-limit", "23",
                     "--expected-package", package, "--expected-min-sdk", "23",
                     "--expected-target-sdk", "36", "--expected-version-code", "411", "--expected-version-name", "4.1.1-beta.1",
                     "--expected-certificate", "45bc9430d2c32457c5d944561176a7ec861b29f6bcb1b4244806307c9f3d0847",
                     "--pdfium-manifest", ROOT / "build/dependencies/pdfium-8086/verification.json",
                     *[argument for guard in args.guarded_native_library for argument in ("--guarded-native-library", guard)],
                     *[argument for asset in args.require_asset for argument in ("--require-asset", asset)]], "apk-" + variant)
        elif args.phase == "upgrade":
            directory = args.upgrade_directory.resolve()
            if not directory.is_relative_to(ROOT / "build"):
                raise ValueError("Installed upgrade captures must be beneath repository/build")
            old_apks = {"update": ("bad-note-Android-4.0.1-Update.apk", "fe7d9b32f05a70d175ef6368b401469d60b0a4a1449f3c7750f36e03b67f59c6"),
                        "sideBySide": ("bad-note-Android-4.0.1-SideBySide.apk", "6840f1dfa4fedb118d203d0ef1cbba9e93bc4fc095565d844a105291deb6f915")}
            receipt["releaseUpgrades"] = []
            for variant, (filename, pinned_sha) in old_apks.items():
                old_apk = ROOT / "build/apk" / filename
                new_apk = ANDROID / f"app/build/outputs/apk/{variant}/release/app-{variant}-release.apk"
                package = "com.inkforge.note4" if variant == "update" else "com.inkforge.note5"
                if digest(old_apk) != pinned_sha:
                    raise ValueError("Retained signed 4.0.1 APK no longer matches its pinned input")
                captures = []
                for label, version, apk in (("before", 401, old_apk), ("after", 411, new_apk)):
                    captured = directory / variant / label
                    capture_path = captured / "capture-receipt.json"
                    capture = json.loads(capture_path.read_text(encoding="utf-8"))
                    captured_sources = capture.get("sourceFiles", {})
                    if (capture.get("status") != "captured"
                            or release_capture_inputs(captured_sources) != release_capture_inputs(before)
                            or capture.get("apk", {}).get("package") != package
                            or capture["apk"].get("versionCode") != version
                            or capture["apk"].get("sha256") != digest(apk)
                            or digest(captured / "installed.apk") != digest(apk)):
                        raise RuntimeError("Upgrade capture is not bound to the actual current source/installed APK")
                    for name, expected_sha in capture.get("dataFiles", {}).items():
                        path = (captured / name).resolve()
                        if not path.is_relative_to(captured):
                            raise ValueError("Captured data path escapes its directory")
                        # SQLite may change its transient read-lock SHM metadata;
                        # DB/WAL and every original asset remain byte-bound.
                        if not name.endswith("-shm") and digest(path) != expected_sha:
                            raise RuntimeError("Captured installed data changed before comparison")
                    unrelated = [{"path": name, "capturedSha256": captured_sources.get(name),
                                  "currentSha256": before.get(name)}
                                 for name in sorted(captured_sources.keys() | before.keys())
                                 if captured_sources.get(name) != before.get(name)]
                    captures.append({"path": str(capture_path.relative_to(ROOT)), "sha256": digest(capture_path),
                        "releaseInputFiles": release_capture_inputs(before),
                        "unrelatedSourceChanges": unrelated,
                        "reuseBasis": "Original captures retained unchanged; exact installed/current APK bytes and every production source-set/build input unchanged. Test/tool changes are separately checked on the current all-source snapshot; this does not claim another install/UI run."})
                comparison = output / (variant + "-comparison.json")
                run([sys.executable, ROOT / "tools/check_release_upgrade.py", "--before", directory / variant / "before",
                     "--after", directory / variant / "after", "--out", comparison], "upgrade-" + variant)
                receipt["releaseUpgrades"].append({"variant": variant, "captures": captures,
                    "comparison": str(comparison.relative_to(ROOT)), "comparisonSha256": digest(comparison),
                    "pdfiumPreparationManifestSha256": digest(ROOT / "build/dependencies/pdfium-8086/verification.json"),
                    "physicalDevice": "unmeasured; task-owned emulator only"})
        else:
            run(["git", "diff", "--check"], "diff-check")
        after = source_snapshot()
        if before != after:
            receipt["changedDuringValidation"] = sorted(k for k in before.keys() | after.keys() if before.get(k) != after.get(k))
            raise RuntimeError("Source changed during validation; rerun after assembly")
        receipt["status"] = "passed"
    except Exception as error:
        receipt.update(status="failed", error=str(error))
    finally:
        receipt["completedAtUtc"] = datetime.now(timezone.utc).isoformat()
        receipt_path.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"status": receipt["status"], "receipt": str(receipt_path.relative_to(ROOT))}), flush=True)
    if receipt["status"] != "passed":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
