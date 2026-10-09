#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import zipfile
from pathlib import Path
from apk_native import inspect_elf


def tool(root: Path, name: str) -> Path:
    for suffix in ("", ".exe", ".bat", ".cmd"):
        candidate = root / f"{name}{suffix}"
        if candidate.exists():
            return candidate
    return root / name


def find_build_tools(explicit: str | None) -> Path:
    if explicit:
        root = Path(explicit).expanduser().resolve()
        if tool(root, "apksigner").exists():
            return root
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if sdk:
        candidates = sorted((Path(sdk) / "build-tools").glob("*"), reverse=True)
        for item in candidates:
            if tool(item, "apksigner").exists() and tool(item, "zipalign").exists() and tool(item, "aapt2").exists():
                return item
    raise SystemExit("Android Build Tools 경로를 찾지 못했습니다. --build-tools 또는 ANDROID_SDK_ROOT를 지정하십시오.")


def command(args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, text=True, capture_output=True)


def certificate_digest(output: str) -> str | None:
    # Build Tools 37 names scheme signers; earlier versions use Signer #1.
    if not re.search(r"^Number of signers: 1\s*$", output, re.M):
        return None
    values = {value.lower() for value in re.findall(
        r"^(?:Signer #\d+|V\d+\.\d+ Signer:) certificate SHA-256 digest: ([0-9a-f]{64})\s*$",
        output, re.I | re.M)}
    return next(iter(values)) if len(values) == 1 else None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--build-tools")
    parser.add_argument("--native-min-api-limit", type=int, help="Require unguarded native libraries to declare an API no greater than this")
    parser.add_argument("--guarded-native-library", action="append", default=[], metavar="LIBNAME=API",
                        help="Declared source-level API guard for a library; validate the guard separately")
    parser.add_argument("--require-asset", action="append", default=[], metavar="APK_PATH",
                        help="Require a non-empty shipped notice or other asset at this exact APK path")
    parser.add_argument("--expected-package")
    parser.add_argument("--expected-certificate", help="Expected release signer SHA-256")
    parser.add_argument("--expected-min-sdk", type=int)
    parser.add_argument("--expected-target-sdk", type=int)
    parser.add_argument("--expected-version-code")
    parser.add_argument("--expected-version-name")
    parser.add_argument("--pdfium-manifest", type=Path, help="Require each shipped PDFium core and notice to match the verified pinned SDK")
    args = parser.parse_args()
    apk = args.apk.resolve()
    tools = find_build_tools(args.build_tools)
    if not apk.is_file():
        raise SystemExit(f"APK를 찾지 못했습니다: {apk}")

    with zipfile.ZipFile(apk) as archive:
        crc_failure = archive.testzip()
        assets = sorted(name for name in archive.namelist() if name.startswith("assets/public/"))
        native_assets = sorted(name for name in archive.namelist() if name.startswith("assets/locales/") or name in ("assets/migration.html", "assets/icons.json"))
        native_libraries = []
        required_assets = {name: {"present": name in archive.namelist(),
                                 "sizeBytes": archive.getinfo(name).file_size if name in archive.namelist() else 0}
                           for name in args.require_asset}
        pdfium_notice = (hashlib.sha256(archive.read("assets/pdfium-notices.txt")).hexdigest()
                         if "assets/pdfium-notices.txt" in archive.namelist() else None)
        for name in sorted(archive.namelist()):
            if name.startswith("lib/") and name.endswith(".so"):
                try:
                    data = archive.read(name)
                    native_libraries.append({"path": name, "sha256": hashlib.sha256(data).hexdigest(),
                                             "sizeBytes": len(data), **inspect_elf(data)})
                except ValueError as error:
                    native_libraries.append({"path": name, "error": str(error)})
    guards = {}
    for declaration in args.guarded_native_library:
        name, api = declaration.rsplit("=", 1)
        guards[name] = int(api)
    native_16k = all(
        not item.get("error") and (item.get("bits") != 64 or item.get("supports16KiBLoadAlignment"))
        for item in native_libraries
    )
    native_api_violations = []
    if args.native_min_api_limit is not None:
        for item in native_libraries:
            limit = guards.get(Path(item["path"]).name, args.native_min_api_limit)
            declared = item.get("declaredAndroidApi")
            if declared is None or declared > limit:
                native_api_violations.append({"path": item["path"], "declared": declared, "limit": limit})

    badging = command([str(tool(tools, "aapt2")), "dump", "badging", str(apk)])
    signing = command([str(tool(tools, "apksigner")), "verify", "--verbose", "--print-certs", str(apk)])
    alignment = command([str(tool(tools, "zipalign")), "-c", "-P", "16", "-v", "4", str(apk)])
    package_match = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging.stdout)
    schemes = {
        name: bool(re.search(rf"Verified using {name} scheme .*?: true", signing.stdout, re.I))
        for name in ("v1", "v2", "v3", "v4")
    }
    cert = certificate_digest(signing.stdout)
    minimum = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", badging.stdout, re.M)
    target = re.search(r"^targetSdkVersion:'(\d+)'", badging.stdout, re.M)
    result = {
        "file": str(apk),
        "size_bytes": apk.stat().st_size,
        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "zip_crc": "ok" if crc_failure is None else f"failed: {crc_failure}",
        "zipalign_16k": alignment.returncode == 0,
        "signing_verified": signing.returncode == 0,
        "signature_schemes": schemes,
        "certificate_sha256": cert,
        "package_id": package_match.group(1) if package_match else None,
        "version_code": package_match.group(2) if package_match else None,
        "version_name": package_match.group(3) if package_match else None,
        "min_sdk": int(minimum.group(1)) if minimum else None,
        "target_sdk": int(target.group(1)) if target else None,
        "web_assets": assets,
        "native_assets": native_assets,
        "native_editor": not assets and "assets/migration.html" in native_assets,
        "native_libraries": native_libraries,
        "required_assets": required_assets,
        "native_64bit_elf_16k": native_16k,
        "native_api_limits": {"default": args.native_min_api_limit, "sourceGuards": guards,
                              "violations": native_api_violations},
        "errors": {
            "aapt2": badging.stderr.strip() if badging.returncode else "",
            "apksigner": signing.stderr.strip() if signing.returncode else "",
            "zipalign": alignment.stderr.strip() if alignment.returncode else "",
        },
    }
    mismatches = []
    for key, expected in (("package_id", args.expected_package), ("certificate_sha256", args.expected_certificate),
                          ("min_sdk", args.expected_min_sdk), ("target_sdk", args.expected_target_sdk),
                          ("version_code", args.expected_version_code), ("version_name", args.expected_version_name)):
        if expected is not None and result[key] != (expected.lower() if key == "certificate_sha256" else expected):
            mismatches.append({"field": key, "expected": expected, "observed": result[key]})
    result["identity_mismatches"] = mismatches
    pdfium_mismatches = []
    if args.pdfium_manifest:
        manifest = json.loads(args.pdfium_manifest.read_text(encoding="utf-8"))
        lock = Path(__file__).with_name("pdfium.lock.json")
        if manifest.get("lockSha256") != hashlib.sha256(lock.read_bytes()).hexdigest():
            pdfium_mismatches.append({"field": "lockSha256", "reason": "Prepared SDK does not match the current dependency lock"})
        shipped = {item["path"]: item.get("sha256") for item in native_libraries}
        for item in manifest["abis"]:
            path = "lib/" + item["abi"] + "/libpdfium.so"
            if shipped.get(path) != item["librarySha256"]:
                pdfium_mismatches.append({"path": path, "expected": item["librarySha256"], "observed": shipped.get(path)})
        if pdfium_notice != manifest["noticeSha256"]:
            pdfium_mismatches.append({"path": "assets/pdfium-notices.txt", "expected": manifest["noticeSha256"], "observed": pdfium_notice})
    result["pdfium_identity_mismatches"] = pdfium_mismatches
    print(json.dumps(result, ensure_ascii=False, indent=2))
    ok = (crc_failure is None and badging.returncode == 0 and signing.returncode == 0
          and alignment.returncode == 0 and schemes["v2"] and native_16k and not native_api_violations
          and all(value["sizeBytes"] > 0 for value in required_assets.values()) and not mismatches and not pdfium_mismatches)
    raise SystemExit(0 if ok else 1)


if __name__ == "__main__":
    main()
