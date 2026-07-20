#!/usr/bin/env python3
import json
import os
import tempfile
import unittest
import zipfile
from unittest.mock import patch

import generate_index as generator
from generate_index import EXPECTED_RELEASES, generate_index, read_registry


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


def release_registry(module):
    expected = EXPECTED_RELEASES[module]
    return {
        "schemaVersion": 1,
        "name": expected["name"],
        "sources": [
            {
                "className": f"example.{index}.Source",
                "id": source_id,
                "name": source_id,
                "lang": "zh-TW",
                "baseUrl": f"https://{index}.example",
            }
            for index, source_id in enumerate(sorted(expected["sources"]))
        ],
    }


def write_release_apk(apk_dir, module):
    apk_name = f"newshub-{module}-v1.0.0.apk"
    apk_path = os.path.join(apk_dir, apk_name)
    with zipfile.ZipFile(apk_path, "w") as apk:
        apk.writestr("assets/newshub-extension.json", json.dumps(release_registry(module)))
    return apk_path


class GenerateIndexTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.apk_dir = os.path.join(self.temp_dir.name, "input")
        self.output_dir = os.path.join(self.temp_dir.name, "output")
        os.makedirs(self.apk_dir)
        os.makedirs(os.path.join(self.output_dir, "apk"))

    def write_existing_output(self):
        old_apk = os.path.join(self.output_dir, "apk", "existing-gamer.apk")
        with open(old_apk, "wb") as file:
            file.write(b"existing")
        index_path = os.path.join(self.output_dir, "index.json")
        with open(index_path, "w", encoding="utf-8") as file:
            file.write("existing-index")
        return old_apk, index_path

    def write_complete_input(self):
        for module in EXPECTED_RELEASES:
            write_release_apk(self.apk_dir, module)

    def test_incomplete_input_does_not_touch_existing_output(self):
        old_apk, index_path = self.write_existing_output()
        write_release_apk(self.apk_dir, "komica")
        write_release_apk(self.apk_dir, "komica2")

        with self.assertRaisesRegex(ValueError, "incomplete release APK set"):
            generate_index(self.apk_dir, self.output_dir, "unused-aapt")

        self.assertTrue(os.path.exists(old_apk))
        with open(index_path, encoding="utf-8") as file:
            self.assertEqual("existing-index", file.read())

    def test_package_mismatch_does_not_touch_existing_output(self):
        old_apk, index_path = self.write_existing_output()
        self.write_complete_input()

        def fake_parse(apk_path, _aapt):
            module = generator.module_from_apk_name(os.path.basename(apk_path))
            package = EXPECTED_RELEASES[module]["package"]
            if module == "gamer":
                package += ".wrong"
            return {"pkg": package, "versionCode": 1, "versionName": "1.0.0"}

        with patch.object(generator, "parse_apk", side_effect=fake_parse):
            with self.assertRaisesRegex(ValueError, "unexpected package for gamer"):
                generate_index(self.apk_dir, self.output_dir, "unused-aapt")

        self.assertTrue(os.path.exists(old_apk))
        with open(index_path, encoding="utf-8") as file:
            self.assertEqual("existing-index", file.read())

    def test_complete_input_replaces_output_with_three_packages_and_nine_sources(self):
        self.write_existing_output()
        self.write_complete_input()

        def fake_parse(apk_path, _aapt):
            module = generator.module_from_apk_name(os.path.basename(apk_path))
            return {
                "pkg": EXPECTED_RELEASES[module]["package"],
                "versionCode": 1,
                "versionName": "1.0.0",
            }

        with patch.object(generator, "parse_apk", side_effect=fake_parse):
            extensions = generate_index(self.apk_dir, self.output_dir, "unused-aapt")

        self.assertEqual(3, len(extensions))
        self.assertEqual(9, sum(len(extension["sources"]) for extension in extensions))
        self.assertEqual(
            {f"newshub-{module}-v1.0.0.apk" for module in EXPECTED_RELEASES},
            set(os.listdir(os.path.join(self.output_dir, "apk"))),
        )
        with open(os.path.join(self.output_dir, "index.json"), encoding="utf-8") as file:
            index = json.load(file)
        self.assertEqual(extensions, index)


if __name__ == "__main__":
    unittest.main()
