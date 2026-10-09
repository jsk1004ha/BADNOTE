#!/usr/bin/env python3
"""Capture read-only native baseline symbols, settings and object kinds from Git."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PREFIX = "android/app/src/main/java/com/inkforge/notesstudio/"
FILES = ("MainActivity.kt", "NoteModel.kt", "NoteRepository.kt", "InkCanvasView.kt",
         "InkGeometry.kt", "NoteRenderer.kt", "AudioController.kt", "AppUpdater.kt",
         "NativeFileExport.kt", "LegacyMigration.kt", "BackgroundLoader.kt", "RootSafeArea.kt")


def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, check=True).stdout.decode("utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", default="HEAD")
    parser.add_argument("--output", type=Path, default=ROOT / "build/native-contract/baseline.json")
    args = parser.parse_args()
    commit = git("rev-parse", "--verify", "--end-of-options", args.ref + "^{commit}").strip()
    output = args.output.resolve()
    if not output.is_relative_to(ROOT / "build"):
        raise ValueError("Write generated inventory beneath repository/build")
    files = {}
    for name in FILES:
        source = git("show", commit + ":" + PREFIX + name)
        symbols = [{"name": match.group(1), "line": source.count("\n", 0, match.start()) + 1}
                   for match in re.finditer(r"\bfun\s+(?:<[^\n>]+>\s*)?(\w+)\s*\(", source)]
        settings = sorted(set(re.findall(r'\bsettings\.(?:opt\w*|put|f)\(\s*"([^"\n]+)"', source)))
        object_types = sorted(set(re.findall(r'(?:optString\("type"\)\s*==\s*|"type"\s+to\s+)"([^"\n]+)"', source)))
        files[PREFIX + name] = {"sha256": hashlib.sha256(source.encode()).hexdigest(),
                                "functions": symbols, "settings": settings, "objectTypes": object_types}
    report = {"schemaVersion": 1, "sourceCommit": commit, "files": files,
              "scope": "source inventory; symbols and literal keys are not proof of runtime equivalence",
              "verification": "Map user flows to current callbacks and fresh tests separately"}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"sourceCommit": commit, "files": len(files),
                      "functions": sum(len(file["functions"]) for file in files.values()), "output": str(output)}))


if __name__ == "__main__":
    main()
