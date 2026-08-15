#!/usr/bin/env python3
"""Load and query the single source of truth for extension releases."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from pathlib import Path
from typing import Any

from source_host_contracts import load_contract, policy_sha256


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CATALOG_PATH = REPO_ROOT / "release-catalog.json"
EXACT_HOST = re.compile(
    r"(?=.{1,253}\Z)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+"
    r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?",
)
KNOWN_CAPABILITIES = {
    "external_link",
    "eyny_challenge_proof",
    "ptt_adult_consent_status",
    "resource_read",
}
SOURCE_KEYS = {
    "module", "testTask", "id", "className", "service", "protocol", "policyHash",
    "policyVersion", "exactHosts", "namedCapabilities",
}
ICON_KEYS = {"source", "name"}
RELEASE_KEYS = {
    "module", "gradleProject", "assembleTask", "apkOutput", "artifactName",
    "package", "metadata", "icon", "sources",
}


def _settings_modules(root: Path) -> set[str]:
    settings_path = root / "settings.gradle.kts"
    try:
        contents = settings_path.read_text(encoding="utf-8")
    except OSError as exc:
        raise ValueError(f"cannot read {settings_path}: {exc}") from exc
    modules = {
        module
        for module in re.findall(r'include\(["\']?:src:([^"\']+)["\']?\)', contents)
        if "$" not in module
    }
    dynamic = re.search(
        r'listOf\((.*?)\)\.forEach\s*\{\s*module\s*->\s*'
        r'include\(["\']:src:\$module["\']\)',
        contents,
        flags=re.DOTALL,
    )
    if dynamic:
        modules.update(re.findall(r'["\']([^"\']+)["\']', dynamic.group(1)))
    if not modules:
        raise ValueError("could not discover any :src Gradle modules from settings.gradle.kts")
    return modules


def _non_empty_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{label} must be a non-empty string")
    return value


def load_catalog(path: str | os.PathLike[str] = DEFAULT_CATALOG_PATH) -> dict:
    catalog_path = Path(path).resolve()
    try:
        catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"invalid release catalog {catalog_path}: {exc}") from exc

    if catalog.get("schemaVersion") != 1:
        raise ValueError(f"unsupported catalog schemaVersion: {catalog.get('schemaVersion')}")
    repository = catalog.get("repository")
    if not isinstance(repository, dict) or set(repository) != {"name", "description", "iconUrl", "website"}:
        raise ValueError("catalog repository display metadata is not exact")
    for key, value in repository.items():
        _non_empty_string(value, f"repository.{key}")
    if not repository["iconUrl"].startswith("https://") or not repository["website"].startswith("https://"):
        raise ValueError("repository display URLs must use HTTPS")
    releases = catalog.get("releases")
    if not isinstance(releases, list) or not releases:
        raise ValueError("catalog releases must be a non-empty array")
    removals = catalog.get("authorizedRemovals")
    if not isinstance(removals, dict):
        raise ValueError("catalog authorizedRemovals must be an object")
    for kind in ("packages", "sources"):
        values = removals.get(kind)
        if not isinstance(values, list) or any(not isinstance(v, str) or not v for v in values):
            raise ValueError(f"authorizedRemovals.{kind} must be an array of strings")
        if len(values) != len(set(values)):
            raise ValueError(f"authorizedRemovals.{kind} contains duplicates")

    root = catalog_path.parent
    contract = load_contract(root / "source-host-contracts.json")
    contract_sources = {source["id"]: source for source in contract["sources"]}
    release_modules: set[str] = set()
    packages: set[str] = set()
    source_ids: set[str] = set()
    class_names: set[str] = set()
    source_modules: set[str] = set()
    for release_index, release in enumerate(releases):
        if not isinstance(release, dict) or set(release) != RELEASE_KEYS:
            raise ValueError(f"releases[{release_index}] must contain exactly {sorted(RELEASE_KEYS)}")
        module = _non_empty_string(release["module"], f"releases[{release_index}].module")
        package = _non_empty_string(release["package"], f"releases[{release_index}].package")
        if module in release_modules:
            raise ValueError(f"duplicate release module: {module}")
        if package in packages:
            raise ValueError(f"duplicate release package: {package}")
        release_modules.add(module)
        packages.add(package)
        for key in RELEASE_KEYS - {"sources", "icon"}:
            _non_empty_string(release[key], f"releases[{release_index}].{key}")
        icon = release["icon"]
        if not isinstance(icon, dict) or set(icon) != ICON_KEYS:
            raise ValueError(f"icon for {module} must contain exactly {sorted(ICON_KEYS)}")
        icon_source = _non_empty_string(icon["source"], f"releases[{release_index}].icon.source")
        icon_name = _non_empty_string(icon["name"], f"releases[{release_index}].icon.name")
        if Path(icon_name).name != icon_name or not icon_name.endswith(".png"):
            raise ValueError(f"invalid icon destination filename for {module}: {icon_name}")
        icon_path = (root / icon_source).resolve()
        if not icon_path.is_relative_to(root) or not icon_path.is_file():
            raise ValueError(f"producer icon for {module} does not exist inside repository: {icon_path}")
        if icon_path.read_bytes()[:8] != b"\x89PNG\r\n\x1a\n":
            raise ValueError(f"producer icon for {module} is not a PNG file: {icon_path}")
        if release["artifactName"].count("{versionName}") != 1:
            raise ValueError(f"artifactName for {module} must contain exactly one {{versionName}}")
        metadata_path = (root / release["metadata"]).resolve()
        if not metadata_path.is_relative_to(root) or not metadata_path.is_file():
            raise ValueError(f"metadata for {module} does not exist inside the repository: {metadata_path}")
        gradle_dir = root / release["gradleProject"].removeprefix(":").replace(":", "/")
        if not (gradle_dir / "build.gradle.kts").is_file():
            raise ValueError(f"Gradle project for {module} does not exist: {gradle_dir}")

        sources = release["sources"]
        if not isinstance(sources, list) or not sources:
            raise ValueError(f"sources for {module} must be a non-empty array")
        for source_index, source in enumerate(sources):
            if not isinstance(source, dict) or set(source) != SOURCE_KEYS:
                raise ValueError(
                    f"{module}.sources[{source_index}] must contain exactly {sorted(SOURCE_KEYS)}",
                )
            for key in SOURCE_KEYS - {"protocol", "policyVersion", "exactHosts", "namedCapabilities"}:
                _non_empty_string(source[key], f"{module}.sources[{source_index}].{key}")
            if source["protocol"] != 1:
                raise ValueError(f"unsupported protocol for {source['id']}: {source['protocol']}")
            if source["policyVersion"] != 2:
                raise ValueError(f"new release metadata requires policyVersion 2 for {source['id']}")
            if not re.fullmatch(r"[0-9a-f]{64}", source["policyHash"]):
                raise ValueError(f"invalid policyHash for {source['id']}")
            for key in ("exactHosts", "namedCapabilities"):
                values = source[key]
                if (
                    not isinstance(values, list) or not values
                    or values != sorted(set(values))
                    or any(not isinstance(value, str) or not value for value in values)
                ):
                    raise ValueError(f"{key} for {source['id']} must be a sorted unique string array")
            if len(source["exactHosts"]) > 32 or any(
                not EXACT_HOST.fullmatch(host) for host in source["exactHosts"]
            ):
                raise ValueError(f"exactHosts for {source['id']} must contain only exact DNS hosts")
            if len(source["namedCapabilities"]) > 16 or any(
                capability not in KNOWN_CAPABILITIES
                for capability in source["namedCapabilities"]
            ):
                raise ValueError(f"unknown namedCapabilities for {source['id']}")
            reviewed = contract_sources.get(source["id"])
            if reviewed is None or reviewed["module"] != source["module"]:
                raise ValueError(f"missing reviewed host contract for {source['id']}")
            if source["exactHosts"] != reviewed["surfaces"]["request"]["exactHttpsHosts"]:
                raise ValueError(f"catalog request hosts are not derived from contract for {source['id']}")
            if source["namedCapabilities"] != reviewed["namedCapabilities"]:
                raise ValueError(f"catalog capabilities are not derived from contract for {source['id']}")
            actual_policy_hash = policy_sha256(reviewed)
            if actual_policy_hash != source["policyHash"]:
                raise ValueError(
                    f"policyHash mismatch for {source['id']}: {actual_policy_hash}",
                )
            if source["id"] in source_ids:
                raise ValueError(f"duplicate Source id: {source['id']}")
            if source["className"] in class_names:
                raise ValueError(f"duplicate Source className: {source['className']}")
            source_module = source["module"]
            source_dir = root / "src" / source_module
            if not (source_dir / "build.gradle.kts").is_file():
                raise ValueError(f"Source Gradle module does not exist: {source_module}")
            if not source["testTask"].startswith(f":src:{source_module}:"):
                raise ValueError(
                    f"testTask for {source['id']} does not belong to module {source_module}",
                )
            source_ids.add(source["id"])
            class_names.add(source["className"])
            source_modules.add(source_module)

        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        actual = {(source["id"], source["className"]) for source in metadata.get("sources", [])}
        expected = {(source["id"], source["className"]) for source in sources}
        if actual != expected:
            raise ValueError(
                f"catalog/metadata Source mismatch for {module}: "
                f"catalog={sorted(expected)}, metadata={sorted(actual)}",
            )

    settings_modules = _settings_modules(root)
    catalog_modules = release_modules | source_modules
    if settings_modules != catalog_modules:
        raise ValueError(
            "settings/catalog Gradle module mismatch: "
            f"settings={sorted(settings_modules)}, catalog={sorted(catalog_modules)}",
        )
    if source_ids != set(contract_sources):
        raise ValueError(
            "catalog/host contract Source mismatch: "
            f"catalog={sorted(source_ids)}, contract={sorted(contract_sources)}",
        )

    catalog["_path"] = str(catalog_path)
    catalog["_root"] = str(root)
    return catalog


def releases_by_module(catalog: dict) -> dict[str, dict]:
    return {release["module"]: release for release in catalog["releases"]}


def releases_by_package(catalog: dict) -> dict[str, dict]:
    return {release["package"]: release for release in catalog["releases"]}


def metadata_for_release(catalog: dict, release: dict) -> dict:
    path = Path(catalog["_root"]) / release["metadata"]
    return json.loads(path.read_text(encoding="utf-8"))


def artifact_name(release: dict, version_name: str) -> str:
    return release["artifactName"].format(versionName=version_name)


def gradle_tasks(catalog: dict) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for release in catalog["releases"]:
        for source in release["sources"]:
            if source["testTask"] not in seen:
                result.append(source["testTask"])
                seen.add(source["testTask"])
        if release["assembleTask"] not in seen:
            result.append(release["assembleTask"])
            seen.add(release["assembleTask"])
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", default=str(DEFAULT_CATALOG_PATH))
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("validate")
    subparsers.add_parser("gradle-tasks")
    subparsers.add_parser("artifact-rows")
    artifact_parser = subparsers.add_parser("artifact-name")
    artifact_parser.add_argument("module")
    artifact_parser.add_argument("version_name")
    args = parser.parse_args()

    catalog = load_catalog(args.catalog)
    if args.command == "validate":
        print(
            f"Release catalog validation passed: APKs={len(catalog['releases'])}, "
            f"Sources={sum(len(r['sources']) for r in catalog['releases'])}",
        )
    elif args.command == "gradle-tasks":
        print("\n".join(gradle_tasks(catalog)))
    elif args.command == "artifact-rows":
        for release in catalog["releases"]:
            print("\t".join((release["module"], release["apkOutput"])))
    elif args.command == "artifact-name":
        release = releases_by_module(catalog).get(args.module)
        if release is None:
            raise SystemExit(f"unknown release module: {args.module}")
        print(artifact_name(release, args.version_name))


if __name__ == "__main__":
    main()
