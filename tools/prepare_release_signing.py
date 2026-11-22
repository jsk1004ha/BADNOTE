#!/usr/bin/env python3
"""Import a configured BADNOTE release identity without exposing its credentials."""
from __future__ import annotations

import base64
import hashlib
import os
from pathlib import Path
import subprocess

EXPECTED_CERTIFICATE = "45bc9430d2c32457c5d944561176a7ec861b29f6bcb1b4244806307c9f3d0847"
ROOT = Path(__file__).resolve().parents[1]


def output(ready: bool) -> None:
    with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as stream:
        stream.write(f"ready={str(ready).lower()}\n")


def property_value(value: str) -> str:
    return (value.replace("\\", "\\\\").replace("\n", "\\n")
            .replace("\r", "\\r").replace("\t", "\\t").replace(" ", "\\ "))


def main() -> None:
    names = ("BADNOTE_KEYSTORE_BASE64", "BADNOTE_KEYSTORE_PASSWORD",
             "BADNOTE_KEY_ALIAS", "BADNOTE_KEY_PASSWORD")
    present = [bool(os.environ.get(name)) for name in names]
    if not any(present):
        output(False)
        print("::notice::Existing BADNOTE release signing identity is not configured; unsigned APKs stay in Actions artifacts.")
        return
    if not all(present):
        raise SystemExit("Release signing configuration is incomplete.")
    directory = Path(os.environ["RUNNER_TEMP"]) / "badnote-release-signing"
    directory.mkdir(mode=0o700, exist_ok=True)
    keystore = directory / "release.jks"
    try:
        data = base64.b64decode(os.environ[names[0]], validate=True)
    except ValueError:
        raise SystemExit("Release keystore encoding is invalid.") from None
    with keystore.open("wb") as stream:
        os.chmod(keystore, 0o600)
        stream.write(data)
    command = ["keytool", "-exportcert", "-keystore", str(keystore),
               "-alias", os.environ[names[2]], "-storepass:env", names[1]]
    certificate = subprocess.run(command, capture_output=True)
    if certificate.returncode:
        raise SystemExit("Could not read the configured release certificate.")
    if hashlib.sha256(certificate.stdout).hexdigest() != EXPECTED_CERTIFICATE:
        raise SystemExit("The configured signer does not match BADNOTE 4.1.1-beta.1; update APK publication is blocked.")
    values = {"storeFile": str(keystore), "storePassword": os.environ[names[1]],
              "keyAlias": os.environ[names[2]], "keyPassword": os.environ[names[3]]}
    path = ROOT / "android/local-signing.properties"
    with path.open("w", encoding="utf-8") as stream:
        os.chmod(path, 0o600)
        for key, value in values.items():
            stream.write(f"{key}={property_value(value)}\n")
    output(True)
    print("Existing BADNOTE release certificate matched.")


if __name__ == "__main__":
    main()
