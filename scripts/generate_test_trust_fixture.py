#!/usr/bin/env python3
"""Generate a complete ephemeral TUF-style repository for local emulator E2E only."""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import json
import os
import shutil
import subprocess
from pathlib import Path

from release_catalog import load_catalog, metadata_for_release, releases_by_module
from validate_distribution import find_tool, read_apk_metadata, read_signing_fingerprint
from validate_release_bundles import module_from_apk_name


def canonical(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()


def expiry(now: dt.datetime, days: int) -> str:
    return (now + dt.timedelta(days=days)).replace(microsecond=0).isoformat().replace("+00:00", "Z")


class FixtureKeys:
    def __init__(self, root: Path, *, reuse: bool = False):
        self.root = root
        root.mkdir(parents=True, exist_ok=reuse)
        root.chmod(0o700)
        counts = {"root": 2, "targets": 2, "snapshot": 1, "timestamp": 1}
        self.roles = {}
        for role, count in counts.items():
            if reuse:
                public = [root / f"{role}-{index}.der" for index in range(count)]
                if any(not path.is_file() or not path.with_suffix(".pem").is_file() for path in public):
                    raise ValueError(f"incomplete reusable fixture keys: {role}")
                self.roles[role] = [hashlib.sha256(path.read_bytes()).hexdigest() for path in public]
            else:
                self.roles[role] = [self._create(f"{role}-{index}") for index in range(count)]

    def _create(self, name: str) -> str:
        private = self.root / f"{name}.pem"
        public = self.root / f"{name}.der"
        subprocess.run(
            ["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(private)],
            check=True, capture_output=True,
        )
        subprocess.run(
            ["openssl", "pkey", "-in", str(private), "-pubout", "-outform", "DER", "-out", str(public)],
            check=True, capture_output=True,
        )
        private.chmod(0o600)
        public.chmod(0o600)
        return hashlib.sha256(public.read_bytes()).hexdigest()

    def public_record(self, keyid: str) -> dict:
        public = next(path for path in self.root.glob("*.der") if hashlib.sha256(path.read_bytes()).hexdigest() == keyid)
        return {
            "keytype": "ecdsa",
            "scheme": "ecdsa-sha2-nistp256",
            "keyval": {"public": base64.b64encode(public.read_bytes()).decode()},
        }

    def private(self, keyid: str) -> Path:
        public = next(path for path in self.root.glob("*.der") if hashlib.sha256(path.read_bytes()).hexdigest() == keyid)
        return public.with_suffix(".pem")

    def envelope(self, signed: dict, role: str) -> dict:
        payload = canonical(signed)
        signatures = []
        for keyid in self.roles[role]:
            result = subprocess.run(
                ["openssl", "dgst", "-sha256", "-sign", str(self.private(keyid))],
                input=payload, check=True, capture_output=True,
            )
            signatures.append({"keyid": keyid, "sig": base64.b64encode(result.stdout).decode()})
        return {"signed": signed, "signatures": signatures}


def descriptor(raw: bytes, version: int) -> dict:
    return {"version": version, "length": len(raw), "hashes": {"sha256": hashlib.sha256(raw).hexdigest()}}


def generate(apk_dir: Path, output: Path, *, aapt: str, apksigner: str, reuse_keys: bool = False) -> None:
    if output.exists() and any(output.iterdir()) and not reuse_keys:
        raise ValueError(f"fixture output must be empty: {output}")
    output.mkdir(parents=True, exist_ok=True)
    output.chmod(0o700)
    metadata = output / "metadata"
    targets = output / "targets" / "apk"
    metadata.mkdir(exist_ok=reuse_keys)
    targets.mkdir(parents=True, exist_ok=reuse_keys)
    keys = FixtureKeys(output / "keys", reuse=reuse_keys)
    catalog = load_catalog()
    releases = releases_by_module(catalog)
    target_descriptors = {}
    seen_modules = set()
    for apk in sorted(apk_dir.glob("*.apk")):
        module = module_from_apk_name(apk.name, catalog)
        release = releases[module]
        seen_modules.add(module)
        target = targets / apk.name
        shutil.copy2(apk, target)
        value = target.read_bytes()
        apk_metadata = read_apk_metadata(str(target), aapt)
        signer = read_signing_fingerprint(str(target), apksigner).lower()
        if apk_metadata["pkg"] != release["package"]:
            raise ValueError(f"package mismatch: {module}")
        release_metadata = metadata_for_release(catalog, release)
        display_by_id = {source["id"]: source for source in release_metadata["sources"]}
        languages = {source["lang"] for source in release_metadata["sources"]}
        target_descriptors[f"apk/{apk.name}"] = {
            "length": len(value),
            "hashes": {"sha256": hashlib.sha256(value).hexdigest()},
            "custom": {
                "packageName": release["package"],
                "versionCode": apk_metadata["versionCode"],
                "versionName": apk_metadata["versionName"],
                "name": release_metadata["name"],
                "lang": next(iter(languages)) if len(languages) == 1 else "",
                "lineageRootSha256": signer,
                "apkSignerPins": [signer],
                "sources": [{
                    "id": source["id"],
                    "service": source["service"],
                    "protocol": source["protocol"],
                    "policyHash": source["policyHash"],
                    "name": display_by_id[source["id"]]["name"],
                    "lang": display_by_id[source["id"]]["lang"],
                    "baseUrl": display_by_id[source["id"]]["baseUrl"],
                } for source in release["sources"]],
            },
        }
    if seen_modules != set(releases):
        raise ValueError(f"incomplete APK set: {sorted(seen_modules)}")

    now = dt.datetime.now(dt.timezone.utc)
    all_ids = [item for values in keys.roles.values() for item in values]
    if reuse_keys:
        root_raw = (metadata / "root.json").read_bytes()
    else:
        root_signed = {
            "_type": "root", "specVersion": "1.0", "version": 1,
            "expires": expiry(now, 365), "consistentSnapshot": True,
            "keys": {keyid: keys.public_record(keyid) for keyid in all_ids},
            "roles": {
                role: {"keyids": ids, "threshold": 2 if role in {"root", "targets"} else 1}
                for role, ids in keys.roles.items()
            },
        }
        root_raw = canonical(keys.envelope(root_signed, "root"))
        (metadata / "root.json").write_bytes(root_raw)
        (metadata / "1.root.json").write_bytes(root_raw)

    targets_signed = {
        "_type": "targets", "specVersion": "1.0", "version": 1,
        "expires": expiry(now, 30), "targets": target_descriptors,
        "custom": {"repository": catalog["repository"]},
    }
    targets_raw = canonical(keys.envelope(targets_signed, "targets"))
    (metadata / "1.targets.json").write_bytes(targets_raw)
    snapshot_signed = {
        "_type": "snapshot", "specVersion": "1.0", "version": 1,
        "expires": expiry(now, 7), "meta": {"targets.json": descriptor(targets_raw, 1)},
    }
    snapshot_raw = canonical(keys.envelope(snapshot_signed, "snapshot"))
    (metadata / "1.snapshot.json").write_bytes(snapshot_raw)
    timestamp_signed = {
        "_type": "timestamp", "specVersion": "1.0", "version": 1,
        "expires": expiry(now, 1), "meta": {"snapshot.json": descriptor(snapshot_raw, 1)},
    }
    (metadata / "timestamp.json").write_bytes(canonical(keys.envelope(timestamp_signed, "timestamp")))
    manifest = {
        "fixtureOnly": True,
        "rootSha256": hashlib.sha256(root_raw).hexdigest(),
        "packagePins": {
            item["custom"]["packageName"]: item["custom"]["apkSignerPins"]
            for item in target_descriptors.values()
        },
    }
    (output / "fixture.json").write_bytes(canonical(manifest) + b"\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk_dir", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--aapt", default=os.environ.get("AAPT"))
    parser.add_argument("--apksigner", default=os.environ.get("APKSIGNER"))
    parser.add_argument("--reuse-keys", action="store_true")
    args = parser.parse_args()
    generate(
        args.apk_dir.resolve(), args.output.resolve(),
        aapt=args.aapt or find_tool("AAPT", ("aapt", "aapt2")),
        apksigner=args.apksigner or find_tool("APKSIGNER", ("apksigner",)),
        reuse_keys=args.reuse_keys,
    )
    print(args.output.resolve())


if __name__ == "__main__":
    main()
