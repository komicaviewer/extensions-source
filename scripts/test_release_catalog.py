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

    def test_workflow_builds_from_catalog_and_opens_candidate_pr(self):
        workflow = (
            Path(__file__).resolve().parents[1] / ".github/workflows/build_push.yml"
        ).read_text(encoding="utf-8")

        self.assertIn("release_catalog.py gradle-tasks", workflow)
        self.assertIn("release_catalog.py artifact-rows", workflow)
        self.assertIn("gh pr create", workflow)
        self.assertIn("gh pr merge", workflow)
        self.assertIn("--baseline-dir", workflow)
        self.assertIn("retention-days: 14", workflow)
        self.assertIn("GITHUB_RUN_ATTEMPT", workflow)
        self.assertIn("git status --porcelain", workflow)
        self.assertNotIn("schedule:", workflow)
        self.assertNotIn("git push origin main", workflow)
        self.assertNotIn("for module in gamer komica komica2", workflow)


if __name__ == "__main__":
    unittest.main()
