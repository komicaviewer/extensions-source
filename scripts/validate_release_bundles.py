#!/usr/bin/env python3
"""Validate the complete APK and Source set published by release CI."""
import json
import os
import re
import sys
import zipfile


REGISTRY_ASSET_PATH = "assets/newshub-extension.json"
REGISTRY_SCHEMA_VERSION = 1
SOURCE_FIELDS = ("className", "id", "name", "lang", "baseUrl")
EXPECTED_SOURCE_COUNT = 9
EXPECTED_RELEASES = {
    "gamer": {
        "package": "tw.kevinzhang.newshub.extension.gamer",
        "name": "NewsHub: Gamer 巴哈姆特",
        "sources": {"tw.kevinzhang.newshub.extension.gamer"},
    },
    "komica": {
        "package": "tw.kevinzhang.newshub.extension.komica",
        "name": "NewsHub: Komica",
        "sources": {
            "tw.kevinzhang.komica.twocat",
            "tw.kevinzhang.komica.sora",
            "tw.kevinzhang.akraft",
            "tw.kevinzhang.nagatoyuki",
            "tw.kevinzhang.wtako",
        },
    },
    "komica2": {
        "package": "tw.kevinzhang.newshub.extension.komica2",
        "name": "NewsHub: Komica2",
        "sources": {
            "tw.kevinzhang.komica2.twocat",
            "tw.kevinzhang.komica2.sora",
            "tw.kevinzhang.komica2.zawarudo",
        },
    },
}
APK_NAME_PATTERN = re.compile(r"^newshub-([a-z0-9_-]+)-v[^/]+\.apk$")


def read_registry(apk_path: str) -> dict:
    try:
        with zipfile.ZipFile(apk_path) as apk:
            registry = json.loads(apk.read(REGISTRY_ASSET_PATH).decode("utf-8"))
    except KeyError as e:
        raise ValueError(f"missing {REGISTRY_ASSET_PATH}") from e
    except (zipfile.BadZipFile, UnicodeDecodeError, json.JSONDecodeError) as e:
        raise ValueError(f"invalid extension registry: {e}") from e

    if registry.get("schemaVersion") != REGISTRY_SCHEMA_VERSION:
        raise ValueError(f"unsupported registry schemaVersion: {registry.get('schemaVersion')}")
    if not isinstance(registry.get("name"), str) or not registry["name"].strip():
        raise ValueError("registry name must be non-empty")
    sources = registry.get("sources")
    if not isinstance(sources, list) or not sources:
        raise ValueError("registry sources must be a non-empty array")

    source_ids = set()
    for index, source in enumerate(sources):
        if not isinstance(source, dict):
            raise ValueError(f"sources[{index}] must be an object")
        for field in SOURCE_FIELDS:
            if not isinstance(source.get(field), str) or not source[field].strip():
                raise ValueError(f"sources[{index}].{field} must be non-empty")
        source_id = source["id"]
        if source_id in source_ids:
            raise ValueError(f"duplicate source id: {source_id}")
        source_ids.add(source_id)
    return registry


def module_from_apk_name(apk_name: str) -> str:
    match = APK_NAME_PATTERN.fullmatch(apk_name)
    if not match:
        raise ValueError(f"unexpected release APK filename: {apk_name}")
    module = match.group(1)
    if module not in EXPECTED_RELEASES:
        raise ValueError(f"unexpected release APK module: {module}")
    return module


def validate_release_bundles(apk_dir: str) -> dict[str, dict]:
    registries = {}
    all_source_ids = set()
    apk_names = sorted(name for name in os.listdir(apk_dir) if name.endswith(".apk"))

    for apk_name in apk_names:
        module = module_from_apk_name(apk_name)
        if module in registries:
            raise ValueError(f"duplicate release APK module: {module}")

        registry = read_registry(os.path.join(apk_dir, apk_name))
        expected = EXPECTED_RELEASES[module]
        if registry["name"] != expected["name"]:
            raise ValueError(
                f"unexpected registry name for {module}: "
                f"expected={expected['name']!r}, actual={registry['name']!r}",
            )

        source_ids = {source["id"] for source in registry["sources"]}
        if source_ids != expected["sources"]:
            raise ValueError(
                f"unexpected sources for {module}: expected={sorted(expected['sources'])}, "
                f"actual={sorted(source_ids)}",
            )
        duplicate_source_ids = all_source_ids.intersection(source_ids)
        if duplicate_source_ids:
            raise ValueError(
                "source ids must belong to exactly one release APK: "
                f"{sorted(duplicate_source_ids)}",
            )
        all_source_ids.update(source_ids)
        registries[module] = registry

    if set(registries) != set(EXPECTED_RELEASES):
        raise ValueError(
            f"incomplete release APK set: expected={sorted(EXPECTED_RELEASES)}, "
            f"actual={sorted(registries)}",
        )
    if len(all_source_ids) != EXPECTED_SOURCE_COUNT:
        raise ValueError(
            f"incomplete release Source set: expected={EXPECTED_SOURCE_COUNT}, "
            f"actual={len(all_source_ids)}",
        )
    return registries


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(f"Usage: {sys.argv[0]} <apk_dir>")
    registries = validate_release_bundles(sys.argv[1])
    source_count = sum(len(registry["sources"]) for registry in registries.values())
    print(
        "Complete release validation passed: "
        f"APKs={len(registries)}, Sources={source_count}",
    )


if __name__ == "__main__":
    main()
