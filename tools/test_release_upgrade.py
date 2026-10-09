"""Discriminate preserved data from plausible but lossy upgrade comparisons."""
from copy import deepcopy
from pathlib import Path
import sqlite3
import tempfile
import unittest

from check_release_upgrade import compare, snapshot


def captured(version=1):
    return {"userVersion": version, "assets": {"image.bin": "original-image-sha"}, "tables": {
        "documents": {("doc",): {"id": "doc", "updated": 10, "body": '{"unknown":{"nested":[0,null,true]}}'}},
        "pages": {("page",): {"id": "page", "document_id": "doc", "position": 0, "body": '{"w":1000}'}},
        "objects": {("page", "stroke"): {"id": "stroke", "page_id": "page", "position": 0,
            "body": '{"points":[{"x":1,"p":0,"t":10},{"x":1,"p":null,"t":11}],"unknown":"keep"}'}},
        "settings": {("folders",): {"key": "folders", "body": '[{"id":"a"},{"id":"b"}]'},
                     ("opaque",): {"key": "opaque", "body": "not-json-setting"}},
    }}


class ReleaseUpgradeComparisonTest(unittest.TestCase):
    def setUp(self):
        self.old = captured()
        self.new = deepcopy(self.old)
        self.new["userVersion"] = 2

    def test_additive_fields_preserve_originals(self):
        self.new["tables"]["pages"][("page",)]["body"] = '{"w":1000.0,"generation":1}'
        self.assertEqual(compare(self.old, self.new)["originalAssetsPreserved"], 1)

    def test_nested_unknown_field_loss_rejected(self):
        self.new["tables"]["documents"][("doc",)]["body"] = '{}'
        with self.assertRaises(ValueError):
            compare(self.old, self.new)

    def test_stationary_pressure_zero_and_null_are_distinct(self):
        body = self.new["tables"]["objects"][("page", "stroke")]["body"]
        self.new["tables"]["objects"][("page", "stroke")]["body"] = body.replace('"p":0', '"p":null')
        with self.assertRaises(ValueError):
            compare(self.old, self.new)

    def test_original_array_order_rejected_if_changed(self):
        self.new["tables"]["settings"][("folders",)]["body"] = '[{"id":"b"},{"id":"a"}]'
        with self.assertRaises(ValueError):
            compare(self.old, self.new)

    def test_original_sql_order_and_rows_cannot_disappear(self):
        self.new["tables"]["objects"][("page", "stroke")]["position"] = 1
        with self.assertRaises(ValueError):
            compare(self.old, self.new)
        self.new = captured(2)
        self.new["tables"]["pages"].clear()
        with self.assertRaises(ValueError):
            compare(self.old, self.new)

    def test_asset_change_rejected(self):
        self.new["assets"]["image.bin"] = "wrong-image-sha"
        with self.assertRaises(ValueError):
            compare(self.old, self.new)

    def test_empty_seed_cannot_be_upgrade_proof(self):
        self.old["assets"].clear()
        with self.assertRaises(ValueError):
            compare(self.old, self.new)


class CapturedDatabaseTest(unittest.TestCase):
    def database(self, directory, version):
        path = directory / "databases/badnote-native.db"
        path.parent.mkdir()
        (directory / "native-assets").mkdir()
        connection = sqlite3.connect(path)
        connection.execute("PRAGMA journal_mode=WAL")
        for sql in ("CREATE TABLE documents(id TEXT,body TEXT,updated INTEGER)",
                    "CREATE TABLE pages(id TEXT,document_id TEXT,position INTEGER,body TEXT)",
                    "CREATE TABLE objects(page_id TEXT,id TEXT,position INTEGER,body TEXT)",
                    "CREATE TABLE settings(key TEXT,body TEXT)"):
            connection.execute(sql)
        connection.execute(f"PRAGMA user_version={version}")
        connection.commit()
        return connection

    def test_read_only_snapshot_includes_uncheckpointed_wal(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            connection = self.database(directory, 1)
            try:
                connection.execute('INSERT INTO documents VALUES(?,?,?)', ("doc", '{}', 41))
                connection.commit()
                self.assertTrue((directory / "databases/badnote-native.db-wal").is_file())
                data = snapshot(directory, 1)
                self.assertEqual(data["tables"]["documents"][("doc",)]["updated"], 41)
            finally:
                connection.close()

    def test_empty_room_identity_cannot_prove_upgrade(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            connection = self.database(directory, 2)
            try:
                connection.execute("CREATE TABLE room_master_table(id INTEGER PRIMARY KEY,identity_hash TEXT)")
                connection.execute("INSERT INTO room_master_table VALUES(42,'')")
                connection.commit()
                with self.assertRaises(ValueError):
                    snapshot(directory, 2)
            finally:
                connection.close()


if __name__ == "__main__":
    unittest.main()
