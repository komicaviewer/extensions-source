#!/usr/bin/env python3
import unittest
import copy
import hashlib
import json
from pathlib import Path
import re
import tempfile
from unittest.mock import patch

import release_catalog
from release_catalog import gradle_tasks, load_catalog


class ReleaseCatalogTest(unittest.TestCase):
    def test_all_extension_api_dependencies_use_exact_isolated_service_protocol_pin(self):
        root = Path(__file__).resolve().parents[1]
        expected_sha = "6a94c4879ebbf052007dc6fa6374deade2428e57"
        dependency_files = (
            root / "shared/broker-http/build.gradle",
            root / "common.gradle",
            root / "bundle.gradle",
            root / "jvm-library.gradle",
        )
        pins = []
        for path in dependency_files:
            contents = path.read_text(encoding="utf-8")
            file_pins = re.findall(
                r"com\.github\.komicaviewer\.NewsHub:extension-api:([0-9a-f]{40})",
                contents,
            )
            self.assertTrue(file_pins, f"missing extension-api pin in {path.name}")
            pins.extend(file_pins)
        self.assertEqual({expected_sha}, set(pins))
        for path in (
            root / "cloudbuild/pr-candidate.yaml",
            root / "cloudbuild/publish.yaml",
            root / "README.md",
        ):
            self.assertIn(expected_sha, path.read_text(encoding="utf-8"))
        stale_shas = {
            "53d421492614c13e2a5984" + "b4991513d993d44246",
            "3d63cb87eeff9ab799152db" + "0034ab3512656d83c",
        }
        stale_paths = []
        for path in root.rglob("*"):
            if not path.is_file() or any(part in {".git", ".gradle", "build"} for part in path.parts):
                continue
            if path.suffix not in {".gradle", ".kts", ".md", ".py", ".yaml", ".yml", ".json"}:
                continue
            contents = path.read_text(encoding="utf-8", errors="ignore")
            for stale_sha in stale_shas:
                if stale_sha in contents:
                    stale_paths.append(f"{path.relative_to(root)}:{stale_sha}")
        self.assertEqual([], stale_paths)

    def test_release_versions_do_not_fall_below_extension_api_payload_baseline(self):
        root = Path(__file__).resolve().parents[1]
        expected = {
            "eyny": (5, "0.1.4"),
            "gamer": (9, "0.0.9"),
            "hackernews": (5, "0.1.4"),
            "komica": (10, "0.3.7"),
            "komica2": (10, "0.4.6"),
            "mobile01": (6, "0.1.5"),
            "ptt": (8, "0.4.4"),
        }
        for module, (minimum_code, baseline_name) in expected.items():
            contents = (root / f"src/{module}/build.gradle.kts").read_text(encoding="utf-8")
            with self.subTest(module=module):
                code = re.search(r'set\("(?:bundle|ext)VersionCode", ([0-9]+)\)', contents)
                name = re.search(r'set\("(?:bundle|ext)VersionName", "([0-9]+\.[0-9]+\.[0-9]+)"\)', contents)
                self.assertIsNotNone(code)
                self.assertIsNotNone(name)
                current_code = int(code.group(1))
                self.assertGreaterEqual(current_code, minimum_code)
                if current_code == minimum_code:
                    self.assertEqual(baseline_name, name.group(1))

    def test_catalog_rejects_wildcards_unknown_capabilities_and_hash_mismatch(self):
        catalog = load_catalog()
        source = catalog["releases"][0]["sources"][0]
        policy = {
            "exactHosts": source["exactHosts"],
            "operations": [{
                "name": "source_read", "methods": ["GET", "HEAD"],
                "pathPrefixes": ["/"], "credentialed": True,
            }],
            "namedCapabilities": source["namedCapabilities"],
        }
        mutations = (
            ("wildcard", "exactHosts", ["*.example.com"], "exact DNS hosts"),
            ("unknown capability", "namedCapabilities", ["raw_socket"], "unknown namedCapabilities"),
            ("hash mismatch", "policyHash", "00" * 32, "policyHash mismatch"),
        )
        for label, field, value, message in mutations:
            invalid = copy.deepcopy(catalog)
            changed = invalid["releases"][0]["sources"][0]
            changed[field] = value
            if field != "policyHash":
                changed_policy = dict(policy)
                changed_policy[field] = value
                changed["policyHash"] = hashlib.sha256(
                    json.dumps(changed_policy, sort_keys=True, separators=(",", ":")).encode(),
                ).hexdigest()
            payload = {key: item for key, item in invalid.items() if not key.startswith("_")}
            with self.subTest(label=label), tempfile.NamedTemporaryFile(
                mode="w", suffix=".json", dir=Path(__file__).resolve().parents[1],
                encoding="utf-8",
            ) as handle:
                json.dump(payload, handle)
                handle.flush()
                with self.assertRaisesRegex(ValueError, message):
                    load_catalog(handle.name)

    def test_catalog_is_complete_and_uses_distinct_png_assets(self):
        catalog = load_catalog()

        self.assertGreater(len(catalog["releases"]), 0)
        sources = [source for release in catalog["releases"] for source in release["sources"]]
        self.assertEqual(7, len(catalog["releases"]))
        self.assertEqual(13, len(sources))
        self.assertEqual({2}, {source["protocol"] for source in sources})
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
