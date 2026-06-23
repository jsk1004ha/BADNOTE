#!/usr/bin/env python3
"""Build InkForge Notes Studio Android release APKs with Gradle."""
from __future__ import annotations

import argparse
import os
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android"


def run(command: list[str]) -> None:
    print("+", " ".join(command), flush=True)
    subprocess.run(command, cwd=ANDROID, check=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--variant", choices=("update", "sideBySide", "both"), default="both")
    parser.add_argument("--gradle", default=os.environ.get("GRADLE", "gradle"))
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()

    if not (ANDROID / "local.properties").exists() and not (os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")):
        raise SystemExit("Android SDK 경로가 없습니다. ANDROID_HOME/ANDROID_SDK_ROOT 또는 android/local.properties를 설정하십시오.")
    if not (ANDROID / "local-signing.properties").exists():
        raise SystemExit("릴리스 서명 설정이 없습니다. android/local-signing.properties.example을 복사해 작성하십시오.")
    gradle = shutil.which(args.gradle) or args.gradle
    tasks = []
    if args.variant in ("update", "both"):
        tasks.append(":app:assembleUpdateRelease")
    if args.variant in ("sideBySide", "both"):
        tasks.append(":app:assembleSideBySideRelease")
    command = [gradle, "--no-daemon", "--console=plain"]
    if args.offline:
        command.append("--offline")
    command.extend(tasks)
    run(command)

    outputs = {
        "update": ANDROID / "app/build/outputs/apk/update/release/app-update-release.apk",
        "sideBySide": ANDROID / "app/build/outputs/apk/sideBySide/release/app-sideBySide-release.apk",
    }
    for name, path in outputs.items():
        if args.variant in (name, "both") and path.exists():
            print(f"{name}: {path}")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode) from error
