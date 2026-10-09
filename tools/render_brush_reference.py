#!/usr/bin/env python3
"""Rasterize the captured, original web brush commands in an isolated browser."""
from __future__ import annotations

import argparse
import asyncio
import base64
import hashlib
import json
import math
import pathlib
import re
import shutil

from playwright.async_api import async_playwright


METHODS = {
    "save": 0, "restore": 0, "beginPath": 0, "arc": 5,
    "fill": 0, "moveTo": 2, "quadraticCurveTo": 4, "lineTo": 2, "stroke": 0,
}
STYLES = {
    "globalAlpha", "strokeStyle", "fillStyle", "lineCap", "lineJoin",
    "lineWidth", "globalCompositeOperation",
}
RASTER = {
    "width": 1024, "height": 1024, "checkerCell": 16,
    "backgroundColors": ["#ffffff", "#e7edf5"], "translate": [32, 32],
}
REPLAY = """({operations, scale, raster, transparent}) => {
    const canvas = document.createElement('canvas');
    canvas.width = raster.width; canvas.height = raster.height;
    const ctx = canvas.getContext('2d', {alpha: Boolean(transparent), colorSpace: 'srgb'});
    const cell = raster.checkerCell;
    for (let y = 0; !transparent && y < canvas.height; y += cell) {
        for (let x = 0; x < canvas.width; x += cell) {
            ctx.fillStyle = raster.backgroundColors[((x / cell) + (y / cell)) % 2];
            ctx.fillRect(x, y, cell, cell);
        }
    }
    ctx.translate(...raster.translate); ctx.scale(scale, scale);
    for (const op of operations) {
        if (op[0] === 'set') ctx[op[1]] = op[2];
        else ctx[op[0]](...op.slice(1));
    }
    return canvas.toDataURL('image/png');
}"""


def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_operations(operations: list) -> None:
    depth = 0
    if not isinstance(operations, list) or len(operations) > 100_000:
        raise ValueError("Invalid drawing-command count")
    for op in operations:
        if not isinstance(op, list) or not op:
            raise ValueError("Invalid drawing command")
        name = op[0]
        if name == "set":
            if len(op) != 3 or op[1] not in STYLES:
                raise ValueError("Unsupported drawing style")
            key, value = op[1:]
            if key in {"fillStyle", "strokeStyle"}:
                valid = isinstance(value, str) and bool(re.fullmatch(r"#[0-9a-fA-F]{6}", value))
            elif key == "lineCap":
                valid = value in {"round", "butt", "square"}
            elif key == "lineJoin":
                valid = value in {"round", "bevel", "miter"}
            elif key == "globalCompositeOperation":
                valid = value in {"source-over", "multiply"}
            else:
                valid = (isinstance(value, (int, float)) and not isinstance(value, bool)
                         and math.isfinite(value) and value >= 0
                         and (value <= 1 if key == "globalAlpha" else value <= 1000))
            if not valid:
                raise ValueError(f"Invalid {key} value")
        elif name in METHODS and len(op) == METHODS[name] + 1:
            if any(not isinstance(x, (int, float)) or isinstance(x, bool)
                   or not math.isfinite(x) or abs(x) > 100_000 for x in op[1:]):
                raise ValueError("Non-finite or excessive drawing coordinate")
            if name == "arc" and op[3] < 0:
                raise ValueError("Negative arc radius")
            if name == "save":
                depth += 1
            elif name == "restore":
                depth -= 1
                if depth < 0:
                    raise ValueError("Unbalanced restore")
        else:
            raise ValueError(f"Unsupported drawing command: {name}")
    if depth:
        raise ValueError("Unbalanced save")


