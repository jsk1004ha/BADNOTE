#!/usr/bin/env python3
"""Copy the established UI's vector geometry for Android's native Canvas renderer."""
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]
source = (ROOT / "legacy/web/app.js").read_text(encoding="utf-8")
block = source.split("const ICONS = {", 1)[1].split("\n  };", 1)[0]
icons = {quoted or bare: value for quoted, bare, value in re.findall(
    r"^\s*(?:'([^']+)'|([\w]+)):\s*'([^']*)'", block, re.MULTILINE)}
assert len(icons) > 60
recognition = (ROOT / "legacy/web/recognition.js").read_text(encoding="utf-8")
icons["ocr"] = re.search(r"const OCR_ICON = '([^']+)'", recognition).group(1)
icons["minus"] = '<path d="M5 12h14"/>'
# Native settings uses these 24-unit SVG paths, drawn for BADNOTE.
icons.update({
    "settings-back": '<path d="m11 5-7 7 7 7M4 12h15"/>',
    "settings-backup": '<path d="M4 14h4l2 3h4l2-3h4v6H4zM12 4v9m-4-5 4-4 4 4"/>',
    "settings-keyboard": '<rect x="3" y="6" width="18" height="12" rx="2"/>'
                         '<path d="M7 10h.5m4.25 0h.5m4.25 0h.5M7 14h.5m3 0H17"/>',
    "settings-license": '<path d="M7 3h7l4 4v14H7zM14 3v5h4M10 12h5m-5 4h5"/>',
    "settings-update": '<path d="M5 11a7 7 0 1 1 2 6M5 6v5h5"/>',
})
destination = ROOT / "android/app/src/nativeAssets/icons.json"
destination.write_text(json.dumps(icons, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Copied {len(icons)} original vector icons to {destination}")
