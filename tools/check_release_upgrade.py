#!/usr/bin/env python3
"""Compare private DB/assets captured from an actual signed-release upgrade.

This checks captured data only. Installation, signing and visible app flows need
their own observed receipts; generated migration fixtures are not substitutes.
"""
from __future__ import annotations

import argparse
from contextlib import closing
import hashlib
import json
from pathlib import Path
import re
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
TABLE_KEYS = {"documents": ("id",), "pages": ("id",),
              "objects": ("page_id", "id"), "settings": ("key",)}


def sha256(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def require_preserved(before, after, locator: str) -> None:
    """Permit additive metadata, preserve every original value and array order."""
    if isinstance(before, dict):
        if not isinstance(after, dict) or not before.keys() <= after.keys():
            raise ValueError("Original fields missing: " + locator)
        for key, value in before.items():
            require_preserved(value, after[key], locator + "." + key)
    elif isinstance(before, list):
        if not isinstance(after, list) or len(before) != len(after):
            raise ValueError("Original array length changed: " + locator)
        for index, value in enumerate(before):
            require_preserved(value, after[index], f"{locator}[{index}]")
    elif type(before) is not type(after) or before != after:
        # JSON distinguishes bool, null and numbers. An integer/float conversion
        # with the same finite value is semantically safe for imported metadata.
        numeric = type(before) in (int, float) and type(after) in (int, float) and before == after
        if not numeric:
            raise ValueError("Original value changed: " + locator)


def snapshot(directory: Path, version: int) -> dict:
    database = directory / "databases/badnote-native.db"
    if not database.is_file():
        raise ValueError("Captured installed database is missing")
    with closing(sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)) as connection:
        connection.row_factory = sqlite3.Row
        if connection.execute("PRAGMA quick_check").fetchall()[0][0] != "ok":
            raise ValueError("Captured database integrity failed")
        observed = connection.execute("PRAGMA user_version").fetchone()[0]
        if observed != version:
            raise ValueError(f"Expected installed DB version {version}, observed {observed}")
        names = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if not TABLE_KEYS.keys() <= names:
            raise ValueError("Original installed tables are missing")
        if version == 2:
            identity = (connection.execute("SELECT identity_hash FROM room_master_table WHERE id=42").fetchone()
                        if "room_master_table" in names else None)
            if identity is None or not isinstance(identity[0], str) or not re.fullmatch(r"[0-9a-f]{32}", identity[0]):
                raise ValueError("Updated installed DB has no Room schema identity")
        tables = {}
        for table, keys in TABLE_KEYS.items():
            tables[table] = {}
            for row in connection.execute(f"SELECT * FROM {table}"):
                data = dict(row)
                key = tuple(data[name] for name in keys)
                if any(value is None for value in key) or key in tables[table]:
                    raise ValueError("Release seed has null/duplicate primary key: " + table)
                tables[table][key] = data
    assets = directory / "native-assets"
    if not assets.is_dir():
        raise ValueError("Captured installed assets directory is missing")
    files = {str(path.relative_to(directory)).replace("\\", "/"): sha256(path)
             for path in sorted(directory.rglob("*")) if path.is_file()}
    return {"tables": tables, "files": files,
            "assets": {str(path.relative_to(assets)).replace("\\", "/"): sha256(path)
                       for path in sorted(assets.rglob("*")) if path.is_file()}, "userVersion": observed}


def compare(before: dict, after: dict) -> dict:
    if any(not before["tables"][name] for name in TABLE_KEYS) or not before["assets"]:
        raise ValueError("Real release seed must exercise all original tables and an asset")
    results = {}
    for table, rows in before["tables"].items():
        for key, original in rows.items():
            updated = after["tables"][table].get(key)
            if updated is None:
                raise ValueError("Installed row lost: " + table)
            for name, value in original.items():
                if name not in updated:
                    raise ValueError("Original SQL column lost: " + table + "." + name)
                actual = updated[name]
                if name == "body":
                    try:
                        old_json = json.loads(value)
                    except (ValueError, TypeError):
                        if value != actual:
                            raise ValueError("Original opaque setting lost: " + table)
                    else:
                        require_preserved(old_json, json.loads(actual), table + ".body")
                elif value != actual:
                    raise ValueError("Original SQL value changed: " + table + "." + name)
        results[table] = {"originalRowsPreserved": len(rows), "updatedRows": len(after["tables"][table])}
    for name, digest in before["assets"].items():
        if after["assets"].get(name) != digest:
            raise ValueError("Installed asset lost or changed: " + name)
    return {"tables": results, "originalAssetsPreserved": len(before["assets"]),
            "beforeUserVersion": before["userVersion"], "afterUserVersion": after["userVersion"]}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", required=True, type=Path)
    parser.add_argument("--after", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    paths = [path.resolve() for path in (args.before, args.after, args.out)]
    if not all(path.is_relative_to(ROOT / "build") for path in paths):
        raise ValueError("Captured release evidence must stay beneath repository/build")
    if args.out.exists():
        raise ValueError("Refusing to overwrite an upgrade comparison")
    before, after = snapshot(paths[0], 1), snapshot(paths[1], 2)
    result = {"schemaVersion": 1, "scope": "Captured actual installed data only; requires independent install/UI receipts",
              "before": {"directory": str(paths[0].relative_to(ROOT)), "files": before["files"]},
              "after": {"directory": str(paths[1].relative_to(ROOT)), "files": after["files"]},
              "comparison": compare(before, after), "status": "passed"}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result["comparison"], ensure_ascii=False))


if __name__ == "__main__":
    main()
