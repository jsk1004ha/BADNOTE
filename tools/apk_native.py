"""Inspect ELF load alignment and declared Android API without running binaries."""
from __future__ import annotations

import struct


def inspect_elf(data: bytes) -> dict:
    if len(data) < 64 or data[:4] != b"\x7fELF":
        raise ValueError("Not an ELF binary")
    bits = {1: 32, 2: 64}.get(data[4])
    endian = {1: "<", 2: ">"}.get(data[5])
    if bits is None or endian is None:
        raise ValueError("Unsupported ELF encoding")
    def unpack(fmt: str, offset: int):
        size = struct.calcsize(endian + fmt)
        if offset < 0 or offset + size > len(data):
            raise ValueError("Truncated ELF metadata")
        return struct.unpack_from(endian + fmt, data, offset)
    if bits == 64:
        phoff = unpack("Q", 32)[0]
        phentsize, phnum = unpack("HH", 54)
        required_size = 56
    else:
        phoff = unpack("I", 28)[0]
        phentsize, phnum = unpack("HH", 42)
        required_size = 32
    if phnum > 512 or not required_size <= phentsize <= 128:
        raise ValueError("Invalid ELF program headers")
    loads, android_apis = [], []
    for index in range(phnum):
        offset = phoff + index * phentsize
        kind = unpack("I", offset)[0]
        if bits == 64:
            file_offset = unpack("Q", offset + 8)[0]
            file_size = unpack("Q", offset + 32)[0]
            alignment = unpack("Q", offset + 48)[0]
        else:
            file_offset = unpack("I", offset + 4)[0]
            file_size = unpack("I", offset + 16)[0]
            alignment = unpack("I", offset + 28)[0]
        if kind == 1:  # PT_LOAD
            loads.append(alignment)
        if kind != 4:  # PT_NOTE
            continue
        if file_offset + file_size > len(data) or file_size > 1024 * 1024:
            raise ValueError("Invalid ELF note segment")
        cursor, end = file_offset, file_offset + file_size
        while cursor + 12 <= end:
            name_size, desc_size, note_type = unpack("III", cursor)
            cursor += 12
            if cursor + name_size > end:
                raise ValueError("Truncated ELF note name")
            name = data[cursor:cursor + name_size].rstrip(b"\0")
            cursor += (name_size + 3) & ~3
            if cursor + desc_size > end:
                raise ValueError("Truncated ELF note descriptor")
            if name == b"Android" and note_type == 1 and desc_size >= 4:
                android_apis.append(unpack("I", cursor)[0])
            cursor += (desc_size + 3) & ~3
    return {
        "bits": bits, "machine": unpack("H", 18)[0],
        "loadAlignments": loads,
        "supports16KiBLoadAlignment": bool(loads) and all(value >= 16384 for value in loads),
        # This is declared build metadata, not proof of compatibility on a device.
        "declaredAndroidApi": max(android_apis) if android_apis else None,
    }
