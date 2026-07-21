#!/usr/bin/env python3
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from release_catalog import load_catalog, registry_for_release
from test_support import write_complete_apks, write_release_apk
from validate_release_bundles import read_registry, validate_release_bundles


class ReleaseBundleValidationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.apk_dir = Path(self.temporary.name)
        self.catalog = load_catalog()

    def test_accepts_catalog_complete_release(self):
        write_complete_apks(self.apk_dir, self.catalog)

        registries = validate_release_bundles(str(self.apk_dir), self.catalog)

        self.assertEqual(
            {release["module"] for release in self.catalog["releases"]},
            set(registries),
        )
        self.assertEqual(
            sum(len(release["sources"]) for release in self.catalog["releases"]),
            sum(len(registry["sources"]) for registry in registries.values()),
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

    def test_rejects_registry_that_differs_from_catalog(self):
        write_complete_apks(self.apk_dir, self.catalog)
        release = next(item for item in self.catalog["releases"] if item["module"] == "komica")
        registry = registry_for_release(self.catalog, release)
        registry["sources"] = registry["sources"][:-1]
        write_release_apk(self.apk_dir, self.catalog, release, registry=registry)

        with self.assertRaisesRegex(ValueError, "APK registry does not match catalog registry"):
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

    def test_accepts_schema_one_registry_with_api_one(self):
        registry = self._registry(schema_version=1)
        registry.pop("requiredApiVersion", None)

        self.assertEqual(1, self._read_registry(registry)["schemaVersion"])

    def test_accepts_schema_two_registry_with_required_api_two(self):
        registry = self._registry(schema_version=2)
        registry["requiredApiVersion"] = 2

        self.assertEqual(2, self._read_registry(registry)["requiredApiVersion"])

    def test_rejects_future_schema_or_api_version(self):
        with self.assertRaisesRegex(ValueError, "unsupported registry schemaVersion"):
            self._read_registry(self._registry(schema_version=3))

        registry = self._registry(schema_version=2)
        registry["requiredApiVersion"] = 3
        with self.assertRaisesRegex(ValueError, "requires extension API version 2"):
            self._read_registry(registry)

    def test_rejects_schema_two_without_required_api_version_two(self):
        registry = self._registry(schema_version=2)
        registry.pop("requiredApiVersion", None)

        with self.assertRaisesRegex(ValueError, "requires extension API version 2"):
            self._read_registry(registry)

    def _registry(self, schema_version: int) -> dict:
        release = next(item for item in self.catalog["releases"] if item["module"] == "ptt")
        registry = registry_for_release(self.catalog, release)
        registry["schemaVersion"] = schema_version
        return registry

    def _read_registry(self, registry: dict) -> dict:
        apk = self.apk_dir / "registry.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("assets/newshub-extension.json", json.dumps(registry))
        return read_registry(str(apk))


if __name__ == "__main__":
    unittest.main()
