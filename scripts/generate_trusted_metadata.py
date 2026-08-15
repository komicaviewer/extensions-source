#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
from typing import Any, Callable

from source_host_contracts import load_contract, network_policy, policy_sha256
from validate_distribution import find_tool, read_signing_fingerprint


SHA256 = re.compile(r"^[0-9a-f]{64}$")
EXACT_HOST = re.compile(
    r"^(?=.{1,253}$)(?![0-9.]+$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)*"
    r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$"
)
KNOWN_CAPABILITIES = {
    "external_link",
    "eyny_challenge_proof",
    "ptt_adult_consent_status",
    "resource_read",
}


class MetadataBuildError(ValueError):
    pass


def canonical(value: object) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False
    ).encode("utf-8")


def expiry(now: dt.datetime, days: int) -> str:
    return (now + dt.timedelta(days=days)).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def load_json(path: Path, label: str) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise MetadataBuildError(f"invalid {label}") from exc


def catalog_network_policies(
    catalog: dict[str, Any],
    contract_path: Path | None = None,
) -> dict[str, dict[str, Any]]:
    """Return the full, canonical policy for every catalog Source.

    Targets carry this object in addition to its digest so a Host does not need a
    code-owned package catalog to reconstruct runtime authority.
    """
    releases = catalog.get("releases")
    if not isinstance(releases, list) or not releases:
        raise MetadataBuildError("release catalog has no policy-bearing releases")
    if contract_path is None:
        root = catalog.get("_root")
        if not isinstance(root, str):
            raise MetadataBuildError("release catalog is not bound to a reviewed host contract")
        contract_path = Path(root) / "source-host-contracts.json"
    try:
        contract = load_contract(contract_path)
    except ValueError as exc:
        raise MetadataBuildError("invalid reviewed host contract") from exc
    reviewed_sources = {source["id"]: source for source in contract["sources"]}
    policies: dict[str, dict[str, Any]] = {}
    for release in releases:
        sources = release.get("sources") if isinstance(release, dict) else None
        if not isinstance(sources, list) or not sources:
            raise MetadataBuildError("release catalog entry has no Sources")
        for source in sources:
            if not isinstance(source, dict):
                raise MetadataBuildError("release catalog Source is invalid")
            source_id = source.get("id")
            hosts = source.get("exactHosts")
            capabilities = source.get("namedCapabilities")
            expected_hash = source.get("policyHash")
            if not isinstance(source_id, str) or not source_id or source_id in policies:
                raise MetadataBuildError("release catalog Source ID is invalid or duplicated")
            if (
                not isinstance(hosts, list) or not hosts or len(hosts) > 32
                or hosts != sorted(set(hosts))
                or any(not isinstance(host, str) or not EXACT_HOST.fullmatch(host) for host in hosts)
            ):
                raise MetadataBuildError(f"invalid exactHosts for {source_id}")
            if (
                not isinstance(capabilities, list) or not capabilities or len(capabilities) > 16
                or capabilities != sorted(set(capabilities))
                or any(value not in KNOWN_CAPABILITIES for value in capabilities)
            ):
                raise MetadataBuildError(f"invalid namedCapabilities for {source_id}")
            reviewed = reviewed_sources.get(source_id)
            if reviewed is None or source.get("policyVersion") != 2:
                raise MetadataBuildError(f"Source policy is not reviewed v2: {source_id}")
            if hosts != reviewed["surfaces"]["request"]["exactHttpsHosts"]:
                raise MetadataBuildError(f"catalog request hosts diverge from contract: {source_id}")
            if capabilities != reviewed["namedCapabilities"]:
                raise MetadataBuildError(f"catalog capabilities diverge from contract: {source_id}")
            policy = network_policy(reviewed)
            actual_hash = policy_sha256(reviewed)
            if not isinstance(expected_hash, str) or actual_hash != expected_hash:
                raise MetadataBuildError(f"catalog policyHash mismatch for {source_id}")
            policies[source_id] = policy
    if set(policies) != set(reviewed_sources):
        raise MetadataBuildError("release catalog/host contract Source set mismatch")
    return policies


def public_der(private_key: Path) -> bytes:
    result = subprocess.run(
        ["openssl", "pkey", "-in", str(private_key), "-pubout", "-outform", "DER"],
        check=False,
        capture_output=True,
        timeout=20,
    )
    if result.returncode != 0:
        raise MetadataBuildError("invalid TUF private key")
    inspected = subprocess.run(
        ["openssl", "pkey", "-pubin", "-inform", "DER", "-text_pub", "-noout"],
        input=result.stdout,
        check=False,
        capture_output=True,
        timeout=20,
    )
    if inspected.returncode != 0 or b"ASN1 OID: prime256v1" not in inspected.stdout:
        raise MetadataBuildError("TUF private key must use ECDSA P-256")
    return result.stdout