def load_cases(fixture_path: pathlib.Path, source: pathlib.Path) -> tuple[dict, list[dict]]:
    if fixture_path.stat().st_size > 8 * 1024 * 1024:
        raise ValueError("Brush reference exceeds 8 MiB")
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
    if fixture.get("schemaVersion") != 1 or fixture.get("sourceSha256") != sha256(source):
        raise ValueError("Original web source hash does not match captured commands")
    definitions = {item["name"]: item for item in fixture["fixtures"]}
    if len(definitions) != len(fixture["fixtures"]):
        raise ValueError("Duplicate brush definition")
    cases = []
    for index, rendered in enumerate(fixture["renderFixtures"]):
        definition = definitions[rendered["name"]]
        scale = rendered["scale"]
        if scale not in (1, 3):
            raise ValueError("Unsupported reference scale")
        validate_operations(rendered["operations"])
        stroke = {**definition["input"], "type": "stroke", "color": "#172033"}
        if definition["settings"] is not None:
            stroke["settings"] = definition["settings"]
        cases.append({"index": index, "name": rendered["name"], "scale": scale,
                      "file": f"case-{index:03d}.png", "stroke": stroke,
                      "operations": rendered["operations"]})
    if not cases or len(cases) > 100:
        raise ValueError("Invalid reference case count")
    return fixture, cases


async def render(args: argparse.Namespace) -> dict:
    workspace = args.workspace.resolve()
    output = args.out.resolve()
    build = (workspace / "build").resolve()
    if output == build or not output.is_relative_to(build):
        raise ValueError("Reference output must be a new directory beneath workspace/build")
    if output.exists():
        raise ValueError("Refusing to overwrite an existing brush reference")
    source = workspace / "web/app.js"
    fixture, cases = load_cases(args.fixtures, source)
    chrome = args.chromium or shutil.which("chrome") or shutil.which("chromium")
    if not chrome:
        candidate = pathlib.Path(r"C:\Program Files\Google\Chrome\Application\chrome.exe")
        if candidate.is_file():
            chrome = str(candidate)
    if not chrome:
        raise ValueError("Installed Chrome/Chromium executable was not found")
    output.mkdir(parents=True)
    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch(executable_path=str(chrome), headless=True)
        try:
            version = browser.version
            context = await browser.new_context()
            await context.route("**/*", lambda route: route.abort())
            page = await context.new_page()
            for case in cases:
                url = await page.evaluate(REPLAY, {
                    "operations": case.pop("operations"), "scale": case["scale"], "raster": RASTER,
                    "transparent": args.transparent,
                })
                prefix = "data:image/png;base64,"
                if not url.startswith(prefix):
                    raise ValueError("Browser did not return a PNG")
                pixels = base64.b64decode(url[len(prefix):], validate=True)
                path = output / case["file"]
                path.write_bytes(pixels)
                case.update({"sha256": sha256(path), "bytes": len(pixels)})
            await context.close()
        finally:
            await browser.close()
    # Check again: a concurrent source edit cannot produce an accepted manifest.
    if fixture["sourceSha256"] != sha256(source):
        raise ValueError("Original web source changed while rendering")
    manifest = {
        "schemaVersion": 1, "purpose": "Synthetic original-JS drawing-command raster reference",
        "method": "Captured original JS operations replayed in actual Chrome Canvas 2D",
        "source": "web/app.js", "sourceSha256": sha256(source),
        "fixturesSha256": sha256(args.fixtures), "generatorSha256": sha256(pathlib.Path(__file__)),
        "browser": {"executable": str(chrome), "version": version},
        "raster": ({**RASTER, "transparent": True} if args.transparent else RASTER), "cases": cases,
        "limitations": ["No physical stylus latency or handwriting-accuracy measurement",
                        "Cross-platform rasterization differences require separate comparison"],
    }
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                                          encoding="utf-8")
    return {"passed": True, "cases": len(cases), "manifest": str(output / "manifest.json"),
            "manifestSha256": sha256(output / "manifest.json"), "browserVersion": version}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace", type=pathlib.Path, default=pathlib.Path.cwd())
    parser.add_argument("--fixtures", type=pathlib.Path, required=True)
    parser.add_argument("--out", type=pathlib.Path, required=True)
    parser.add_argument("--chromium")
    parser.add_argument("--transparent", action="store_true", help="Separate raw-alpha diagnostic; leaves normal references unchanged")
    args = parser.parse_args()
    try:
        result = asyncio.run(render(args))
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(json.dumps({"passed": False, "error": str(error)}))
        return 1
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
