#!/usr/bin/env python3
"""Validate release APK registries and bytecode against release-catalog.json."""
from __future__ import annotations

import argparse
import json
import os
import zipfile

from release_catalog import (
    DEFAULT_CATALOG_PATH,
    artifact_name,
    load_catalog,
    registry_for_release,
    releases_by_module,
)


REGISTRY_ASSET_PATH = "assets/newshub-extension.json"
REGISTRY_SCHEMA_VERSION = 1
SOURCE_FIELDS = ("className", "id", "name", "lang", "baseUrl")


def read_registry(apk_path: str) -> dict:
    try:
        with zipfile.ZipFile(apk_path) as apk:
            registry = json.loads(apk.read(REGISTRY_ASSET_PATH).decode("utf-8"))
    except KeyError as exc:
        raise ValueError(f"missing {REGISTRY_ASSET_PATH} in {os.path.basename(apk_path)}") from exc
    except (OSError, zipfile.BadZipFile, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"invalid extension registry in {os.path.basename(apk_path)}: {exc}") from exc

    if registry.get("schemaVersion") != REGISTRY_SCHEMA_VERSION:
        raise ValueError(f"unsupported registry schemaVersion: {registry.get('schemaVersion')}")
    if not isinstance(registry.get("name"), str) or not registry["name"].strip():
        raise ValueError("registry name must be non-empty")
    sources = registry.get("sources")
    if not isinstance(sources, list) or not sources:
        raise ValueError("registry sources must be a non-empty array")

    source_ids: set[str] = set()
    class_names: set[str] = set()
    for index, source in enumerate(sources):
        if not isinstance(source, dict):
            raise ValueError(f"sources[{index}] must be an object")
        for field in SOURCE_FIELDS:
            if not isinstance(source.get(field), str) or not source[field].strip():
                raise ValueError(f"sources[{index}].{field} must be non-empty")
        if source["id"] in source_ids:
            raise ValueError(f"duplicate source id: {source['id']}")
        if source["className"] in class_names:
            raise ValueError(f"duplicate source className: {source['className']}")
        source_ids.add(source["id"])
        class_names.add(source["className"])
    return registry


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
    registries: dict[str, dict] = {}
    all_source_ids: set[str] = set()
    apk_names = sorted(name for name in os.listdir(apk_dir) if name.endswith(".apk"))

    for apk_name in apk_names:
        module = module_from_apk_name(apk_name, catalog)
        if module in registries:
            raise ValueError(f"duplicate release APK module: {module}")
        release = releases[module]
        apk_path = os.path.join(apk_dir, apk_name)
        registry = read_registry(apk_path)
        expected_registry = registry_for_release(catalog, release)
        if registry != expected_registry:
            raise ValueError(
                f"APK registry does not match catalog registry for {module}: "
                f"expected={expected_registry}, actual={registry}",
            )

        source_ids = {source["id"] for source in registry["sources"]}
        duplicates = all_source_ids.intersection(source_ids)
        if duplicates:
            raise ValueError(f"Source ids belong to multiple APKs: {sorted(duplicates)}")
        all_source_ids.update(source_ids)
        if verify_dex:
            _validate_dex(apk_path, release, catalog)
        registries[module] = registry

    if set(registries) != set(releases):
        raise ValueError(
            f"incomplete release APK set: expected={sorted(releases)}, actual={sorted(registries)}",
        )
    expected_source_ids = {
        source["id"] for release in catalog["releases"] for source in release["sources"]
    }
    if all_source_ids != expected_source_ids:
        raise ValueError(
            f"incomplete release Source set: expected={sorted(expected_source_ids)}, "
            f"actual={sorted(all_source_ids)}",
        )
    return registries


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk_dir")
    parser.add_argument("--catalog", default=str(DEFAULT_CATALOG_PATH))
    parser.add_argument("--skip-dex", action="store_true")
    args = parser.parse_args()
    catalog = load_catalog(args.catalog)
    registries = validate_release_bundles(args.apk_dir, catalog, verify_dex=not args.skip_dex)
    print(
        "Complete release validation passed: "
        f"APKs={len(registries)}, Sources={sum(len(r['sources']) for r in registries.values())}",
    )


if __name__ == "__main__":
    main()
