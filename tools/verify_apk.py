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


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--build-tools")
    args = parser.parse_args()
    apk = args.apk.resolve()
    tools = find_build_tools(args.build_tools)
    if not apk.is_file():
        raise SystemExit(f"APK를 찾지 못했습니다: {apk}")

    with zipfile.ZipFile(apk) as archive:
        crc_failure = archive.testzip()
        assets = sorted(name for name in archive.namelist() if name.startswith("assets/public/"))

    badging = command([str(tool(tools, "aapt2")), "dump", "badging", str(apk)])
    signing = command([str(tool(tools, "apksigner")), "verify", "--verbose", "--print-certs", str(apk)])
    alignment = command([str(tool(tools, "zipalign")), "-c", "-P", "16", "-v", "4", str(apk)])
    package_match = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging.stdout)
    schemes = {
        name: bool(re.search(rf"Verified using {name} scheme .*?: true", signing.stdout, re.I))
        for name in ("v1", "v2", "v3", "v4")
    }
    cert = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", signing.stdout, re.I)
    result = {
        "file": str(apk),
        "size_bytes": apk.stat().st_size,
        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "zip_crc": "ok" if crc_failure is None else f"failed: {crc_failure}",
        "zipalign_16k": alignment.returncode == 0,
        "signing_verified": signing.returncode == 0,
        "signature_schemes": schemes,
        "certificate_sha256": cert.group(1).lower() if cert else None,
        "package_id": package_match.group(1) if package_match else None,
        "version_code": package_match.group(2) if package_match else None,
        "version_name": package_match.group(3) if package_match else None,
        "web_assets": assets,
        "errors": {
            "aapt2": badging.stderr.strip() if badging.returncode else "",
            "apksigner": signing.stderr.strip() if signing.returncode else "",
            "zipalign": alignment.stderr.strip() if alignment.returncode else "",
        },
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    ok = crc_failure is None and badging.returncode == 0 and signing.returncode == 0 and alignment.returncode == 0 and schemes["v2"]
    raise SystemExit(0 if ok else 1)


if __name__ == "__main__":
    main()
