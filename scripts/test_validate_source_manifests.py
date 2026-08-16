#!/usr/bin/env python3
import tempfile
import unittest
from pathlib import Path

from release_catalog import load_catalog, metadata_for_release
from validate_source_manifests import validate_all, validate_manifest


class SourceManifestValidationTest(unittest.TestCase):
    def setUp(self):
        self.catalog = load_catalog()
        self.release = self.catalog["releases"][0]
        metadata_by_id = {
            source["id"]: source
            for source in metadata_for_release(self.catalog, self.release)["sources"]
        }
        self.expected = [
            {**metadata_by_id[source["id"]], **source}
            for source in self.release["sources"]
        ]
        self.manifest = (
            Path(self.catalog["_root"])
            / "src" / self.release["module"] / "src/main/AndroidManifest.xml"
        )

    def test_accepts_all_current_release_manifests(self):
        validate_all(self.catalog)

    def test_rejects_network_permission(self):
        self._tamper(
            "<application",
            '<uses-permission android:name="android.permission.INTERNET" />\n    <application',
            "must declare no permissions",
        )

    def test_rejects_non_isolated_service(self):
        self._tamper('android:isolatedProcess="true"', 'android:isolatedProcess="false"', "isolatedProcess")

    def test_rejects_missing_host_permission(self):
        self._tamper(
            'android:permission="tw.kevinzhang.newshub.permission.BIND_EXTENSION"',
            'android:permission="android.permission.INTERNET"',
            "signature permission",
        )

    def test_rejects_legacy_application_marker(self):
        self._tamper(
            'android:roundIcon="@drawable/ic_launcher">',
            'android:roundIcon="@drawable/ic_launcher">\n'
            '        <meta-data android:name="newshub.extension" android:value="true" />',
            "legacy application metadata",
        )

    def test_rejects_protocol_v1_service(self):
        self._tamper(
            'android:name="newshub.extension.protocol" android:value="2"',
            'android:name="newshub.extension.protocol" android:value="1"',
            "protocol must equal 2",
        )

    def test_rejects_legacy_login_service_metadata(self):
        self._tamper(
            '<meta-data android:name="newshub.extension.source_id"',
            '<meta-data android:name="newshub.extension.needs_login" android:value="true" />\n'
            '            <meta-data android:name="newshub.extension.source_id"',
            "legacy login service metadata",
        )

    def _tamper(self, old: str, new: str, error: str) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "AndroidManifest.xml"
            contents = self.manifest.read_text(encoding="utf-8")
            self.assertIn(old, contents)
            target.write_text(contents.replace(old, new, 1), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, error):
                validate_manifest(target, self.release["package"], self.expected)


if __name__ == "__main__":
    unittest.main()
