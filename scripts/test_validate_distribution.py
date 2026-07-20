#!/usr/bin/env python3
import copy
import json
import tempfile
import unittest
from pathlib import Path

from release_catalog import load_catalog
from test_support import (
    TEST_CERT,
    build_distribution_tree,
    metadata_reader_for,
    signature_reader,
    write_indexes,
)
from validate_distribution import validate_distribution_tree


class DistributionValidationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        self.candidate = root / "candidate"
        self.baseline = root / "baseline"
        self.catalog = load_catalog()
        self.entries = build_distribution_tree(self.candidate, self.catalog)

    def validate(self, **overrides):
        arguments = {
            "aapt": "unused-aapt",
            "apksigner": "unused-apksigner",
            "expected_signing_cert_sha256": TEST_CERT,
            "metadata_reader": metadata_reader_for(self.catalog),
            "signature_reader": signature_reader,
        }
        arguments.update(overrides)
        return validate_distribution_tree(
            str(self.candidate),
            self.catalog,
            **arguments,
        )

    def test_accepts_complete_distribution(self):
        validated = self.validate()

        self.assertEqual(3, len(validated))
        self.assertEqual(9, sum(len(item["sources"]) for item in validated))

    def test_rejects_non_equivalent_minified_index(self):
        compact = json.loads((self.candidate / "index.min.json").read_text(encoding="utf-8"))
        compact.pop()
        (self.candidate / "index.min.json").write_text(json.dumps(compact), encoding="utf-8")

        with self.assertRaisesRegex(ValueError, "not semantically identical"):
            self.validate()

    def test_rejects_missing_referenced_icon(self):
        (self.candidate / "icon" / self.entries[0]["iconName"]).unlink()

        with self.assertRaisesRegex(ValueError, "referenced icon does not exist"):
            self.validate()

    def test_rejects_apk_sha_mismatch(self):
        entries = copy.deepcopy(self.entries)
        entries[0]["sha256"] = "0" * 64
        write_indexes(self.candidate, entries)

        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            self.validate()

    def test_rejects_apk_package_mismatch(self):
        normal = metadata_reader_for(self.catalog)

        def wrong_package(apk_path, aapt):
            metadata = normal(apk_path, aapt)
            if Path(apk_path).name == self.entries[0]["apkName"]:
                metadata["pkg"] += ".wrong"
            return metadata

        with self.assertRaisesRegex(ValueError, "APK/index pkg mismatch"):
            self.validate(metadata_reader=wrong_package)

    def test_rejects_unexpected_signer(self):
        with self.assertRaisesRegex(ValueError, "unexpected signing certificate"):
            self.validate(signature_reader=lambda *_args: "CD" * 32)

    def test_rejects_version_code_rollback_against_main_baseline(self):
        build_distribution_tree(self.baseline, self.catalog, version_code=2)

        with self.assertRaisesRegex(ValueError, "versionCode regression"):
            self.validate(baseline_dir=str(self.baseline))

    def test_rejects_same_version_code_with_changed_apk_sha(self):
        baseline_entries = build_distribution_tree(self.baseline, self.catalog)
        baseline_entries[0]["sha256"] = "f" * 64
        write_indexes(self.baseline, baseline_entries)

        with self.assertRaisesRegex(ValueError, "same versionCode changed SHA-256"):
            self.validate(baseline_dir=str(self.baseline))

    def test_rejects_unauthorized_historical_source_deletion(self):
        baseline_entries = build_distribution_tree(self.baseline, self.catalog)
        baseline_entries[0]["sources"].append({
            "id": "retired.source",
            "name": "Retired",
            "lang": "zh-TW",
            "baseUrl": "https://retired.example",
        })
        write_indexes(self.baseline, baseline_entries)

        with self.assertRaisesRegex(ValueError, "unauthorized Source deletion"):
            self.validate(baseline_dir=str(self.baseline))

    def test_allows_explicitly_authorized_historical_source_deletion(self):
        baseline_entries = build_distribution_tree(self.baseline, self.catalog)
        baseline_entries[0]["sources"].append({
            "id": "retired.source",
            "name": "Retired",
            "lang": "zh-TW",
            "baseUrl": "https://retired.example",
        })
        write_indexes(self.baseline, baseline_entries)
        catalog = copy.deepcopy(self.catalog)
        catalog["authorizedRemovals"]["sources"] = ["retired.source"]

        validated = validate_distribution_tree(
            str(self.candidate),
            catalog,
            aapt="unused-aapt",
            apksigner="unused-apksigner",
            expected_signing_cert_sha256=TEST_CERT,
            baseline_dir=str(self.baseline),
            metadata_reader=metadata_reader_for(self.catalog),
            signature_reader=signature_reader,
        )

        self.assertEqual(3, len(validated))

    def test_rejects_unauthorized_historical_package_deletion(self):
        baseline_entries = build_distribution_tree(self.baseline, self.catalog)
        baseline_entries.append({
            "pkg": "retired.package",
            "name": "Retired",
            "versionCode": 1,
            "versionName": "1.0",
            "lang": "zh-TW",
            "apkName": "retired.apk",
            "iconName": "retired.png",
            "sha256": "0" * 64,
            "sources": [],
        })
        write_indexes(self.baseline, baseline_entries)

        with self.assertRaisesRegex(ValueError, "unauthorized package deletion"):
            self.validate(baseline_dir=str(self.baseline))


if __name__ == "__main__":
    unittest.main()