def key_id(private_key: Path) -> str:
    return hashlib.sha256(public_der(private_key)).hexdigest()


def sign(signed: dict[str, Any], private_keys: list[Path]) -> dict[str, Any]:
    payload = canonical(signed)
    signatures = []
    for private_key in private_keys:
        result = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", str(private_key)],
            input=payload,
            check=False,
            capture_output=True,
            timeout=20,
        )
        if result.returncode != 0:
            raise MetadataBuildError("TUF metadata signing failed")
        signatures.append({
            "keyid": key_id(private_key),
            "sig": base64.b64encode(result.stdout).decode("ascii"),
        })
    return {"signed": signed, "signatures": signatures}


def descriptor(raw: bytes, version: int) -> dict[str, Any]:
    return {"version": version, "length": len(raw), "hashes": {"sha256": hashlib.sha256(raw).hexdigest()}}


def current_versions(metadata: Path) -> tuple[int, int, int]:
    timestamp = metadata / "timestamp.json"
    if not timestamp.is_file():
        return 0, 0, 0
    value = load_json(timestamp, "timestamp metadata")
    try:
        timestamp_version = value["signed"]["version"]
        snapshot_version = value["signed"]["meta"]["snapshot.json"]["version"]
        snapshot = load_json(metadata / f"{snapshot_version}.snapshot.json", "snapshot metadata")
        targets_version = snapshot["signed"]["meta"]["targets.json"]["version"]
    except (KeyError, TypeError) as exc:
        raise MetadataBuildError("existing metadata version chain is invalid") from exc
    versions = (targets_version, snapshot_version, timestamp_version)
    if any(not isinstance(item, int) or isinstance(item, bool) or item < 1 for item in versions):
        raise MetadataBuildError("existing metadata versions are invalid")
    return versions


def root_role_ids(root: dict[str, Any], role: str) -> list[str]:
    try:
        role_policy = root["signed"]["roles"][role]
        ids = role_policy["keyids"]
        threshold = role_policy["threshold"]
    except (KeyError, TypeError) as exc:
        raise MetadataBuildError(f"root role is missing: {role}") from exc
    if not isinstance(ids, list) or len(ids) != len(set(ids)) or threshold != len(ids):
        raise MetadataBuildError(f"root role must use all configured keys: {role}")
    return ids


def validate_key_set(root: dict[str, Any], role: str, keys: list[Path]) -> None:
    expected = root_role_ids(root, role)
    actual = [key_id(path) for path in keys]
    if actual != expected:
        raise MetadataBuildError(f"{role} private keys do not match the reviewed root ordering")


