from __future__ import annotations

import base64
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from bootstrap_production_root import bootstrap
from generate_trusted_metadata import (
    MetadataBuildError,
    canonical,
    catalog_network_policies,
    generate,
    key_id,
)
from materialize_tuf_role_key import RoleKeyError, parse
from manual_policy_maintenance_admission import catalog_hashes, policy_sources
from release_catalog import load_catalog
from release_catalog import metadata_for_release
from source_host_contracts import DEFAULT_CONTRACT_PATH


def make_key(root: Path, name: str) -> Path:
    path = root / f"{name}.pem"
    subprocess.run(
        ["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(path)],
        check=True,
        capture_output=True,
    )
    path.chmod(0o600)
    return path


def destination_root() -> Path:
    return Path(
        os.environ.get(
            "EXTENSIONS_REPO_DIR",
            str(Path(__file__).resolve().parents[2] / "extensions"),
        )
    ).resolve()


def load_destination_verifier():
    destination = destination_root()
    verifier_path = destination / "policy" / "trusted_metadata.py"
    if not verifier_path.is_file():
        raise unittest.SkipTest(
            "destination verifier checkout unavailable; set EXTENSIONS_REPO_DIR for cross-repo validation"
        )
    spec = importlib.util.spec_from_file_location(
        "destination_trusted_metadata", verifier_path
    )
    if spec is None or spec.loader is None:
        raise AssertionError(f"cannot load destination verifier: {verifier_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ProductionTrustedMetadataTest(unittest.TestCase):
    def test_destination_admission_policy_matches_merged_catalog_contract(self) -> None:
        destination = destination_root()
        policy_path = destination / "policy" / "admission_policy.json"
        if not policy_path.is_file():
            raise unittest.SkipTest(
                "destination policy checkout unavailable; set EXTENSIONS_REPO_DIR for cross-repo validation"
            )
        candidate = policy_sources(json.loads(policy_path.read_text(encoding="utf-8")))
        expected = catalog_hashes(
            json.loads(Path("release-catalog.json").read_text(encoding="utf-8")),
            DEFAULT_CONTRACT_PATH,
        )
        self.assertEqual(set(expected), set(candidate))
        self.assertEqual(
            expected,
            {source_id: source["policyHash"] for source_id, source in candidate.items()},
        )

    def test_merged_catalog_contract_v2_targets_pass_destination_verifier(self) -> None:
        catalog = load_catalog()
        policies = catalog_network_policies(catalog, DEFAULT_CONTRACT_PATH)
        verifier = load_destination_verifier()
        validated_sources: set[str] = set()

        for release in catalog["releases"]:
            metadata = metadata_for_release(catalog, release)
            metadata_sources = {source["id"]: source for source in metadata["sources"]}
            target_sources = []
            for catalog_source in release["sources"]:
                source_id = catalog_source["id"]
                source_metadata = metadata_sources[source_id]
                target_sources.append({
                    "id": source_id,
                    "service": catalog_source["service"],
                    "protocol": catalog_source["protocol"],
                    "policyHash": catalog_source["policyHash"],
                    "networkPolicy": policies[source_id],
                    "name": source_metadata["name"],
                    "lang": source_metadata["lang"],
                    "baseUrl": source_metadata["baseUrl"],
                })
                validated_sources.add(source_id)
            verifier._check_target_custom({
                "packageName": release["package"],
                "versionCode": 1,
                "versionName": "production-policy-fixture",
                "name": metadata["name"],
                "lang": target_sources[0]["lang"],
                "lineageRootSha256": "1" * 64,
                "apkSignerPins": ["1" * 64],
                "sources": target_sources,
            }, f"apk/{release['module']}-production-policy-fixture.apk")

        self.assertEqual(set(policies), validated_sources)
        self.assertEqual(13, len(validated_sources))

    def test_catalog_network_policy_is_fail_closed(self) -> None:
        catalog = load_catalog()
        policies = catalog_network_policies(catalog, DEFAULT_CONTRACT_PATH)
        self.assertEqual(13, len(policies))

        for field, value in (
            ("exactHosts", ["*.example.com"]),
            ("namedCapabilities", ["raw_socket"]),
            ("policyHash", "00" * 32),
        ):
            invalid = json.loads(json.dumps(catalog))
            invalid["releases"][0]["sources"][0][field] = value
            with self.subTest(field=field):
                with self.assertRaises(MetadataBuildError):
                    catalog_network_policies(invalid, DEFAULT_CONTRACT_PATH)

    def test_root_bootstrap_requires_distinct_two_of_two_roles(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            keys = [make_key(root, name) for name in (
                "root-a", "root-b", "targets-a", "targets-b", "snapshot", "timestamp"
            )]
            output = root / "root.json"
            envelope = bootstrap(
                output, keys[:2], keys[2:4], keys[4], keys[5],
                now=dt.datetime(2026, 8, 15, tzinfo=dt.timezone.utc),
            )
            self.assertEqual(2, envelope["signed"]["roles"]["root"]["threshold"])
            self.assertEqual(2, envelope["signed"]["roles"]["targets"]["threshold"])
            self.assertEqual(2, len(envelope["signatures"]))
            self.assertEqual(6, len(envelope["signed"]["keys"]))
            with self.assertRaises(MetadataBuildError):
                bootstrap(root / "duplicate.json", [keys[0], keys[0]], keys[2:4], keys[4], keys[5])

    def test_role_key_bundle_is_exact_and_bound_to_public_key_id(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            key = make_key(Path(temporary), "targets-a")
            keyid = key_id(key)
            payload = {
                "schemaVersion": 1,
                "bundleType": "tuf-role-key",
                "role": "targets",
                "slot": "a",
                "keyId": keyid,
                "privateKeyPem": key.read_text(),
            }
            self.assertEqual(key.read_bytes(), parse(json.dumps(payload).encode(), "targets", "a", keyid))
            payload["slot"] = "b"
            with self.assertRaises(RoleKeyError):
                parse(json.dumps(payload).encode(), "targets", "a", keyid)

    def test_generator_emits_threshold_metadata_and_exact_targets(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            distribution = root / "distribution"
            output = root / "output"
            keys = root / "keys"
            keys.mkdir()
            targets = [make_key(keys, "targets-a"), make_key(keys, "targets-b")]
            snapshot = make_key(keys, "snapshot")
            timestamp = make_key(keys, "timestamp")
            signer = "ab" * 32
            package = "tw.kevinzhang.newshub.extension.test"
            apk_name = "newshub-test-v1.0.apk"
            apk = distribution / "apk" / apk_name
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b"signed apk fixture")
            index = [{
                "pkg": package, "name": "Test", "versionCode": 1, "versionName": "1.0",
                "lang": "en", "apkName": apk_name, "iconName": "test.png",
                "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                "sources": [{"id": "test", "name": "Test", "lang": "en", "baseUrl": "https://example.com"}],
            }]
            (distribution / "index.json").write_text(json.dumps(index))
            source = {
                "id": "test", "service": "TestService", "protocol": 1,
                "policyHash": "cd" * 32, "name": "Test", "lang": "en",
                "baseUrl": "https://example.com",
            }
            policy = {
                "trustedRepository": {"provisioned": True},
                "releases": {package: {"name": "Test", "signerPins": [signer], "sources": [source]}},
            }
            policy_path = root / "policy.json"
            policy_path.write_text(json.dumps(policy))
            catalog_path = root / "catalog.json"
            network_policy = {
                "schemaVersion": 2,
                "request": {"rules": [{
                    "exactHosts": ["example.com"],
                    "operation": {
                        "name": "source_read", "methods": ["GET", "HEAD"],
                        "pathPrefixes": ["/"], "credentialed": True,
                    },
                }]},
                "resource": {"exactHosts": ["example.com"]},
                "external": {"exactHosts": ["example.com"]},
                "auth": {"exactHosts": []},
                "namedCapabilities": ["external_link", "resource_read"],
            }
            source["policyHash"] = hashlib.sha256(
                json.dumps(
                    network_policy, ensure_ascii=False, sort_keys=True,
                    separators=(",", ":"), allow_nan=False,
                ).encode("utf-8")
            ).hexdigest()
            policy_path.write_text(json.dumps(policy))
            catalog_path.write_text(json.dumps({
                "repository": {
                    "name": "Test", "description": "Test",
                    "iconUrl": "https://example.com/icon.png", "website": "https://example.com",
                },
                "releases": [{"sources": [{
                    "id": "test", "policyHash": source["policyHash"],
                    "policyVersion": 2,
                    "exactHosts": ["example.com"],
                    "namedCapabilities": ["external_link", "resource_read"],
                }]}],
            }))
            (root / "source-host-contracts.json").write_text(json.dumps({
                "schemaVersion": 1,
                "sources": [{
                    "id": "test",
                    "module": "test",
                    "namedCapabilities": ["external_link", "resource_read"],
                    "surfaces": {
                        "request": {
                            "exactHttpsHosts": ["example.com"],
                            "blockedHttpHosts": [],
                            "dynamicFromContent": False,
                            "evidence": ["policy.json"],
                            "rules": [{
                                "exactHttpsHosts": ["example.com"],
                                "methods": ["GET", "HEAD"],
                                "pathPrefixes": ["/"],
                                "credentialed": True,
                            }],
                        },
                        "resource": {
                            "exactHttpsHosts": ["example.com"],
                            "dynamicFromContent": False,
                            "evidence": ["policy.json"],
                        },
                        "external": {
                            "exactHttpsHosts": ["example.com"],
                            "dynamicFromContent": False,
                            "evidence": ["policy.json"],
                        },
                        "auth": {
                            "exactHttpsHosts": [],
                            "dynamicFromContent": False,
                            "evidence": ["policy.json"],
                        },
                    },
                }],
            }))
            root_path = root / "root.json"
            root_path.write_text(json.dumps({"signed": {"roles": {
                "targets": {"keyids": [key_id(item) for item in targets], "threshold": 2},
                "snapshot": {"keyids": [key_id(snapshot)], "threshold": 1},
                "timestamp": {"keyids": [key_id(timestamp)], "threshold": 1},
            }}, "signatures": []}))
            generate(
                distribution, output, policy_path, root_path, catalog_path, targets,
                snapshot, timestamp, "unused",
                now=dt.datetime(2026, 8, 15, tzinfo=dt.timezone.utc),
                signer_reader=lambda _path, _tool: signer,
            )
            targets_envelope = json.loads((output / "metadata/1.targets.json").read_text())
            self.assertEqual(2, len(targets_envelope["signatures"]))
            self.assertEqual(
                {key_id(item) for item in targets},
                {item["keyid"] for item in targets_envelope["signatures"]},
            )
            target = targets_envelope["signed"]["targets"][f"apk/{apk_name}"]
            self.assertEqual([signer], target["custom"]["apkSignerPins"])
            self.assertEqual(
                network_policy,
                target["custom"]["sources"][0]["networkPolicy"],
            )
            self.assertEqual(
                hashlib.sha256(
                    json.dumps(
                        target["custom"]["sources"][0]["networkPolicy"],
                        ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False,
                    ).encode("utf-8")
                ).hexdigest(),
                target["custom"]["sources"][0]["policyHash"],
            )
            self.assertEqual(apk.read_bytes(), (output / "targets/apk" / apk_name).read_bytes())
            self.assertTrue((output / "metadata/1.snapshot.json").is_file())
            self.assertTrue((output / "metadata/timestamp.json").is_file())


if __name__ == "__main__":
    unittest.main()
