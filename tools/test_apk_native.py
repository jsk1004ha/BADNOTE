import struct
import unittest

from apk_native import inspect_elf
from verify_apk import certificate_digest


def elf(bits=64, alignment=16384, api=23):
    data = bytearray(1024)
    data[:7] = b"\x7fELF" + bytes((2 if bits == 64 else 1, 1, 1))
    struct.pack_into("<H", data, 18, 183 if bits == 64 else 40)
    if bits == 64:
        struct.pack_into("<Q", data, 32, 64)
        struct.pack_into("<HH", data, 54, 56, 2)
        struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 500, 500, alignment)
        struct.pack_into("<IIQQQQQQ", data, 120, 4, 0, 300, 0, 0, 24, 24, 4)
    else:
        struct.pack_into("<I", data, 28, 64)
        struct.pack_into("<HH", data, 42, 32, 2)
        struct.pack_into("<IIIIIIII", data, 64, 1, 0, 0, 0, 500, 500, 5, alignment)
        struct.pack_into("<IIIIIIII", data, 96, 4, 300, 0, 0, 24, 24, 0, 4)
    struct.pack_into("<III", data, 300, 8, 4, 1)
    data[312:320] = b"Android\0"
    struct.pack_into("<I", data, 320, api)
    return bytes(data)


class NativeMetadataTest(unittest.TestCase):
    def test_min_sdk_manifest_does_not_hide_actual_native_api(self):
        metadata = inspect_elf(elf(api=26))
        self.assertTrue(metadata["supports16KiBLoadAlignment"])
        self.assertEqual(metadata["declaredAndroidApi"], 26)

    def test_four_kib_alignment_fails_for_64_bit_elf(self):
        self.assertFalse(inspect_elf(elf(alignment=4096))["supports16KiBLoadAlignment"])

    def test_32_bit_metadata_is_inspected_separately(self):
        metadata = inspect_elf(elf(bits=32, alignment=4096))
        self.assertEqual(metadata["bits"], 32)
        self.assertEqual(metadata["declaredAndroidApi"], 23)

    def test_truncated_or_untrusted_header_fails(self):
        for data in (b"", b"not a native library", elf()[:120]):
            with self.assertRaises(ValueError):
                inspect_elf(data)


class CertificateOutputTest(unittest.TestCase):
    digest = "45bc9430d2c32457c5d944561176a7ec861b29f6bcb1b4244806307c9f3d0847"

    def test_build_tools_36_and_37_certificate_formats(self):
        for signer in ("Signer #1", "V3.0 Signer:", "V3.2 Signer:"):
            output = f"Number of signers: 1\n{signer} certificate SHA-256 digest: {self.digest}\n"
            self.assertEqual(certificate_digest(output), self.digest)

    def test_multiple_schemes_require_the_same_certificate(self):
        output = f"Number of signers: 1\nV3.0 Signer: certificate SHA-256 digest: {self.digest}\n"
        self.assertEqual(certificate_digest(output + output.splitlines()[1] + "\n"), self.digest)
        self.assertIsNone(certificate_digest(output + "V3.2 Signer: certificate SHA-256 digest: " + "a" * 64 + "\n"))

    def test_public_key_malformed_digest_and_multiple_signers_are_rejected(self):
        for detail in (f"Signer #1 public key SHA-256 digest: {self.digest}",
                       "Signer #1 certificate SHA-256 digest: abc"):
            self.assertIsNone(certificate_digest("Number of signers: 1\n" + detail + "\n"))
        self.assertIsNone(certificate_digest(f"Number of signers: 2\nSigner #1 certificate SHA-256 digest: {self.digest}\n"))


if __name__ == "__main__":
    unittest.main()
