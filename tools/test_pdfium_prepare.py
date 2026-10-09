import io
import pathlib
import tarfile
import tempfile
import unittest

from prepare_pdfium import checked_members, download, prepare


def archive(entries):
    content = io.BytesIO()
    with tarfile.open(fileobj=content, mode="w") as output:
        for name, kind in entries:
            info = tarfile.TarInfo(name)
            if kind == "link":
                info.type = tarfile.SYMTYPE
                info.linkname = "../outside"
                output.addfile(info)
            else:
                info.size = 3
                output.addfile(info, io.BytesIO(b"abc"))
    content.seek(0)
    return tarfile.open(fileobj=content, mode="r")


class PdfiumPreparationTest(unittest.TestCase):
    def test_traversal_and_links_fail_before_extraction(self):
        for name, kind in (("../outside", "file"), ("/absolute", "file"), ("include/link", "link")):
            with archive([(name, kind)]) as source:
                with self.assertRaises(ValueError):
                    checked_members(source)

    def test_only_headers_libraries_and_complete_notices_selected(self):
        with archive([("include/fpdf_text.h", "file"), ("licenses/pdfium.txt", "file"),
                      ("LICENSE", "file"), ("lib/libpdfium.so", "file"),
                      ("arbitrary-program.exe", "file")]) as source:
            selected = [entry.name for entry in checked_members(source)]
        self.assertEqual(len(selected), 4)
        self.assertNotIn("arbitrary-program.exe", selected)

    def test_corrupted_cached_dependency_is_rejected_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "artifact.tgz"
            path.write_bytes(b"unexpected")
            with self.assertRaisesRegex(ValueError, "does not match"):
                download({"filename": path.name, "size": 10, "sha256": "0" * 64}, path, True)
            self.assertEqual(path.read_bytes(), b"unexpected")

    def test_output_cannot_escape_workspace_build(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "below"):
                prepare(pathlib.Path(directory), True)


if __name__ == "__main__":
    unittest.main()
