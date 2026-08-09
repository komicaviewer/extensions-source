#!/usr/bin/env python3
"""Fail closed unless every release declares only isolated, host-gated Source services."""
from __future__ import annotations

import argparse
import xml.etree.ElementTree as ET
from pathlib import Path

from release_catalog import DEFAULT_CATALOG_PATH, load_catalog, metadata_for_release


ANDROID = "{http://schemas.android.com/apk/res/android}"
ACTION = "tw.kevinzhang.newshub.extension.SERVICE"
BIND_PERMISSION = "tw.kevinzhang.newshub.permission.BIND_EXTENSION"
PROTOCOL_KEY = "newshub.extension.protocol"
SOURCE_KEYS = {
    "id": "newshub.extension.source_id",
    "name": "newshub.extension.source_name",
    "lang": "newshub.extension.source_lang",
    "baseUrl": "newshub.extension.source_base_url",
}
LEGACY_APPLICATION_KEYS = {
    "newshub.extension",
    "newshub.extension.registry",
    "newshub.extension.source_class",
}


def _attr(element: ET.Element, name: str) -> str | None:
    return element.get(ANDROID + name)


def validate_manifest(path: Path, expected_package: str, expected_sources: list[dict]) -> None:
    root = ET.parse(path).getroot()
    permissions = [_attr(node, "name") for node in root.findall("uses-permission")]
    if permissions:
        raise ValueError(f"extension APK must declare no permissions: {permissions}")
    application = root.find("application")
    if application is None:
        raise ValueError(f"missing application in {path}")
    app_metadata = {_attr(node, "name") for node in application.findall("meta-data")}
    forbidden = app_metadata & LEGACY_APPLICATION_KEYS
    if forbidden:
        raise ValueError(f"legacy application metadata is forbidden: {sorted(forbidden)}")

    actual_sources: dict[str, dict[str, str]] = {}
    service_names: set[str] = set()
    processes: set[str] = set()
    for service in application.findall("service"):
        actions = {
            _attr(action, "name")
            for intent in service.findall("intent-filter")
            for action in intent.findall("action")
        }
        if ACTION not in actions:
            continue
        name = _attr(service, "name")
        process = _attr(service, "process")
        if not name or name in service_names:
            raise ValueError(f"Source service name must be unique: {name}")
        if _attr(service, "exported") != "true":
            raise ValueError(f"Source service must be exported: {name}")
        if _attr(service, "isolatedProcess") != "true":
            raise ValueError(f"Source service must use isolatedProcess: {name}")
        if _attr(service, "permission") != BIND_PERMISSION:
            raise ValueError(f"Source service must require host signature permission: {name}")
        if not process or not process.startswith(":") or process in processes:
            raise ValueError(f"Source service process must be unique and private: {process}")
        values = {
            _attr(node, "name"): _attr(node, "value")
            for node in service.findall("meta-data")
        }
        if values.get(PROTOCOL_KEY) != "1":
            raise ValueError(f"Source service protocol must equal 1: {name}")
        descriptor = {field: values.get(key) for field, key in SOURCE_KEYS.items()}
        if any(not value for value in descriptor.values()):
            raise ValueError(f"Source service metadata is incomplete: {name}")
        source_id = descriptor["id"]
        if source_id in actual_sources:
            raise ValueError(f"duplicate Source id in manifest: {source_id}")
        actual_sources[source_id] = descriptor
        expected_source = next((item for item in expected_sources if item["id"] == source_id), None)
        resolved_name = expected_package + name if name.startswith(".") else name
        if expected_source is None or resolved_name != expected_source["service"]:
            raise ValueError(f"Source service class mismatch: {source_id}: {resolved_name}")
        if int(values[PROTOCOL_KEY]) != expected_source["protocol"]:
            raise ValueError(f"Source service protocol mismatch: {source_id}")
        service_names.add(name)
        processes.add(process)

    expected = {
        source["id"]: {field: source[field] for field in SOURCE_KEYS}
        for source in expected_sources
    }
    if actual_sources != expected:
        raise ValueError(
            f"Source service metadata mismatch: expected={expected}, actual={actual_sources}",
        )


def validate_all(catalog: dict) -> None:
    root = Path(catalog["_root"])
    for release in catalog["releases"]:
        manifest = root / "src" / release["module"] / "src/main/AndroidManifest.xml"
        metadata_by_id = {
            source["id"]: source
            for source in metadata_for_release(catalog, release)["sources"]
        }
        expected_sources = [
            {**metadata_by_id[source["id"]], **source}
            for source in release["sources"]
        ]
        validate_manifest(manifest, release["package"], expected_sources)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", default=str(DEFAULT_CATALOG_PATH))
    args = parser.parse_args()
    catalog = load_catalog(args.catalog)
    validate_all(catalog)
    print(
        "Source manifest validation passed: "
        f"APKs={len(catalog['releases'])}, "
        f"Sources={sum(len(r['sources']) for r in catalog['releases'])}",
    )


if __name__ == "__main__":
    main()
