#!/usr/bin/env python3
"""Sign and incrementally publish dev-variant extension metadata for the `dev` branch.

The `.debug` Host embeds the dev root written by ``--init`` and verifies this
repository exactly like production, so every publish advances the
timestamp/snapshot/targets versions and keeps packages that were not rebuilt.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path

from generate_test_trust_fixture import (
    FixtureKeys,
    canonical,
    descriptor,
    expiry,
    fixture_source_descriptors,
)
from generate_trusted_metadata import catalog_network_policies
from release_catalog import load_catalog, metadata_for_release, releases_by_module
from validate_distribution import find_tool, read_apk_metadata, read_signing_fingerprint
from validate_release_bundles import module_from_apk_name

DEV_DEBUG_CERT_SHA256 = "5d8d88556c9b3b5f56847637aea4a4395161e46cb1a05a6047795b8a5b3bbdd1"
DEV_PACKAGE_SUFFIX = ".debug"
THRESHOLDS = {"root": 2, "targets": 2, "snapshot": 1, "timestamp": 1}
EXPIRY_DAYS = {"root": 365, "targets": 90, "snapshot": 90, "timestamp": 30}
REPO_ROOT = Path(__file__).resolve().parent.parent


def _utc_now() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


def _write(path: Path, raw: bytes) -> None:
    temporary = path.with_name(f".{path.name}.tmp")
    temporary.write_bytes(raw)
    os.replace(temporary, path)


def _is_within(path: Path, parent: Path) -> bool:
    return path == parent or parent in path.parents


def _guard_key_location(keys_dir: Path, output: Path) -> None:
    keys, out = keys_dir.resolve(), output.resolve()
    if _is_within(keys, out) or _is_within(keys, REPO_ROOT):
        raise ValueError("dev trust keys must live outside the output worktree and the source repository")


def _print_root(root_path: Path) -> None:
    print(f"dev root: {root_path}")
    print(f"dev root sha256: {hashlib.sha256(root_path.read_bytes()).hexdigest()}")


def init_repository(output: Path, keys_dir: Path, *, now: dt.datetime | None = None) -> Path:
    """Create the dev role keys and the version-1 dev root; never overwrite either."""
    _guard_key_location(keys_dir, output)
    metadata = output / "metadata"
    root_path = metadata / "root.json"
    if root_path.exists() or (metadata / "1.root.json").exists():
        raise ValueError(f"output already has a dev root: {root_path}")
    if keys_dir.exists():
        if any(keys_dir.iterdir()):
            raise ValueError(f"keys directory already has keys: {keys_dir}")
        keys_dir.rmdir()
    keys = FixtureKeys(keys_dir)
    now = now or _utc_now()
    all_ids = [keyid for ids in keys.roles.values() for keyid in ids]
    root_signed = {
        "_type": "root", "specVersion": "1.0", "version": 1,
        "expires": expiry(now, EXPIRY_DAYS["root"]), "consistentSnapshot": True,
        "keys": {keyid: keys.public_record(keyid) for keyid in all_ids},
        "roles": {role: {"keyids": ids, "threshold": THRESHOLDS[role]} for role, ids in keys.roles.items()},
    }
    root_raw = canonical(keys.envelope(root_signed, "root"))
    metadata.mkdir(parents=True, exist_ok=True)
    _write(metadata / "1.root.json", root_raw)
    _write(root_path, root_raw)
    _print_root(root_path)
    return root_path


def _load_keys(keys_dir: Path, root_signed: dict) -> FixtureKeys:
    if not keys_dir.is_dir():
        raise ValueError(f"missing dev trust keys: {keys_dir}")
    keys = FixtureKeys(keys_dir, reuse=True)
    for role, threshold in THRESHOLDS.items():
        declared = root_signed["roles"][role]
        if declared["threshold"] != threshold or sorted(declared["keyids"]) != sorted(keys.roles[role]):
            raise ValueError(f"dev trust keys do not match the published root: {role}")
    return keys


def _read_descriptor_target(metadata: Path, name: str, desc: dict) -> dict:
    path = metadata / f"{desc['version']}.{name}"
    raw = path.read_bytes() if path.is_file() else b""
    if len(raw) != desc["length"] or hashlib.sha256(raw).hexdigest() != desc["hashes"]["sha256"]:
        raise ValueError(f"existing {name} does not match its signed descriptor: {path.name}")
    signed = json.loads(raw)["signed"]
    if signed["version"] != desc["version"]:
        raise ValueError(f"existing {name} version mix-and-match: {path.name}")
    return signed


def _existing_state(metadata: Path) -> tuple[int, dict]:
    """Return the highest published online-role version and the current signed targets map."""
    numbered = [
        int(path.name.split(".", 1)[0])
        for pattern in ("*.targets.json", "*.snapshot.json")
        for path in metadata.glob(pattern)
        if path.name.split(".", 1)[0].isdigit()
    ]
    timestamp_path = metadata / "timestamp.json"
    if not timestamp_path.is_file():
        return max(numbered, default=0), {}
    timestamp = json.loads(timestamp_path.read_bytes())["signed"]
    snapshot = _read_descriptor_target(metadata, "snapshot.json", timestamp["meta"]["snapshot.json"])
    targets = _read_descriptor_target(metadata, "targets.json", snapshot["meta"]["targets.json"])
    version = max([timestamp["version"], snapshot["version"], targets["version"], *numbered])
    return version, targets["targets"]


def _target_for_apk(apk: Path, catalog: dict, releases: dict, policies: dict, aapt: str, apksigner: str):
    module = module_from_apk_name(apk.name, catalog)
    release = releases[module]
    apk_metadata = read_apk_metadata(str(apk), aapt)
    expected_package = release["package"] + DEV_PACKAGE_SUFFIX
    if apk_metadata["pkg"] != expected_package:
        raise ValueError(f"package mismatch for {module}: expected {expected_package}")
    signer = read_signing_fingerprint(str(apk), apksigner).lower()
    if signer != DEV_DEBUG_CERT_SHA256:
        raise ValueError(f"APK signer is not the dev debug certificate: {module}")
    release_metadata = metadata_for_release(catalog, release)
    languages = {source["lang"] for source in release_metadata["sources"]}
    value = apk.read_bytes()
    entry = {
        "length": len(value),
        "hashes": {"sha256": hashlib.sha256(value).hexdigest()},
        "custom": {
            "packageName": expected_package,
            "versionCode": apk_metadata["versionCode"],
            "versionName": apk_metadata["versionName"],
            "name": release_metadata["name"],
            "lang": next(iter(languages)) if len(languages) == 1 else "",
            "lineageRootSha256": signer,
            "apkSignerPins": [signer],
            "sources": fixture_source_descriptors(release, release_metadata, policies),
        },
    }
    return module, value, entry


def publish(
    apks: list[Path],
    output: Path,
    keys_dir: Path,
    *,
    aapt: str,
    apksigner: str,
    now: dt.datetime | None = None,
    catalog: dict | None = None,
) -> Path:
    """Validate every APK first, then merge, sign, and write the next metadata version."""
    _guard_key_location(keys_dir, output)
    metadata = output / "metadata"
    root_path = metadata / "root.json"
    if not root_path.is_file():
        raise ValueError(f"dev repository is not initialized (run --init): {root_path}")
    keys = _load_keys(keys_dir, json.loads(root_path.read_bytes())["signed"])
    catalog = catalog or load_catalog()
    releases = releases_by_module(catalog)
    policies = catalog_network_policies(catalog)

    built: dict[str, tuple[str, bytes, dict]] = {}
    for apk in apks:
        module, value, entry = _target_for_apk(apk, catalog, releases, policies, aapt, apksigner)
        if module in built:
            raise ValueError(f"duplicate APK for module: {module}")
        built[module] = (apk.name, value, entry)

    previous_version, previous_targets = _existing_state(metadata)
    replaced_packages = {entry["custom"]["packageName"] for _, _, entry in built.values()}
    targets_map = {
        path: entry for path, entry in previous_targets.items()
        if entry["custom"]["packageName"] not in replaced_packages
    }
    for name, _, entry in built.values():
        targets_map[f"apk/{name}"] = entry
    if not targets_map:
        raise ValueError("no APK targets to publish")

    apk_dir = output / "targets" / "apk"
    apk_dir.mkdir(parents=True, exist_ok=True)
    for name, value, _ in built.values():
        _write(apk_dir / name, value)

    now = now or _utc_now()
    version = previous_version + 1
    targets_raw = canonical(keys.envelope({
        "_type": "targets", "specVersion": "1.0", "version": version,
        "expires": expiry(now, EXPIRY_DAYS["targets"]), "targets": targets_map,
        "custom": {"repository": catalog["repository"]},
    }, "targets"))
    _write(metadata / f"{version}.targets.json", targets_raw)
    snapshot_raw = canonical(keys.envelope({
        "_type": "snapshot", "specVersion": "1.0", "version": version,
        "expires": expiry(now, EXPIRY_DAYS["snapshot"]),
        "meta": {"targets.json": descriptor(targets_raw, version)},
    }, "snapshot"))
    _write(metadata / f"{version}.snapshot.json", snapshot_raw)
    # timestamp.json is the only mutable entry point, so it is written last.
    _write(metadata / "timestamp.json", canonical(keys.envelope({
        "_type": "timestamp", "specVersion": "1.0", "version": version,
        "expires": expiry(now, EXPIRY_DAYS["timestamp"]),
        "meta": {"snapshot.json": descriptor(snapshot_raw, version)},
    }, "timestamp")))

    for path in set(previous_targets) - set(targets_map):
        stale = output / "targets" / path
        if stale.parent == apk_dir:
            stale.unlink(missing_ok=True)
    _print_root(root_path)
    print(f"dev metadata version: {version}")
    return metadata / "timestamp.json"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apks", nargs="*", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--keys", type=Path, required=True)
    parser.add_argument("--init", action="store_true")
    parser.add_argument("--aapt", default=os.environ.get("AAPT"))
    parser.add_argument("--apksigner", default=os.environ.get("APKSIGNER"))
    args = parser.parse_args()
    output, keys_dir = args.output.expanduser().resolve(), args.keys.expanduser().resolve()
    if not args.init and not args.apks:
        parser.error("APK paths are required unless --init is given")
    if args.init:
        init_repository(output, keys_dir)
    if args.apks:
        publish(
            [apk.resolve() for apk in args.apks], output, keys_dir,
            aapt=args.aapt or find_tool("AAPT", ("aapt", "aapt2")),
            apksigner=args.apksigner or find_tool("APKSIGNER", ("apksigner",)),
        )


if __name__ == "__main__":
    main()
