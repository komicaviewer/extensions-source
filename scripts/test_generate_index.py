#!/usr/bin/env python3
import json
import os
import tempfile
import unittest
import zipfile

from generate_index import read_registry


class RegistryTest(unittest.TestCase):
    def write_apk(self, registry):
        temp = tempfile.NamedTemporaryFile(suffix=".apk", delete=False)
        temp.close()
        with zipfile.ZipFile(temp.name, "w") as apk:
            apk.writestr("assets/newshub-extension.json", json.dumps(registry))
        self.addCleanup(lambda: os.remove(temp.name))
        return temp.name

    def test_reads_multi_source_registry(self):
        registry = {
            "schemaVersion": 1,
            "name": "NewsHub: Komica",
            "sources": [
                {
                    "className": "example.TwocatSource",
                    "id": "example.twocat",
                    "name": "Twocat",
                    "lang": "zh-TW",
                    "baseUrl": "https://example.com",
                },
                {
                    "className": "example.SoraSource",
                    "id": "example.sora",
                    "name": "Sora",
                    "lang": "zh-TW",
                    "baseUrl": "https://example.org",
                },
            ],
        }
        self.assertEqual(registry, read_registry(self.write_apk(registry)))

    def test_rejects_duplicate_source_ids(self):
        source = {
            "className": "example.Source",
            "id": "example.source",
            "name": "Source",
            "lang": "zh-TW",
            "baseUrl": "https://example.com",
        }
        registry = {
            "schemaVersion": 1,
            "name": "Duplicate",
            "sources": [source, dict(source, className="example.OtherSource")],
        }
        with self.assertRaisesRegex(ValueError, "duplicate source id"):
            read_registry(self.write_apk(registry))

    def test_rejects_missing_registry_asset(self):
        temp = tempfile.NamedTemporaryFile(suffix=".apk", delete=False)
        temp.close()
        with zipfile.ZipFile(temp.name, "w") as apk:
            apk.writestr("placeholder", "")
        self.addCleanup(lambda: os.remove(temp.name))

        with self.assertRaisesRegex(ValueError, "missing assets/newshub-extension.json"):
            read_registry(temp.name)


if __name__ == "__main__":
    unittest.main()
