#!/usr/bin/env python3
"""Compare native brush PNGs with source-bound Chrome command-replay images."""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageStat


# Declared before obtaining native raster results. These permit backend edge
# coverage and 8-bit alpha rounding, while retaining meaningful ink differences.
LIMITS = {"relativeInkMassError": 0.08, "relativePixelError": 0.12,
          "minimumBidirectionalCoverage": 0.99, "edgeTolerancePixels": 1,
          "inkThreshold": 8}


def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def background(raster: dict) -> Image.Image:
    image = Image.new("RGB", (raster["width"], raster["height"]))
    draw = ImageDraw.Draw(image)
    cell = raster["checkerCell"]
    for y in range(0, image.height, cell):
        for x in range(0, image.width, cell):
            color = raster["backgroundColors"][(x // cell + y // cell) % 2]
            draw.rectangle((x, y, x + cell - 1, y + cell - 1), fill=color)
    return image


def mass(image: Image.Image) -> float:
    return sum(ImageStat.Stat(image).sum)


def mask(delta: Image.Image) -> Image.Image:
    red, green, blue = delta.split()
    strongest = ImageChops.lighter(ImageChops.lighter(red, green), blue)
    return strongest.point(lambda x: 255 if x >= LIMITS["inkThreshold"] else 0)


def compare(reference: Image.Image, native: Image.Image, base: Image.Image) -> dict:
    expected = ImageChops.difference(reference, base)
    actual = ImageChops.difference(native, base)
    expected_mass, actual_mass = mass(expected), mass(actual)
    if expected_mass <= 0:
        raise ValueError("Reference contains no ink")
    expected_mask, actual_mask = mask(expected), mask(actual)
    if not mass(expected_mask) or not mass(actual_mask):
        coverage = 0.0
    else:
        radius = LIMITS["edgeTolerancePixels"]
        expanded_expected = expected_mask.filter(ImageFilter.MaxFilter(radius * 2 + 1))
        expanded_actual = actual_mask.filter(ImageFilter.MaxFilter(radius * 2 + 1))
        expected_covered = mass(ImageChops.multiply(expected_mask, expanded_actual)) / mass(expected_mask)
        actual_covered = mass(ImageChops.multiply(actual_mask, expanded_expected)) / mass(actual_mask)
        coverage = min(expected_covered, actual_covered)
    mass_error = abs(actual_mass - expected_mass) / expected_mass
    pixel_error = mass(ImageChops.difference(reference, native)) / expected_mass
    return {
        "passed": (mass_error <= LIMITS["relativeInkMassError"]
                   and pixel_error <= LIMITS["relativePixelError"]
                   and coverage >= LIMITS["minimumBidirectionalCoverage"]),
        "referenceInkMass": expected_mass, "nativeInkMass": actual_mass,
        "relativeInkMassError": mass_error, "relativePixelError": pixel_error,
        "bidirectionalCoverageWithinOnePixel": coverage,
    }


def run(args: argparse.Namespace) -> dict:
    manifest_path = args.reference / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != 1 or len(manifest["cases"]) != 36:
        raise ValueError("Expected the 36-case source-bound browser manifest")
    base = background(manifest["raster"])
    results = []
    for index, case in enumerate(manifest["cases"]):
        filename = f"case-{index:03d}.png"
        if case["index"] != index or case["file"] != filename:
            raise ValueError("Noncanonical case filename or index")
        reference_path, native_path = args.reference / filename, args.native / filename
        if sha256(reference_path) != case["sha256"]:
            raise ValueError(f"Reference hash changed: {filename}")
        with Image.open(reference_path) as expected, Image.open(native_path) as actual:
            if expected.size != base.size or actual.size != base.size:
                raise ValueError(f"Raster dimension mismatch: {filename}")
            if actual.mode == "RGBA" and actual.getchannel("A").getextrema() != (255, 255):
                raise ValueError(f"Native reference background must be opaque: {filename}")
            result = compare(expected.convert("RGB"), actual.convert("RGB"), base)
        results.append({"index": index, "name": case["name"], "scale": case["scale"],
                        "file": filename, "referenceSha256": case["sha256"],
                        "nativeSha256": sha256(native_path), **result})
    return {
        "schemaVersion": 1, "passed": all(case["passed"] for case in results),
        "limits": LIMITS, "referenceManifestSha256": sha256(manifest_path),
        "originalWebSourceSha256": manifest["sourceSha256"],
        "comparisonSourceSha256": sha256(pathlib.Path(__file__)), "cases": results,
        "limitations": ["Synthetic raster regression; no physical latency or OCR accuracy claim",
                        "APK/runtime binding belongs to the native execution receipt"],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", type=pathlib.Path, required=True)
    parser.add_argument("--native", type=pathlib.Path, required=True)
    parser.add_argument("--out", type=pathlib.Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        parser.error("Refusing to overwrite an existing comparison receipt")
    try:
        result = run(args)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(json.dumps({"passed": False, "error": str(error)}))
        return 1
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    failures = [case["index"] for case in result["cases"] if not case["passed"]]
    print(json.dumps({"passed": result["passed"], "cases": len(result["cases"]),
                      "failedCaseIndexes": failures, "report": str(args.out)}))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
