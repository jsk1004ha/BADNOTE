#!/usr/bin/env python3
"""Fetch pinned PDFium SDK archives and validate hashes/API/ELF before building.

Generated headers/libraries/notices stay under the repository's ignored build
directory. No downloaded code is executed. Python standard library only.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import shutil
import tarfile
import urllib.request

from apk_native import inspect_elf

ROOT = pathlib.Path(__file__).resolve().parents[1]
LOCK = pathlib.Path(__file__).with_name("pdfium.lock.json")
MAX_EXTRACTED = 80 * 1024 * 1024


def checked_members(archive: tarfile.TarFile) -> list[tarfile.TarInfo]:
    accepted, total = [], 0
    for member in archive.getmembers():
        path = pathlib.PurePosixPath(member.name)
        if path.is_absolute() or ".." in path.parts or "\\" in member.name:
            raise ValueError("Unsafe PDFium archive path")
        if member.issym() or member.islnk():
            raise ValueError("PDFium archive links are not allowed")
        if not member.isfile():
            continue
        name = str(path).removeprefix("./")
        selected = (
            name.startswith(("include/", "lib/", "licenses/"))
            or path.name.upper().startswith(("LICENSE", "LICENCE", "NOTICE"))
        )
        if selected:
            total += member.size
            if member.size < 0 or total > MAX_EXTRACTED:
                raise ValueError("PDFium SDK archive exceeds extraction budget")
            accepted.append(member)
    if not accepted:
        raise ValueError("PDFium SDK archive has no accepted files")
    return accepted


def digest(path: pathlib.Path) -> str:
    hash_value = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(65536):
            hash_value.update(chunk)
    return hash_value.hexdigest()


def download(spec: dict, path: pathlib.Path, offline: bool) -> None:
    if not path.is_file():
        if offline:
            raise ValueError("Pinned PDFium archive not cached: " + spec["filename"])
        if not spec["url"].startswith("https://github.com/bblanchon/pdfium-binaries/releases/download/"):
            raise ValueError("Unexpected vendor origin")
        partial = path.with_suffix(path.suffix + ".partial")
        request = urllib.request.Request(spec["url"], headers={"User-Agent": "BADNOTE-build/1"})
        with urllib.request.urlopen(request, timeout=60) as source, partial.open("wb") as output:
            size = 0
            while chunk := source.read(65536):
                size += len(chunk)
                if size > spec["size"]:
                    raise ValueError("PDFium archive exceeds its pinned size")
                output.write(chunk)
        if partial.stat().st_size != spec["size"] or digest(partial) != spec["sha256"]:
            raise ValueError("Downloaded PDFium archive hash/size does not match lock")
        partial.replace(path)
    if path.stat().st_size != spec["size"] or digest(path) != spec["sha256"]:
        raise ValueError("Cached PDFium archive hash/size does not match lock")


def prepare(output: pathlib.Path, offline: bool = False) -> dict:
    output = output.resolve()
    # Never recursively remove/move an unchecked computed target.
    build = (ROOT / "build").resolve()
    if output == build or build not in output.parents:
        raise ValueError("PDFium output must stay below this repository's build directory")
    output.mkdir(parents=True, exist_ok=True)
    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    result = {
        "schemaVersion": 1, "release": lock["release"], "pdfiumVersion": lock["pdfiumVersion"],
        "lockSha256": digest(LOCK), "abis": [],
    }
    notices: dict[str, str] = {}
    notice_encodings: dict[str, str] = {}
    cache = output / "downloads"
    cache.mkdir(exist_ok=True)
    for spec in lock["archives"]:
        archive_path = cache / spec["filename"]
        download(spec, archive_path, offline)
        target = output / spec["abi"]
        target.mkdir(exist_ok=True)
        with tarfile.open(archive_path, "r:gz") as archive:
            members = checked_members(archive)
            for member in members:
                name = pathlib.PurePosixPath(member.name)
                destination = target.joinpath(*name.parts)
                if target.resolve() not in destination.resolve().parents:
                    raise ValueError("Archive destination escapes ABI directory")
                destination.parent.mkdir(parents=True, exist_ok=True)
                source = archive.extractfile(member)
                if source is None:
                    raise ValueError("Cannot read archive entry")
                # Validate the source archive first, then replace an individual
                # generated file. There is no recursive deletion or moving.
                partial = destination.with_name(destination.name + ".partial")
                with source, partial.open("wb") as stream:
                    shutil.copyfileobj(source, stream, length=65536)
                partial.replace(destination)
                if str(name).startswith("licenses/") or name.name.upper().startswith(("LICENSE", "LICENCE", "NOTICE")):
                    notice_bytes = destination.read_bytes()
                    try:
                        text = notice_bytes.decode("utf-8")
                        encoding = "utf-8"
                    except UnicodeDecodeError:
                        # Upstream freetype's copyright notice is Latin-1.
                        # Retain original bytes in the extracted SDK and record
                        # the reversible transcoding instead of dropping bytes.
                        text = notice_bytes.decode("latin-1")
                        encoding = "latin-1"
                    if str(name) in notices and notices[str(name)] != text:
                        raise ValueError("PDFium notices differ across ABIs")
                    notices[str(name)] = text
                    notice_encodings[str(name)] = encoding
        core = target / "lib/libpdfium.so"
        if not core.is_file() or not (target / "include/fpdf_text.h").is_file():
            raise ValueError("PDFium SDK is missing its library or text headers")
        metadata = inspect_elf(core.read_bytes())
        if metadata["declaredAndroidApi"] is None or metadata["declaredAndroidApi"] > lock["minAndroidApi"]:
            raise ValueError("PDFium native core exceeds declared app minimum API")
        if metadata["bits"] == 64 and not metadata["supports16KiBLoadAlignment"]:
            raise ValueError("PDFium 64-bit core is not aligned for 16 KiB pages")
        result["abis"].append({
            "abi": spec["abi"], "archiveSha256": spec["sha256"], "librarySha256": digest(core),
            **metadata,
        })
        print(json.dumps({"abi": spec["abi"], "api": metadata["declaredAndroidApi"], "elf16k": metadata["supports16KiBLoadAlignment"]}), flush=True)
    if not notices:
        raise ValueError("PDFium license notices are missing")
    notice_path = output / "pdfium-notices.txt"
    notice_path.write_text(
        "PDFium " + lock["pdfiumVersion"] + "\n" + lock["releaseUrl"] + "\n\n" +
        "\n\n".join("===== " + name + " =====\n" + body for name, body in sorted(notices.items())),
        encoding="utf-8",
    )
    result["noticeFiles"] = sorted(notices)
    result["noticeEncodings"] = notice_encodings
    result["noticeSha256"] = digest(notice_path)
    (output / "verification.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, default=ROOT / "build/dependencies/pdfium-8086")
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    result = prepare(args.output, args.offline)
    print(json.dumps({"release": result["release"], "verifiedAbis": len(result["abis"]), "noticeFiles": len(result["noticeFiles"])}))


if __name__ == "__main__":
    main()
