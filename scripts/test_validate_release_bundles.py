#!/usr/bin/env python3
import tempfile
import unittest
import zipfile
from pathlib import Path

from release_catalog import load_catalog, metadata_for_release
from test_support import write_complete_apks, write_release_apk
from validate_release_bundles import validate_release_bundles


class ReleaseBundleValidationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.apk_dir = Path(self.temporary.name)
        self.catalog = load_catalog()

    def test_accepts_catalog_complete_release(self):
        write_complete_apks(self.apk_dir, self.catalog)

        metadata = validate_release_bundles(str(self.apk_dir), self.catalog)

        self.assertEqual(
            {release["module"] for release in self.catalog["releases"]},
            set(metadata),
        )
        self.assertEqual(
            sum(len(release["sources"]) for release in self.catalog["releases"]),
            sum(len(item["sources"]) for item in metadata.values()),
        )

    def test_rejects_release_missing_gamer(self):
        for release in self.catalog["releases"]:
            if release["module"] != "gamer":
                write_release_apk(self.apk_dir, self.catalog, release)

        with self.assertRaisesRegex(ValueError, "incomplete release APK set"):
            validate_release_bundles(str(self.apk_dir), self.catalog)

    def test_rejects_unexpected_release_filename(self):
        write_complete_apks(self.apk_dir, self.catalog)
        (self.apk_dir / "newshub-unknown-v1.0.0.apk").write_bytes(b"not-an-apk")

        with self.assertRaisesRegex(ValueError, "unexpected release APK filename"):
            validate_release_bundles(str(self.apk_dir), self.catalog)

    def test_rejects_legacy_registry_asset(self):
        write_complete_apks(self.apk_dir, self.catalog)
        release = next(item for item in self.catalog["releases"] if item["module"] == "komica")
        legacy_registry = metadata_for_release(self.catalog, release)
        write_release_apk(self.apk_dir, self.catalog, release, legacy_registry=legacy_registry)

        with self.assertRaisesRegex(ValueError, "legacy extension registry is forbidden"):
            validate_release_bundles(str(self.apk_dir), self.catalog)

    def test_rejects_missing_source_bytecode(self):
        write_complete_apks(self.apk_dir, self.catalog)
        release = next(item for item in self.catalog["releases"] if item["module"] == "gamer")
        write_release_apk(self.apk_dir, self.catalog, release, include_dex=False)

        with self.assertRaisesRegex(ValueError, "release APK has no classes.dex"):
            validate_release_bundles(str(self.apk_dir), self.catalog)

    def test_rejects_foreign_source_bytecode(self):
        write_complete_apks(self.apk_dir, self.catalog)
        gamer = next(item for item in self.catalog["releases"] if item["module"] == "gamer")
        komica = next(item for item in self.catalog["releases"] if item["module"] == "komica")
        apk = write_release_apk(self.apk_dir, self.catalog, gamer)
        with zipfile.ZipFile(apk, "a") as archive:
            marker = komica["sources"][0]["className"].replace(".", "/")
            archive.writestr("classes2.dex", marker)

        with self.assertRaisesRegex(ValueError, "contains foreign Source class"):
            validate_release_bundles(str(self.apk_dir), self.catalog)

if __name__ == "__main__":
    unittest.main()
