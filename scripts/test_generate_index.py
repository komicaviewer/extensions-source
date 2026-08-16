#!/usr/bin/env python3
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import generate_index as generator
from generate_index import _publish_staged_tree, apk_payload_sha256, generate_index
from release_catalog import load_catalog
from test_support import (
    build_distribution_tree,
    metadata_reader_for,
    signature_reader,
    write_complete_apks,
)


def snapshot(root: Path) -> dict[str, bytes]:
    if not root.exists():
        return {}
    return {
        str(path.relative_to(root)): path.read_bytes()
        for path in root.rglob("*")
        if path.is_file()
    }


class GenerateIndexTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        self.apk_dir = root / "input"
        self.output_dir = root / "output"
        self.apk_dir.mkdir()
        self.catalog = load_catalog()

    def call_generate(self, **overrides):
        arguments = {
            "catalog": self.catalog,
            "metadata_reader": metadata_reader_for(self.catalog),
            "signature_reader": signature_reader,
        }
        arguments.update(overrides)
        return generate_index(
            str(self.apk_dir),
            str(self.output_dir),
            "unused-aapt",
            "unused-apksigner",
            **arguments,
        )

    def write_existing_output(self):
        (self.output_dir / "apk").mkdir(parents=True)
        (self.output_dir / "icon").mkdir()
        (self.output_dir / "apk/old.apk").write_bytes(b"old-apk")
        (self.output_dir / "icon/old.png").write_bytes(b"old-icon")
        (self.output_dir / "index.json").write_text("old-index", encoding="utf-8")
        (self.output_dir / "index.min.json").write_text("old-min", encoding="utf-8")
        (self.output_dir / "repo.json").write_text("keep", encoding="utf-8")

    def test_payload_hash_ignores_signatures_and_build_provenance(self):
        import zipfile

        first = Path(self.temporary.name) / "first.apk"
        second = Path(self.temporary.name) / "second.apk"
        for path, marker in ((first, "first"), (second, "second")):
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("classes.dex", "runtime")
                archive.writestr("META-INF/MANIFEST.MF", marker)
                archive.writestr("META-INF/CERT.SF", marker)
                archive.writestr("META-INF/CERT.RSA", marker)
                archive.writestr("META-INF/version-control-info.textproto", marker)

        self.assertEqual(apk_payload_sha256(first), apk_payload_sha256(second))

    def test_incomplete_input_does_not_touch_existing_output(self):
        self.write_existing_output()
        before = snapshot(self.output_dir)

        with self.assertRaisesRegex(ValueError, "incomplete release APK set"):
            self.call_generate()

        self.assertEqual(before, snapshot(self.output_dir))

    def test_package_mismatch_does_not_touch_existing_output(self):
        self.write_existing_output()
        write_complete_apks(self.apk_dir, self.catalog)
        before = snapshot(self.output_dir)
        normal_reader = metadata_reader_for(self.catalog)

        def wrong_package(apk_path, aapt):
            metadata = normal_reader(apk_path, aapt)
            if "gamer" in Path(apk_path).name:
                metadata["pkg"] += ".wrong"
            return metadata

        with self.assertRaisesRegex(ValueError, "unexpected package for gamer"):
            self.call_generate(metadata_reader=wrong_package)

        self.assertEqual(before, snapshot(self.output_dir))

    def test_candidate_validation_failure_does_not_touch_existing_output(self):
        self.write_existing_output()
        write_complete_apks(self.apk_dir, self.catalog)
        before = snapshot(self.output_dir)

        def reject_candidate(*_args, **kwargs):
            self.assertEqual(str(self.output_dir.resolve()), kwargs["baseline_dir"])
            self.assertEqual(
                "old-index",
                (Path(kwargs["baseline_dir"]) / "index.json").read_text(encoding="utf-8"),
            )
            raise ValueError("injected candidate failure")

        with self.assertRaisesRegex(ValueError, "injected candidate failure"):
            self.call_generate(distribution_validator=reject_candidate)

        self.assertEqual(before, snapshot(self.output_dir))

    def test_complete_input_replaces_managed_tree_and_preserves_repo_files(self):
        self.write_existing_output()
        # An invalid old index is intentionally not used as a baseline in this success test.
        (self.output_dir / "index.min.json").unlink()
        write_complete_apks(self.apk_dir, self.catalog)

        extensions = self.call_generate()

        self.assertEqual(len(self.catalog["releases"]), len(extensions))
        self.assertEqual(
            sum(len(release["sources"]) for release in self.catalog["releases"]),
            sum(len(extension["sources"]) for extension in extensions),
        )
        self.assertEqual(
            {item["apkName"] for item in extensions},
            {path.name for path in (self.output_dir / "apk").glob("*.apk")},
        )
        self.assertEqual(
            {release["icon"]["name"] for release in self.catalog["releases"]},
            {path.name for path in (self.output_dir / "icon").glob("*.png")},
        )
        pretty = json.loads((self.output_dir / "index.json").read_text(encoding="utf-8"))
        compact = json.loads((self.output_dir / "index.min.json").read_text(encoding="utf-8"))
        self.assertEqual(extensions, pretty)
        self.assertEqual(pretty, compact)
        self.assertEqual("keep", (self.output_dir / "repo.json").read_text(encoding="utf-8"))

    def test_same_version_reuses_baseline_when_only_apk_packaging_differs(self):
        import zipfile

        baseline = build_distribution_tree(self.output_dir, self.catalog)
        write_complete_apks(self.apk_dir, self.catalog)
        baseline_bytes = {
            item["pkg"]: (self.output_dir / "apk" / item["apkName"]).read_bytes()
            for item in baseline
        }
        for apk_path in self.apk_dir.glob("*.apk"):
            with zipfile.ZipFile(apk_path, "a") as archive:
                archive.writestr("META-INF/CERT.SF", "rebuilt")
                archive.writestr("META-INF/CERT.RSA", "rebuilt")
                archive.writestr("META-INF/version-control-info.textproto", "rebuilt")

        extensions = self.call_generate()

        for item in extensions:
            self.assertEqual(
                baseline_bytes[item["pkg"]],
                (self.output_dir / "apk" / item["apkName"]).read_bytes(),
            )

    def test_same_version_rejects_changed_apk_payload(self):
        import zipfile

        build_distribution_tree(self.output_dir, self.catalog)
        write_complete_apks(self.apk_dir, self.catalog)
        gamer_apk = next(self.apk_dir.glob("*gamer*.apk"))
        with zipfile.ZipFile(gamer_apk, "a") as archive:
            archive.writestr("assets/changed.txt", "changed")
        before = snapshot(self.output_dir)

        with self.assertRaisesRegex(ValueError, "APK payload changed without versionCode bump"):
            self.call_generate()

        self.assertEqual(before, snapshot(self.output_dir))

    def test_publish_mode_preserves_baseline_for_changed_unversioned_payload(self):
        baseline = build_distribution_tree(self.output_dir, self.catalog)
        write_complete_apks(self.apk_dir, self.catalog)
        baseline_bytes = {
            item["pkg"]: (self.output_dir / "apk" / item["apkName"]).read_bytes()
            for item in baseline
        }
        import zipfile

        gamer_apk = next(self.apk_dir.glob("*gamer*.apk"))
        with zipfile.ZipFile(gamer_apk, "a") as archive:
            archive.writestr("assets/unreleased.txt", "not-for-this-release")

        extensions = self.call_generate(preserve_unchanged_baseline=True)

        for item in extensions:
            self.assertEqual(
                baseline_bytes[item["pkg"]],
                (self.output_dir / "apk" / item["apkName"]).read_bytes(),
            )

    def test_publish_error_rolls_back_all_managed_paths(self):
        self.write_existing_output()
        stage = Path(self.temporary.name) / "stage"
        (stage / "apk").mkdir(parents=True)
        (stage / "icon").mkdir()
        (stage / "apk/new.apk").write_bytes(b"new")
        (stage / "icon/new.png").write_bytes(b"new")
        (stage / "index.json").write_text("new", encoding="utf-8")
        (stage / "index.min.json").write_text("new", encoding="utf-8")
        before = snapshot(self.output_dir)
        real_replace = os.replace
        calls = 0

        def fail_once(source, destination):
            nonlocal calls
            calls += 1
            if calls == 6:
                raise OSError("injected replace failure")
            return real_replace(source, destination)

        with patch.object(generator.os, "replace", side_effect=fail_once):
            with self.assertRaisesRegex(OSError, "injected replace failure"):
                _publish_staged_tree(stage, self.output_dir)

        self.assertEqual(before, snapshot(self.output_dir))


if __name__ == "__main__":
    unittest.main()
