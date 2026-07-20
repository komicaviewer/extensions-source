#!/usr/bin/env python3
"""Validate a complete extensions distribution tree and publication history."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
from pathlib import Path
from typing import Callable

from release_catalog import (
    DEFAULT_CATALOG_PATH,
    artifact_name,
    load_catalog,
    registry_for_release,
    releases_by_package,
)
from validate_release_bundles import read_registry


PACKAGE_RE = re.compile(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'")
CERT_RE = re.compile(r"Signer #1 certificate SHA-256 digest:\s*([0-9A-Fa-f: ]+)")
INDEX_KEYS = {
    "pkg", "name", "versionCode", "versionName", "lang", "apkName", "iconName", "sha256", "sources",
}
INDEX_SOURCE_KEYS = {"id", "name", "lang", "baseUrl"}


def normalize_fingerprint(value: str) -> str:
    return re.sub(r"[^0-9A-Fa-f]", "", value).upper()


def sha256_file(path: str | os.PathLike[str]) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as file:
        for chunk in iter(lambda: file.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def find_tool(environment_name: str, candidates: tuple[str, ...]) -> str:
    configured = os.environ.get(environment_name)
    if configured:
        return configured
    for candidate in candidates:
        found = shutil.which(candidate)
        if found:
            return found
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if sdk:
        build_tools = Path(sdk) / "build-tools"
        if build_tools.is_dir():
            for version in sorted(build_tools.iterdir(), reverse=True):
                for candidate in candidates:
                    path = version / candidate
                    if path.is_file():
                        return str(path)
    raise FileNotFoundError(f"{environment_name} tool not found")


def read_apk_metadata(apk_path: str, aapt: str) -> dict:
    result = subprocess.run(
        [aapt, "dump", "badging", apk_path],
        capture_output=True,
        text=True,
        timeout=60,
        check=False,
    )
    if result.returncode != 0:
        raise ValueError(f"aapt failed for {os.path.basename(apk_path)}: {result.stderr[:200]}")
    match = PACKAGE_RE.search(result.stdout)
    if not match:
        raise ValueError(f"aapt returned no package metadata for {os.path.basename(apk_path)}")
    return {"pkg": match.group(1), "versionCode": int(match.group(2)), "versionName": match.group(3)}


def read_signing_fingerprint(apk_path: str, apksigner: str) -> str:
    result = subprocess.run(
        [apksigner, "verify", "--verbose", "--print-certs", apk_path],
        capture_output=True,
        text=True,
        timeout=60,
        check=False,
    )
    if result.returncode != 0:
        raise ValueError(f"signature verification failed for {os.path.basename(apk_path)}: {result.stderr[:200]}")
    match = CERT_RE.search(result.stdout)
    if not match:
        raise ValueError(f"apksigner returned no SHA-256 certificate for {os.path.basename(apk_path)}")
    return normalize_fingerprint(match.group(1))


def _load_indexes(tree: Path) -> list[dict]:
    try:
        pretty = json.loads((tree / "index.json").read_text(encoding="utf-8"))
        compact = json.loads((tree / "index.min.json").read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"invalid distribution index in {tree}: {exc}") from exc
    if pretty != compact:
        raise ValueError("index.json and index.min.json are not semantically identical")
    if not isinstance(pretty, list):
        raise ValueError("distribution index must be an array")
    return pretty


def _source_index_descriptor(source: dict) -> dict:
    return {key: source[key] for key in ("id", "name", "lang", "baseUrl")}


def _validate_index_shape(index: list[dict]) -> None:
    for position, item in enumerate(index):
        if not isinstance(item, dict) or set(item) != INDEX_KEYS:
            raise ValueError(f"index[{position}] must contain exactly {sorted(INDEX_KEYS)}")
        if not isinstance(item["versionCode"], int) or isinstance(item["versionCode"], bool):
            raise ValueError(f"index[{position}].versionCode must be an integer")
        if not isinstance(item["sources"], list) or not item["sources"]:
            raise ValueError(f"index[{position}].sources must be a non-empty array")
        for source_position, source in enumerate(item["sources"]):
            if not isinstance(source, dict) or set(source) != INDEX_SOURCE_KEYS:
                raise ValueError(
                    f"index[{position}].sources[{source_position}] must contain exactly "
                    f"{sorted(INDEX_SOURCE_KEYS)}",
                )


def _safe_child(root: Path, directory: str, name: object) -> Path:
    if not isinstance(name, str) or not name or Path(name).name != name:
        raise ValueError(f"invalid {directory} filename: {name!r}")
    return root / directory / name


def _validate_history(candidate: list[dict], baseline: list[dict], catalog: dict) -> None:
    candidate_by_package = {item["pkg"]: item for item in candidate}
    baseline_by_package = {item["pkg"]: item for item in baseline}
    removed_packages = set(baseline_by_package) - set(candidate_by_package)
    authorized_packages = set(catalog["authorizedRemovals"]["packages"])
    unauthorized_packages = removed_packages - authorized_packages
    if unauthorized_packages:
        raise ValueError(f"unauthorized package deletion: {sorted(unauthorized_packages)}")

    baseline_sources = {
        source["id"] for item in baseline for source in item.get("sources", [])
    }
    candidate_sources = {
        source["id"] for item in candidate for source in item.get("sources", [])
    }
    unauthorized_sources = (
        baseline_sources - candidate_sources - set(catalog["authorizedRemovals"]["sources"])
    )
    if unauthorized_sources:
        raise ValueError(f"unauthorized Source deletion: {sorted(unauthorized_sources)}")

    for package in set(candidate_by_package).intersection(baseline_by_package):
        new = candidate_by_package[package]
        old = baseline_by_package[package]
        if new["versionCode"] < old["versionCode"]:
            raise ValueError(
                f"versionCode regression for {package}: old={old['versionCode']}, new={new['versionCode']}",
            )
        if new["versionCode"] == old["versionCode"] and new["sha256"] != old["sha256"]:
            raise ValueError(f"same versionCode changed SHA-256 for {package}: {new['versionCode']}")


def validate_distribution_tree(
    tree_dir: str,
    catalog: dict | None = None,
    *,
    aapt: str,
    apksigner: str,
    expected_signing_cert_sha256: str,
    baseline_dir: str | None = None,
    metadata_reader: Callable[[str, str], dict] = read_apk_metadata,
    signature_reader: Callable[[str, str], str] = read_signing_fingerprint,
) -> list[dict]:
    catalog = catalog or load_catalog()
    tree = Path(tree_dir)
    index = _load_indexes(tree)
    _validate_index_shape(index)
    expected_by_package = releases_by_package(catalog)
    packages = [item.get("pkg") for item in index if isinstance(item, dict)]
    if len(packages) != len(index) or len(packages) != len(set(packages)):
        raise ValueError("distribution index contains invalid or duplicate packages")
    if set(packages) != set(expected_by_package):
        raise ValueError(
            f"distribution package set mismatch: expected={sorted(expected_by_package)}, "
            f"actual={sorted(packages)}",
        )

    referenced_apks: set[str] = set()
    expected_fingerprint = normalize_fingerprint(expected_signing_cert_sha256)
    if len(expected_fingerprint) != 64:
        raise ValueError("expected signing certificate SHA-256 must contain 64 hexadecimal digits")

    for item in index:
        package = item["pkg"]
        release = expected_by_package[package]
        apk_path = _safe_child(tree, "apk", item.get("apkName"))
        icon_path = _safe_child(tree, "icon", item.get("iconName"))
        if not apk_path.is_file():
            raise ValueError(f"referenced APK does not exist: {apk_path}")
        if not icon_path.is_file():
            raise ValueError(f"referenced icon does not exist: {icon_path}")
        referenced_apks.add(apk_path.name)
        if item["iconName"] != release["icon"]["name"]:
            raise ValueError(f"unexpected icon for {package}: {item['iconName']}")
        if sha256_file(apk_path) != item.get("sha256"):
            raise ValueError(f"SHA-256 mismatch for {apk_path.name}")

        metadata = metadata_reader(str(apk_path), aapt)
        for key in ("pkg", "versionCode", "versionName"):
            if metadata.get(key) != item.get(key):
                raise ValueError(
                    f"APK/index {key} mismatch for {apk_path.name}: "
                    f"apk={metadata.get(key)!r}, index={item.get(key)!r}",
                )
        if metadata["pkg"] != release["package"]:
            raise ValueError(f"APK package is not authorized by catalog: {metadata['pkg']}")
        if apk_path.name != artifact_name(release, metadata["versionName"]):
            raise ValueError(f"unexpected APK filename for {package}: {apk_path.name}")

        registry = read_registry(str(apk_path))
        expected_registry = registry_for_release(catalog, release)
        if registry != expected_registry:
            raise ValueError(f"APK registry does not match catalog registry for {release['module']}")
        expected_index_sources = [_source_index_descriptor(source) for source in registry["sources"]]
        if item.get("name") != registry["name"] or item.get("sources") != expected_index_sources:
            raise ValueError(f"index registry metadata mismatch for {release['module']}")
        languages = {source["lang"] for source in registry["sources"]}
        expected_language = next(iter(languages)) if len(languages) == 1 else ""
        if item.get("lang") != expected_language:
            raise ValueError(f"index language mismatch for {release['module']}")

        actual_fingerprint = signature_reader(str(apk_path), apksigner)
        if normalize_fingerprint(actual_fingerprint) != expected_fingerprint:
            raise ValueError(f"unexpected signing certificate for {apk_path.name}")

    actual_apks = {path.name for path in (tree / "apk").glob("*.apk")}
    if actual_apks != referenced_apks:
        raise ValueError(
            f"distribution APK directory mismatch: referenced={sorted(referenced_apks)}, "
            f"actual={sorted(actual_apks)}",
        )

    if baseline_dir is not None:
        _validate_history(index, _load_indexes(Path(baseline_dir)), catalog)
    return index


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("tree_dir")
    parser.add_argument("--catalog", default=str(DEFAULT_CATALOG_PATH))
    parser.add_argument("--baseline-dir")
    parser.add_argument("--aapt", default=os.environ.get("AAPT"))
    parser.add_argument("--apksigner", default=os.environ.get("APKSIGNER"))
    parser.add_argument(
        "--signing-cert-sha256",
        default=os.environ.get("SIGNING_CERT_SHA256"),
    )
    args = parser.parse_args()
    aapt = args.aapt or find_tool("AAPT", ("aapt", "aapt2"))
    apksigner = args.apksigner or find_tool("APKSIGNER", ("apksigner",))
    if not args.signing_cert_sha256:
        raise SystemExit("SIGNING_CERT_SHA256 is required")
    index = validate_distribution_tree(
        args.tree_dir,
        load_catalog(args.catalog),
        aapt=aapt,
        apksigner=apksigner,
        expected_signing_cert_sha256=args.signing_cert_sha256,
        baseline_dir=args.baseline_dir,
    )
    print(
        f"Distribution validation passed: APKs={len(index)}, "
        f"Sources={sum(len(item['sources']) for item in index)}",
    )


if __name__ == "__main__":
    main()
