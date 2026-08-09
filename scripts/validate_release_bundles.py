#!/usr/bin/env python3
"""Validate release APK contents against the in-repository release catalog."""
from __future__ import annotations

import argparse
import os
import zipfile

from release_catalog import (
    DEFAULT_CATALOG_PATH,
    artifact_name,
    load_catalog,
    metadata_for_release,
    releases_by_module,
)


REGISTRY_ASSET_PATH = "assets/newshub-extension.json"
def reject_legacy_registry(apk_path: str) -> None:
    try:
        with zipfile.ZipFile(apk_path) as apk:
            if REGISTRY_ASSET_PATH in apk.namelist():
                raise ValueError(
                    f"legacy extension registry is forbidden: {os.path.basename(apk_path)}",
                )
    except (OSError, zipfile.BadZipFile) as exc:
        raise ValueError(f"invalid extension APK {os.path.basename(apk_path)}: {exc}") from exc


def module_from_apk_name(apk_name: str, catalog: dict) -> str:
    matches = []
    for release in catalog["releases"]:
        prefix, suffix = release["artifactName"].split("{versionName}")
        if apk_name.startswith(prefix) and apk_name.endswith(suffix):
            version = apk_name[len(prefix):len(apk_name) - len(suffix) if suffix else None]
            if version:
                matches.append(release["module"])
    if len(matches) != 1:
        raise ValueError(f"unexpected release APK filename: {apk_name}")
    return matches[0]


def _dex_bytes(apk_path: str) -> bytes:
    with zipfile.ZipFile(apk_path) as apk:
        dex_names = sorted(name for name in apk.namelist() if name.startswith("classes") and name.endswith(".dex"))
        if not dex_names:
            raise ValueError(f"release APK has no classes.dex: {os.path.basename(apk_path)}")
        return b"\n".join(apk.read(name) for name in dex_names)


def _validate_dex(apk_path: str, release: dict, catalog: dict) -> None:
    dex = _dex_bytes(apk_path)
    expected_classes = {source["className"] for source in release["sources"]}
    for class_name in expected_classes:
        marker = class_name.replace(".", "/").encode()
        if marker not in dex:
            raise ValueError(f"{release['module']} APK is missing Source class {class_name}")
    for other in catalog["releases"]:
        if other is release:
            continue
        for source in other["sources"]:
            marker = source["className"].replace(".", "/").encode()
            if marker in dex:
                raise ValueError(
                    f"{release['module']} APK contains foreign Source class {source['className']}",
                )


def validate_release_bundles(
    apk_dir: str,
    catalog: dict | None = None,
    *,
    verify_dex: bool = True,
) -> dict[str, dict]:
    catalog = catalog or load_catalog()
    releases = releases_by_module(catalog)
    metadata_by_module: dict[str, dict] = {}
    all_source_ids: set[str] = set()
    apk_names = sorted(name for name in os.listdir(apk_dir) if name.endswith(".apk"))

    for apk_name in apk_names:
        module = module_from_apk_name(apk_name, catalog)
        if module in metadata_by_module:
            raise ValueError(f"duplicate release APK module: {module}")
        release = releases[module]
        apk_path = os.path.join(apk_dir, apk_name)
        reject_legacy_registry(apk_path)
        metadata = metadata_for_release(catalog, release)

        source_ids = {source["id"] for source in metadata["sources"]}
        duplicates = all_source_ids.intersection(source_ids)
        if duplicates:
            raise ValueError(f"Source ids belong to multiple APKs: {sorted(duplicates)}")
        all_source_ids.update(source_ids)
        if verify_dex:
            _validate_dex(apk_path, release, catalog)
        metadata_by_module[module] = metadata

    if set(metadata_by_module) != set(releases):
        raise ValueError(
            f"incomplete release APK set: expected={sorted(releases)}, actual={sorted(metadata_by_module)}",
        )
    expected_source_ids = {
        source["id"] for release in catalog["releases"] for source in release["sources"]
    }
    if all_source_ids != expected_source_ids:
        raise ValueError(
            f"incomplete release Source set: expected={sorted(expected_source_ids)}, "
            f"actual={sorted(all_source_ids)}",
        )
    return metadata_by_module


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk_dir")
    parser.add_argument("--catalog", default=str(DEFAULT_CATALOG_PATH))
    parser.add_argument("--skip-dex", action="store_true")
    args = parser.parse_args()
    catalog = load_catalog(args.catalog)
    metadata = validate_release_bundles(args.apk_dir, catalog, verify_dex=not args.skip_dex)
    print(
        "Complete release validation passed: "
        f"APKs={len(metadata)}, Sources={sum(len(r['sources']) for r in metadata.values())}",
    )


if __name__ == "__main__":
    main()