def generate(
    distribution: Path,
    output: Path,
    policy_path: Path,
    root_path: Path,
    catalog_path: Path,
    targets_keys: list[Path],
    snapshot_key: Path,
    timestamp_key: Path,
    apksigner: str,
    *,
    now: dt.datetime | None = None,
    signer_reader: Callable[[str, str], str] = read_signing_fingerprint,
) -> None:
    if output.exists() and any(output.iterdir()):
        raise MetadataBuildError("trusted metadata output must be empty")
    if len(targets_keys) != 2:
        raise MetadataBuildError("targets role requires exactly two keys")
    root = load_json(root_path, "production root")
    policy = load_json(policy_path, "admission policy")
    catalog = load_json(catalog_path, "release catalog")
    if policy.get("trustedRepository", {}).get("provisioned") is not True:
        raise MetadataBuildError("admission policy trust is not provisioned")
    validate_key_set(root, "targets", targets_keys)
    validate_key_set(root, "snapshot", [snapshot_key])
    validate_key_set(root, "timestamp", [timestamp_key])

    index = load_json(distribution / "index.json", "distribution index")
    if not isinstance(index, list):
        raise MetadataBuildError("distribution index must be an array")
    releases = policy.get("releases")
    if not isinstance(releases, dict) or {item.get("pkg") for item in index} != set(releases):
        raise MetadataBuildError("distribution and admission package sets differ")
    repository = catalog.get("repository")
    if not isinstance(repository, dict) or set(repository) != {"name", "description", "iconUrl", "website"}:
        raise MetadataBuildError("release catalog repository metadata is invalid")
    network_policies = catalog_network_policies(
        catalog,
        catalog_path.parent / "source-host-contracts.json",
    )

    old_targets, old_snapshot, old_timestamp = current_versions(distribution / "metadata")
    targets_version, snapshot_version, timestamp_version = (
        old_targets + 1, old_snapshot + 1, old_timestamp + 1
    )
    now = now or dt.datetime.now(dt.timezone.utc)
    if now.tzinfo is None:
        raise MetadataBuildError("metadata clock must be timezone-aware")
    metadata = output / "metadata"
    target_dir = output / "targets" / "apk"
    metadata.mkdir(parents=True, exist_ok=True)
    target_dir.mkdir(parents=True, exist_ok=True)
    if (distribution / "metadata").is_dir():
        for path in (distribution / "metadata").glob("*.json"):
            if path.name != "timestamp.json":
                shutil.copy2(path, metadata / path.name)

    target_descriptors: dict[str, Any] = {}
    for item in sorted(index, key=lambda value: value["pkg"]):
        package = item["pkg"]
        release = releases[package]
        apk_name = item.get("apkName")
        if not isinstance(apk_name, str) or Path(apk_name).name != apk_name:
            raise MetadataBuildError(f"unsafe APK name: {package}")
        apk = distribution / "apk" / apk_name
        if not apk.is_file():
            raise MetadataBuildError(f"missing distribution APK: {package}")
        value = apk.read_bytes()
        if hashlib.sha256(value).hexdigest() != item.get("sha256"):
            raise MetadataBuildError(f"distribution APK digest mismatch: {package}")
        signer = re.sub(r"[^0-9A-Fa-f]", "", signer_reader(str(apk), apksigner)).lower()
        pins = [str(pin).lower() for pin in release.get("signerPins", [])]
        if not SHA256.fullmatch(signer) or not pins or signer not in pins or len(pins) != len(set(pins)):
            raise MetadataBuildError(f"APK signer is not authorized by policy: {package}")
        shutil.copy2(apk, target_dir / apk_name)
        sources = []
        for source in release["sources"]:
            source_id = source.get("id") if isinstance(source, dict) else None
            network_policy = network_policies.get(source_id)
            if network_policy is None:
                raise MetadataBuildError(f"Source has no catalog network policy: {source_id}")
            policy_hash = source.get("policyHash")
            if policy_hash != hashlib.sha256(canonical(network_policy)).hexdigest():
                raise MetadataBuildError(f"admission/catalog policy mismatch for {source_id}")
            sources.append({
                **{key: source[key] for key in (
                    "id", "service", "protocol", "policyHash", "name", "lang", "baseUrl"
                )},
                "networkPolicy": network_policy,
            })
        target_descriptors[f"apk/{apk_name}"] = {
            "length": len(value),
            "hashes": {"sha256": hashlib.sha256(value).hexdigest()},
            "custom": {
                "packageName": package,
                "versionCode": item["versionCode"],
                "versionName": item["versionName"],
                "name": release["name"],
                "lang": item["lang"],
                "lineageRootSha256": signer,
                "apkSignerPins": pins,
                "sources": sources,
            },
        }

    targets_signed = {
        "_type": "targets", "specVersion": "1.0", "version": targets_version,
        "expires": expiry(now, 365), "targets": target_descriptors,
        "custom": {"repository": repository},
    }
    targets_raw = canonical(sign(targets_signed, targets_keys))
    (metadata / f"{targets_version}.targets.json").write_bytes(targets_raw)
    snapshot_signed = {
        "_type": "snapshot", "specVersion": "1.0", "version": snapshot_version,
        "expires": expiry(now, 180),
        "meta": {"targets.json": descriptor(targets_raw, targets_version)},
    }
    snapshot_raw = canonical(sign(snapshot_signed, [snapshot_key]))
    (metadata / f"{snapshot_version}.snapshot.json").write_bytes(snapshot_raw)
    timestamp_signed = {
        "_type": "timestamp", "specVersion": "1.0", "version": timestamp_version,
        "expires": expiry(now, 90),
        "meta": {"snapshot.json": descriptor(snapshot_raw, snapshot_version)},
    }
    (metadata / "timestamp.json").write_bytes(canonical(sign(timestamp_signed, [timestamp_key])))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--distribution", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--policy", type=Path, required=True)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--targets-key", type=Path, action="append", required=True)
    parser.add_argument("--snapshot-key", type=Path, required=True)
    parser.add_argument("--timestamp-key", type=Path, required=True)
    parser.add_argument("--apksigner", default=None)
    args = parser.parse_args()
    try:
        generate(
            args.distribution.resolve(), args.output.resolve(), args.policy.resolve(),
            args.root.resolve(), args.catalog.resolve(), args.targets_key,
            args.snapshot_key, args.timestamp_key,
            args.apksigner or find_tool("APKSIGNER", ("apksigner",)),
        )
    except (OSError, subprocess.SubprocessError, MetadataBuildError, ValueError) as exc:
        print(f"trusted metadata generation failed: {exc}", file=sys.stderr)
        return 2
    print("generated threshold-signed production repository metadata")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
