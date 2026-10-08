#!/usr/bin/env python3
"""Copy the established UI's vector geometry for Android's native Canvas renderer."""
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]
source = (ROOT / "web/app.js").read_text(encoding="utf-8")
block = source.split("const ICONS = {", 1)[1].split("\n  };", 1)[0]
icons = {quoted or bare: value for quoted, bare, value in re.findall(
    r"^\s*(?:'([^']+)'|([\w]+)):\s*'([^']*)'", block, re.MULTILINE)}
assert len(icons) > 60
recognition = (ROOT / "web/recognition.js").read_text(encoding="utf-8")
icons["ocr"] = re.search(r"const OCR_ICON = '([^']+)'", recognition).group(1)
destination = ROOT / "android/app/src/nativeAssets/icons.json"
destination.write_text(json.dumps(icons, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Copied {len(icons)} original vector icons to {destination}")
