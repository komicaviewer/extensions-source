from __future__ import annotations

import base64
import datetime as dt
import hashlib
import json
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


def make_key(root: Path, name: str) -> Path:
    path = root / f"{name}.pem"
    subprocess.run(
        ["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(path)],
        check=True,
        capture_output=True,
    )
    path.chmod(0o600)
    return path


class ProductionTrustedMetadataTest(unittest.TestCase):
    def test_catalog_network_policy_is_fail_closed(self) -> None:
        policy = {
            "exactHosts": ["example.com"],
            "operations": [{
                "credentialed": True,
                "methods": ["GET", "HEAD"],
                "name": "source_read",
                "pathPrefixes": ["/"],
            }],
            "namedCapabilities": ["external_link"],
        }
        source = {
            "id": "test",
            "exactHosts": ["example.com"],
            "namedCapabilities": ["external_link"],
            "policyHash": hashlib.sha256(canonical(policy)).hexdigest(),
        }
        self.assertEqual(policy, catalog_network_policies({"releases": [{"sources": [source]}]})["test"])

        for field, value in (
            ("exactHosts", ["*.example.com"]),
            ("namedCapabilities", ["raw_socket"]),
            ("policyHash", "00" * 32),
        ):
            invalid = json.loads(json.dumps(source))
            invalid[field] = value
            with self.subTest(field=field):
                with self.assertRaises(MetadataBuildError):
                    catalog_network_policies({"releases": [{"sources": [invalid]}]})

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
                "exactHosts": ["example.com"],
                "operations": [{
                    "name": "source_read", "methods": ["GET", "HEAD"],
                    "pathPrefixes": ["/"], "credentialed": True,
                }],
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
                    "exactHosts": ["example.com"],
                    "namedCapabilities": ["external_link", "resource_read"],
                }]}],
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
