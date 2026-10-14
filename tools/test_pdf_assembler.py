#!/usr/bin/env python3
"""Compile the production PDF assembler and exercise it in a separate 96 MiB JVM."""
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    with tempfile.TemporaryDirectory(prefix="badnote-vector-pdf-") as directory:
        work = Path(directory)
        classes = work / "classes"
        java = shutil.which("java")
        if java is None:
            raise SystemExit("Java 17 or later is required")
        compiler = [shutil.which("javac")] if shutil.which("javac") else [java, "-m", "jdk.compiler/com.sun.tools.javac.Main"]
        subprocess.run([*compiler, "-encoding", "UTF-8", "-d", str(classes),
                        str(ROOT / "android/app/src/main/java/com/inkforge/notesstudio/PdfPageAssembler.java"),
                        str(ROOT / "tools/java/PdfPageAssemblerChecks.java")], check=True)
        subprocess.run([java, "-Xmx96m", "-cp", str(classes),
                        "com.inkforge.notesstudio.PdfPageAssemblerChecks", str(work / "fixtures")], check=True)
        # Independent PDF parser checks reference remapping, page order, fonts and stream lengths.
        from pypdf import PdfReader
        reader = PdfReader(work / "fixtures/two-pages.pdf", strict=True)
        assert len(reader.pages) == 2
        for index, page in enumerate(reader.pages):
            assert tuple(page.mediabox) == ((0, 0, 240, 320) if index == 0 else (0, 0, 320, 240))
            assert page["/Resources"]["/Font"]["/F1"]["/BaseFont"] == "/Helvetica"
            assert f"Page {index + 1} keeps 3 0 R" in page.extract_text()
            assert b"% preserve stream reference 3 0 R" in page.get_contents().get_data()
            assert page["/Parent"]["/Count"] == 2
            assert page["/Opaque"].get_data() == b""
        print("PDF_PARSE two ordered pages, dimensions, font references, strings and streams verified")


if __name__ == "__main__":
    main()
