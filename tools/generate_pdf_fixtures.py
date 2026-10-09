#!/usr/bin/env python3
"""Generate synthetic PDF text/lifecycle fixtures using installed PyMuPDF/pypdf."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import math
import random
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def payload_pdf(path: Path, mib: int) -> int:
    """Write real image/text pages in 64 KiB chunks, without a whole-file buffer.

    Stream lengths and classic xref offsets follow ISO 32000-1 sections 7.3.8/7.5:
    https://developer.adobe.com/document-services/docs/assets/35e4369068f86065372c18787171a17e/PDF_ISO_32000-1.pdf
    """
    width, height = 1024, 512
    image_bytes = width * height * 3
    pages = math.ceil(mib * 1024 * 1024 / image_bytes)
    offsets = [0] * (4 + pages * 3)
    noise = random.Random(8086 + mib)
    with path.open("xb") as stream:
        stream.write(b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\n")

        def obj(number: int, body: bytes) -> None:
            offsets[number] = stream.tell()
            stream.write(f"{number} 0 obj\n".encode() + body + b"\nendobj\n")

        obj(1, b"<< /Type /Catalog /Pages 2 0 R >>")
        kids = " ".join(f"{4 + index * 3} 0 R" for index in range(pages))
        obj(2, f"<< /Type /Pages /Count {pages} /Kids [{kids}] >>".encode())
        obj(3, b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        for index in range(pages):
            page, content, image = 4 + index * 3, 5 + index * 3, 6 + index * 3
            obj(page, (f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                       f"/Resources << /Font << /F1 3 0 R >> /XObject << /Im0 {image} 0 R >> >> "
                       f"/Contents {content} 0 R >>").encode())
            commands = (f"BT /F1 18 Tf 48 735 Td (BADNOTE {mib} MiB streaming original page {index + 1}) Tj ET\n"
                        "q 512 0 0 256 48 400 cm /Im0 Do Q\n").encode()
            obj(content, f"<< /Length {len(commands)} >>\nstream\n".encode() + commands + b"endstream")
            offsets[image] = stream.tell()
            stream.write((f"{image} 0 obj\n<< /Type /XObject /Subtype /Image /Width {width} /Height {height} "
                          f"/ColorSpace /DeviceRGB /BitsPerComponent 8 /Length {image_bytes} >>\nstream\n").encode())
            for _ in range(image_bytes // (64 * 1024)):
                stream.write(noise.randbytes(64 * 1024))
            stream.write(b"\nendstream\nendobj\n")
        xref = stream.tell()
        stream.write(f"xref\n0 {len(offsets)}\n0000000000 65535 f \n".encode())
        for offset in offsets[1:]:
            stream.write(f"{offset:010d} 00000 n \n".encode())
        stream.write(f"trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
    return pages


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--font", type=Path, help="Installed font supporting Hangul; embed a subset")
    parser.add_argument("--output", type=Path, required=True, help="New generated directory beneath repository/build")
    parser.add_argument("--large-pages", type=int, default=500)
    parser.add_argument("--stress-mib", type=int, nargs="*", default=[], choices=(100, 300),
                        help="Also stream synthetic 100/300 MiB image/text PDFs")
    args = parser.parse_args()
    if not 1 <= args.large_pages <= 2000:
        raise ValueError("large-pages must be 1..2000")
    output = args.output.resolve()
    if not output.is_relative_to(ROOT / "build") or output == ROOT / "build":
        raise ValueError("Write fixtures beneath repository/build")
    if output.exists() and any(output.iterdir()):
        raise ValueError("Use a new directory; existing fixtures are preserved")
    font = args.font or (Path(os.environ["WINDIR"]) / "Fonts/malgun.ttf" if os.environ.get("WINDIR") else None)
    if font is None or not font.is_file():
        raise ValueError("Supply --font for an installed Hangul-capable font")
    import pymupdf as fitz
    import pypdf

    output.mkdir(parents=True, exist_ok=True)
    normal = output / "original-text-rotated-blank.pdf"
    large_path = output / f"original-{args.large_pages}-pages.pdf"
    with fitz.open() as document:
        page = document.new_page(width=612, height=792)
        page.insert_text((48, 60), "BADNOTE original text page one", fontsize=20)
        page.insert_text((48, 96), "Left column alpha", fontsize=16)
        page.insert_text((328, 96), "Right column beta", fontsize=16)
        page.insert_font(fontname="fixture-ko", fontfile=str(font))
        page.insert_text((48, 150), "한글 원문 검색 가나다", fontname="fixture-ko", fontsize=18)
        page.draw_rect(fitz.Rect(48, 180, 564, 250), color=(.1, .4, .6), width=2)
        page.insert_text((60, 215), "Vector page; text is not OCR", fontsize=16)
        page = document.new_page(width=612, height=792)
        page.insert_text((90, 110), "Rotated cropped original PDF", fontsize=18)
        page.set_cropbox(fitz.Rect(72, 72, 540, 720))
        page.set_rotation(90)
        document.new_page(width=300, height=400)
        document.subset_fonts()
        document.save(normal, garbage=4, deflate=True)
        document.save(output / "password-protected.pdf", encryption=fitz.PDF_ENCRYPT_AES_256,
                      owner_pw="test-owner-only", user_pw="test-password", permissions=fitz.PDF_PERM_ACCESSIBILITY)
    with fitz.open() as large:
        for number in range(args.large_pages):
            page = large.new_page(width=612, height=792)
            page.insert_text((48, 60), f"BADNOTE large PDF original page {number + 1}", fontsize=18)
        large.save(large_path, garbage=4, deflate=True)
    (output / "malformed.pdf").write_bytes(b"%PDF-1.7\nnot-a-complete-cross-reference\n")
    with fitz.open(normal) as rendered:
        extracted = [page.get_text() for page in rendered]
        assert "한글 원문 검색 가나다" in extracted[0]
        assert "Rotated cropped original PDF" in extracted[1]
        assert not extracted[2].strip()
        for index, page in enumerate(rendered):
            page.get_pixmap(matrix=fitz.Matrix(1, 1)).save(output / f"preview-{index + 1}.png")
    independent = [page.extract_text() for page in pypdf.PdfReader(normal).pages]
    assert "한글 원문 검색 가나다" in independent[0]
    assert "Rotated cropped original PDF" in independent[1]
    assert not independent[2].strip()
    large_reader = pypdf.PdfReader(large_path)
    assert len(large_reader.pages) == args.large_pages
    for index, page in enumerate(large_reader.pages):
        assert f"BADNOTE large PDF original page {index + 1}" in page.extract_text()
    encrypted = pypdf.PdfReader(output / "password-protected.pdf")
    assert encrypted.is_encrypted and encrypted.decrypt("test-password")
    stress = []
    for mib in sorted(set(args.stress_mib)):
        path = output / f"original-{mib}MiB.pdf"
        count = payload_pdf(path, mib)
        with path.open("rb") as source:
            checked = pypdf.PdfReader(source)
            assert len(checked.pages) == count
            for index, page in enumerate(checked.pages):
                assert f"BADNOTE {mib} MiB streaming original page {index + 1}" in page.extract_text()
                assert len(page["/Resources"]["/XObject"]) == 1
        with fitz.open(path) as rendered:
            assert len(rendered) == count
            for index in (0, count - 1):
                page = rendered[index]
                assert f"BADNOTE {mib} MiB streaming original page {index + 1}" in page.get_text()
                assert len(page.get_images()) == 1
                page.get_pixmap(matrix=fitz.Matrix(.5, .5)).save(output / f"stress-{mib}MiB-{index + 1}.png")
        stress.append({"file": path.name, "requestedMiB": mib, "pages": count,
                       "imageStreamBytesPerPage": 1024 * 512 * 3, "allPagesTextVerified": True})
    files = {path.name: {"size": path.stat().st_size, "sha256": sha(path)} for path in sorted(output.glob("*.pdf"))}
    manifest = {"schemaVersion": 1, "provenance": {"kind": "synthetic", "purpose": "PDF original-text and lifecycle checks"},
                "generator": {"library": "PyMuPDF", "version": fitz.VersionBind, "fontSha256": sha(font)},
                "independentParser": {"library": "pypdf", "version": pypdf.__version__, "allLargePagesTextVerified": True},
                "files": files, "expectedOriginalText": extracted, "largePageCount": args.large_pages,
                "encryptedUserPassword": "test-password", "stress": stress, "physicalAcceptance": "unmeasured"}
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"files": len(files), "largePages": args.large_pages, "output": str(output)}))


if __name__ == "__main__":
    main()
