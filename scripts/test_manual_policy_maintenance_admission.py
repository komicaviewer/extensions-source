import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import manual_policy_maintenance_admission as admission


def canonical_hash(policy):
    return hashlib.sha256(admission.canonical(policy)).hexdigest()


class ManualPolicyMaintenanceAdmissionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.base = root / "base"
        self.candidate = root / "candidate"
        self.base.mkdir()
        self.candidate.mkdir()
        (self.base / "policy").mkdir()
        (self.candidate / "policy").mkdir()
        self.network_policy = {
            "exactHosts": ["example.com"],
            "operations": [{
                "name": "source_read",
                "methods": ["GET", "HEAD"],
                "pathPrefixes": ["/"],
                "credentialed": True,
            }],
            "namedCapabilities": ["resource_read"],
        }
        self.new_hash = canonical_hash(self.network_policy)
        self.base_policy = {
            "schemaVersion": 2,
            "expectedReleaseCount": 1,
            "expectedSourceCount": 1,
            "trustedRepository": {"provisioned": True},
            "releases": {
                "tw.example.extension": {
                    "name": "Example",
                    "signerPins": ["a" * 64],
                    "sources": [{
                        "id": "tw.example.source",
                        "name": "Example",
                        "lang": "en",
                        "baseUrl": "https://example.com",
                        "service": "tw.example.Service",
                        "protocol": 1,
                        "policyHash": "b" * 64,
                    }],
                }
            },
        }
        self.candidate_policy = copy.deepcopy(self.base_policy)
        self.candidate_policy["releases"]["tw.example.extension"]["sources"][0][
            "policyHash"
        ] = self.new_hash
        self.catalog = {
            "schemaVersion": 2,
            "releases": [{
                "packageName": "tw.example.extension",
                "sources": [{
                    "id": "tw.example.source",
                    "exactHosts": ["example.com"],
                    "namedCapabilities": ["resource_read"],
                    "policyHash": self.new_hash,
                }],
            }],
        }

    def tearDown(self):
        self.temp.cleanup()

    def write_inputs(self):
        (self.base / "policy" / "admission_policy.json").write_text(
            json.dumps(self.base_policy), encoding="utf-8"
        )
        (self.candidate / "policy" / "admission_policy.json").write_text(
            json.dumps(self.candidate_policy), encoding="utf-8"
        )
        catalog = Path(self.temp.name) / "catalog.json"
        catalog.write_text(json.dumps(self.catalog), encoding="utf-8")
        return catalog

    def test_accepts_hash_only_change_bound_to_catalog_policy(self):
        catalog = self.write_inputs()
        self.assertEqual(
            ["tw.example.source"], admission.validate(self.base, self.candidate, catalog)
        )

    def test_rejects_non_hash_authority_change(self):
        self.candidate_policy["releases"]["tw.example.extension"]["sources"][0][
            "baseUrl"
        ] = "https://evil.example"
        catalog = self.write_inputs()
        with self.assertRaisesRegex(admission.AdmissionError, "non-hash authority"):
            admission.validate(self.base, self.candidate, catalog)

    def test_rejects_hash_not_derived_from_catalog_policy(self):
        self.candidate_policy["releases"]["tw.example.extension"]["sources"][0][
            "policyHash"
        ] = "c" * 64
        catalog = self.write_inputs()
        with self.assertRaisesRegex(admission.AdmissionError, "does not match signed catalog"):
            admission.validate(self.base, self.candidate, catalog)

    def test_rejects_any_additional_changed_path(self):
        catalog = self.write_inputs()
        (self.candidate / "README.md").write_text("changed", encoding="utf-8")
        with self.assertRaisesRegex(admission.AdmissionError, "forbidden paths"):
            admission.validate(self.base, self.candidate, catalog)


if __name__ == "__main__":
    unittest.main()
