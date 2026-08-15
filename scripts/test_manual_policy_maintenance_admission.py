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

    def write_v2_inputs(self):
        source = {
            "id": "tw.example.source",
            "module": "example",
            "namedCapabilities": ["resource_read"],
            "surfaces": {
                "request": {
                    "exactHttpsHosts": ["api.example.com"],
                    "blockedHttpHosts": [],
                    "rules": [{
                        "exactHttpsHosts": ["api.example.com"],
                        "methods": ["GET"],
                        "pathPrefixes": ["/v1/"],
                        "credentialed": False,
                    }],
                    "dynamicFromContent": False,
                    "evidence": ["evidence.kt"],
                },
                "resource": {
                    "exactHttpsHosts": ["images.example.com"],
                    "dynamicFromContent": True,
                    "evidence": ["evidence.kt"],
                },
                "external": {
                    "exactHttpsHosts": ["www.example.com"],
                    "dynamicFromContent": True,
                    "evidence": ["evidence.kt"],
                },
                "auth": {
                    "exactHttpsHosts": ["login.example.com"],
                    "dynamicFromContent": False,
                    "evidence": ["evidence.kt"],
                },
            },
        }
        expected_hash = admission.policy_sha256(source)
        self.catalog["releases"][0]["sources"][0].update({
            "policyVersion": 2,
            "exactHosts": ["api.example.com"],
            "policyHash": expected_hash,
        })
        self.candidate_policy["releases"]["tw.example.extension"]["sources"][0][
            "policyHash"
        ] = expected_hash
        catalog = self.write_inputs()
        contract = Path(self.temp.name) / "source-host-contracts.json"
        (Path(self.temp.name) / "evidence.kt").write_text("// reviewed", encoding="utf-8")
        contract.write_text(
            json.dumps({"schemaVersion": 1, "sources": [source]}),
            encoding="utf-8",
        )
        return catalog, contract

    def test_accepts_hash_only_change_bound_to_catalog_policy(self):
        catalog = self.write_inputs()
        self.assertEqual(
            ["tw.example.source"], admission.validate(self.base, self.candidate, catalog)
        )

    def test_accepts_v2_hash_recomputed_from_strict_reviewed_contract(self):
        catalog, contract = self.write_v2_inputs()
        self.assertEqual(
            ["tw.example.source"],
            admission.validate(self.base, self.candidate, catalog, contract),
        )

    def test_production_v2_catalog_hashes_match_reviewed_contract(self):
        root = Path(__file__).resolve().parents[1]
        hashes = admission.catalog_hashes(
            admission.load_json(root / "release-catalog.json", "release catalog"),
            root / "source-host-contracts.json",
        )
        self.assertEqual(13, len(hashes))

    def test_v2_catalog_wildcard_and_contract_mismatch_fail_closed(self):
        catalog, contract = self.write_v2_inputs()
        self.catalog["releases"][0]["sources"][0]["exactHosts"] = ["*.example.com"]
        catalog.write_text(json.dumps(self.catalog), encoding="utf-8")
        with self.assertRaisesRegex(admission.AdmissionError, "diverge from reviewed contract"):
            admission.validate(self.base, self.candidate, catalog, contract)

        catalog, contract = self.write_v2_inputs()
        value = json.loads(contract.read_text(encoding="utf-8"))
        value["sources"][0]["surfaces"]["request"]["rules"][0]["exactHttpsHosts"] = [
            "*.example.com"
        ]
        contract.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(admission.AdmissionError, "reviewed source host contract is invalid"):
            admission.validate(self.base, self.candidate, catalog, contract)

    def test_cloud_build_passes_explicit_reviewed_contract_path(self):
        config = (
            Path(__file__).resolve().parents[1] / "cloudbuild/manual-policy-maintenance.yaml"
        ).read_text(encoding="utf-8")
        self.assertIn("--contract /workspace/source/source-host-contracts.json", config)

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

    def test_accepts_exact_policy_verifier_code_change(self):
        for relative in admission.VERIFIER_MAINTENANCE_PATHS:
            base = self.base / relative
            candidate = self.candidate / relative
            base.parent.mkdir(parents=True, exist_ok=True)
            candidate.parent.mkdir(parents=True, exist_ok=True)
            base.write_text("old", encoding="utf-8")
            candidate.write_text("new", encoding="utf-8")
        self.assertEqual(
            admission.VERIFIER_MAINTENANCE_PATHS,
            admission.validate_verifier_code(self.base, self.candidate),
        )

    def test_accepts_each_complete_policy_verifier_pair(self):
        for pair in admission.VERIFIER_MAINTENANCE_PAIRS:
            with self.subTest(pair=sorted(pair)):
                with tempfile.TemporaryDirectory() as temporary:
                    root = Path(temporary)
                    base = root / "base"
                    candidate = root / "candidate"
                    base.mkdir()
                    candidate.mkdir()
                    for relative in pair:
                        base_path = base / relative
                        candidate_path = candidate / relative
                        base_path.parent.mkdir(parents=True, exist_ok=True)
                        candidate_path.parent.mkdir(parents=True, exist_ok=True)
                        base_path.write_text("old", encoding="utf-8")
                        candidate_path.write_text("new", encoding="utf-8")
                    self.assertEqual(
                        sorted(pair),
                        admission.validate_verifier_code(base, candidate),
                    )

    def test_rejects_partial_policy_verifier_code_change(self):
        relative = admission.VERIFIER_MAINTENANCE_PATHS[0]
        base = self.base / relative
        candidate = self.candidate / relative
        base.parent.mkdir(parents=True, exist_ok=True)
        candidate.parent.mkdir(parents=True, exist_ok=True)
        base.write_text("old", encoding="utf-8")
        candidate.write_text("new", encoding="utf-8")
        with self.assertRaisesRegex(admission.AdmissionError, "forbidden paths"):
            admission.validate_verifier_code(self.base, self.candidate)

    def test_rejects_mismatched_policy_verifier_pair(self):
        mismatched = [pair_path for pair in admission.VERIFIER_MAINTENANCE_PAIRS for pair_path in pair]
        for relative in (mismatched[0], mismatched[-1]):
            base = self.base / relative
            candidate = self.candidate / relative
            base.parent.mkdir(parents=True, exist_ok=True)
            candidate.parent.mkdir(parents=True, exist_ok=True)
            base.write_text("old", encoding="utf-8")
            candidate.write_text("new", encoding="utf-8")
        with self.assertRaisesRegex(admission.AdmissionError, "forbidden paths"):
            admission.validate_verifier_code(self.base, self.candidate)

    def test_rejects_complete_pair_with_additional_path(self):
        for relative in (*admission.VERIFIER_MAINTENANCE_PAIRS[0], "README.md"):
            base = self.base / relative
            candidate = self.candidate / relative
            base.parent.mkdir(parents=True, exist_ok=True)
            candidate.parent.mkdir(parents=True, exist_ok=True)
            base.write_text("old", encoding="utf-8")
            candidate.write_text("new", encoding="utf-8")
        with self.assertRaisesRegex(admission.AdmissionError, "forbidden paths"):
            admission.validate_verifier_code(self.base, self.candidate)


if __name__ == "__main__":
    unittest.main()
