#!/usr/bin/env python3
import unittest
from pathlib import Path
from unittest.mock import patch

import release_catalog
from release_catalog import gradle_tasks, load_catalog


class ReleaseCatalogTest(unittest.TestCase):
    def test_catalog_is_complete_and_uses_distinct_png_assets(self):
        catalog = load_catalog()

        self.assertGreater(len(catalog["releases"]), 0)
        self.assertGreater(sum(len(item["sources"]) for item in catalog["releases"]), 0)
        icon_sources = [item["icon"]["source"] for item in catalog["releases"]]
        icon_names = [item["icon"]["name"] for item in catalog["releases"]]
        self.assertEqual(len(catalog["releases"]), len(set(icon_sources)))
        self.assertEqual(len(catalog["releases"]), len(set(icon_names)))

    def test_every_test_and_assemble_task_is_derived_from_catalog(self):
        catalog = load_catalog()
        expected = {
            source["testTask"]
            for release in catalog["releases"]
            for source in release["sources"]
        } | {release["assembleTask"] for release in catalog["releases"]}

        self.assertEqual(expected, set(gradle_tasks(catalog)))

    def test_unregistered_gradle_module_is_rejected(self):
        catalog = load_catalog()
        known = {
            release["module"] for release in catalog["releases"]
        } | {
            source["module"]
            for release in catalog["releases"]
            for source in release["sources"]
        }
        with patch.object(release_catalog, "_settings_modules", return_value=known | {"forgotten"}):
            with self.assertRaisesRegex(ValueError, "settings/catalog Gradle module mismatch"):
                load_catalog()

    def test_cloud_build_publish_derives_release_from_catalog(self):
        config = (Path(__file__).resolve().parents[1] / "cloudbuild/publish.yaml").read_text(
            encoding="utf-8"
        )

        self.assertIn("release_catalog.py gradle-tasks", config)
        self.assertIn("release_catalog.py artifact-rows", config)
        self.assertIn("validate_release_bundles.py", config)
        self.assertIn("--baseline-dir", config)
        self.assertIn("publish_distribution_pr.py", config)
        self.assertIn("github_app_token.py", config)
        self.assertIn("availableSecrets:", config)
        self.assertNotIn("git push origin main", config)
        self.assertNotIn("GLOBAL_SIGNING_CERT", config)


if __name__ == "__main__":
    unittest.main()
