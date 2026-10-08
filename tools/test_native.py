#!/usr/bin/env python3
"""Build/test the Kotlin runtime; optionally exercise an isolated Android .debug app."""
from __future__ import annotations

import argparse
import os
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android"


def run(args: list[str], *, capture: bool = False) -> subprocess.CompletedProcess[str]:
    print("+", " ".join(args), flush=True)
    return subprocess.run(args, cwd=ANDROID, check=True, text=True, encoding="utf-8", errors="replace", capture_output=capture)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle", default=os.environ.get("GRADLE", str(ANDROID / ("gradlew.bat" if os.name == "nt" else "gradlew"))))
    parser.add_argument("--device", help="Explicit adb serial for an emulator or a consenting test device")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--ui-only", action="store_true", help="Run stylus, scribble-erasing and native UI regressions; skip the already-tested export/migration stress suite")
    args = parser.parse_args()
    tasks = [":app:testUpdateDebugUnitTest", ":app:assembleUpdateDebug"]
    if args.device:
        tasks.append(":app:assembleUpdateDebugAndroidTest")
    run([args.gradle, "--console=plain", *tasks])
    if not args.device:
        print("JVM tests include 100/300 MiB streaming export and 100 MiB legacy import in a 96 MiB heap.")
        return
    adb = [args.adb, "-s", args.device]
    for apk in ("app/build/outputs/apk/update/debug/app-update-debug.apk", "app/build/outputs/apk/androidTest/update/debug/app-update-debug-androidTest.apk"):
        run([*adb, "install", "-r", str(ANDROID / apk)])
    extras = ["-e", "uiOnly", "true"] if args.ui_only else []
    result = run([*adb, "shell", "am", "instrument", "-w", *extras, "com.inkforge.note4.debug.test/com.inkforge.notesstudio.NativeSmokeInstrumentation"], capture=True)
    output = ROOT / ("build/classic-ui-instrumentation.log" if args.ui_only else "build/native-instrumentation.log")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(result.stdout + result.stderr, encoding="utf-8")
    print(result.stdout)
    if "INSTRUMENTATION_RESULT: passed=true" not in result.stdout:
        raise SystemExit(f"Native instrumentation failed; see {output}")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode) from error
