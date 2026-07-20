#!/usr/bin/env python3
import json
import os
import tempfile
import unittest
import zipfile

from validate_release_bundles import EXPECTED_RELEASES, validate_release_bundles


def registry_for(module):
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


class ReleaseBundleValidationTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)

    def write_apk(self, module, registry=None, apk_name=None):
        path = os.path.join(
            self.temp_dir.name,
            apk_name or f"newshub-{module}-v1.0.0.apk",
        )
        with zipfile.ZipFile(path, "w") as apk:
            apk.writestr(
                "assets/newshub-extension.json",
                json.dumps(registry or registry_for(module)),
            )
        return path

    def write_complete_release(self):
        for module in EXPECTED_RELEASES:
            self.write_apk(module)

    def test_accepts_exact_three_apk_nine_source_release(self):
        self.write_complete_release()

        registries = validate_release_bundles(self.temp_dir.name)

        self.assertEqual(set(EXPECTED_RELEASES), set(registries))
        self.assertEqual(9, sum(len(registry["sources"]) for registry in registries.values()))

    def test_rejects_release_missing_gamer(self):
        self.write_apk("komica")
        self.write_apk("komica2")

        with self.assertRaisesRegex(ValueError, "incomplete release APK set"):
            validate_release_bundles(self.temp_dir.name)

    def test_rejects_unexpected_release_module(self):
        self.write_complete_release()
        self.write_apk("gamer", apk_name="newshub-akraft-v1.0.0.apk")

        with self.assertRaisesRegex(ValueError, "unexpected release APK module: akraft"):
            validate_release_bundles(self.temp_dir.name)

    def test_rejects_incomplete_komica_source_set(self):
        self.write_complete_release()
        registry = registry_for("komica")
        registry["sources"].pop()
        self.write_apk("komica", registry=registry)

        with self.assertRaisesRegex(ValueError, "unexpected sources for komica"):
            validate_release_bundles(self.temp_dir.name)

    def test_rejects_duplicate_apk_for_same_module(self):
        self.write_complete_release()
        self.write_apk("gamer", apk_name="newshub-gamer-v2.0.0.apk")

        with self.assertRaisesRegex(ValueError, "duplicate release APK module: gamer"):
            validate_release_bundles(self.temp_dir.name)


if __name__ == "__main__":
    unittest.main()
