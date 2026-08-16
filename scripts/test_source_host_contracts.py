from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from release_catalog import load_catalog
from source_host_contracts import DEFAULT_CATALOG_PATH, audit, load_contract, policy_sha256


ROOT = Path(__file__).resolve().parents[1]


def canonical_policy(source: dict) -> str:
    policy = {
        "exactHosts": source["exactHosts"],
        "operations": [{
            "name": "source_read",
            "methods": ["GET", "HEAD"],
            "pathPrefixes": ["/"],
            "credentialed": True,
        }],
        "namedCapabilities": source["namedCapabilities"],
    }
    return hashlib.sha256(
        json.dumps(policy, sort_keys=True, separators=(",", ":")).encode("utf-8"),
    ).hexdigest()


class SourceHostContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.contract = load_contract(catalog_path=DEFAULT_CATALOG_PATH)

    def findings(self, contract=None):
        return {(item.source_id, item.code): item.hosts for item in audit(contract or self.contract)}

    def write_contract(self, value: dict) -> Path:
        payload = {key: item for key, item in value.items() if not key.startswith("_")}
        handle = tempfile.NamedTemporaryFile(
            mode="w", suffix=".json", dir=ROOT, delete=False, encoding="utf-8",
        )
        with handle:
            json.dump(payload, handle)
        self.addCleanup(Path(handle.name).unlink)
        return Path(handle.name)

    def write_catalog(self, value: dict) -> Path:
        payload = {key: item for key, item in value.items() if not key.startswith("_")}
        handle = tempfile.NamedTemporaryFile(
            mode="w", suffix=".json", dir=ROOT, delete=False, encoding="utf-8",
        )
        with handle:
            json.dump(payload, handle)
        self.addCleanup(Path(handle.name).unlink)
        return Path(handle.name)

    def test_inventory_covers_all_thirteen_catalog_sources(self) -> None:
        self.assertEqual(13, len(self.contract["sources"]))
        self.assertEqual(
            set(self.contract["_catalogSources"]),
            {source["id"] for source in self.contract["sources"]},
        )

    def test_source_ids_are_bound_to_their_reviewed_request_hosts(self) -> None:
        catalog = self.contract["_catalogSources"]
        self.assertEqual(
            ["eyny.com", "www.eyny.com", "www52.eyny.com", "www53.eyny.com"],
            catalog["tw.kevinzhang.eyny"]["exactHosts"],
        )
        eyny = next(source for source in self.contract["sources"] if source["id"] == "tw.kevinzhang.eyny")
        self.assertEqual(
            "9cbfa85fd151858f5443d64b2a9d2762879d30cd54ebf4bbd3d775ac26f4839c",
            policy_sha256(eyny),
        )
        self.assertEqual(
            [
                "fenrisulfr.org", "gaia.komica1.org", "gita.komica1.org", "iris.komica1.org",
                "komica.dbfoxtw.me", "msgirls.boguspix.com", "pixmicat.alica.idv.tw",
                "sister.boguspix.com", "storysol.boguspix.com", "travel.voidfactory.com",
                "www.karlsland.net",
            ],
            catalog["tw.kevinzhang.komica.sora"]["exactHosts"],
        )
        self.assertEqual(
            ["2cat.org", "2cat.uk"],
            catalog["tw.kevinzhang.komica2.sora"]["exactHosts"],
        )
        self.assertEqual(
            ["hacker-news.firebaseio.com"],
            catalog["tw.kevinzhang.newshub.extension.hackernews"]["exactHosts"],
        )

    def test_gamer_old_policy_fails_and_new_policy_covers_board_api(self) -> None:
        current = self.findings()
        gamer_key = ("tw.kevinzhang.newshub.extension.gamer", "REQUEST_HOST_NOT_SIGNED")
        self.assertNotIn(gamer_key, current)

        old = copy.deepcopy(self.contract)
        old["_catalogSources"]["tw.kevinzhang.newshub.extension.gamer"]["exactHosts"] = [
            "forum.gamer.com.tw",
        ]
        self.assertEqual(("api.gamer.com.tw",), self.findings(old)[gamer_key])

    def test_retired_http_boards_leave_no_policy_findings(self) -> None:
        findings = self.findings()
        self.assertEqual({}, findings)

    def test_resource_or_external_literal_never_becomes_request_authority(self) -> None:
        changed = copy.deepcopy(self.contract)
        gamer = next(
            item for item in changed["sources"]
            if item["id"] == "tw.kevinzhang.newshub.extension.gamer"
        )
        gamer["surfaces"]["external"]["exactHttpsHosts"].append("unreviewed.example")
        self.assertNotIn(
            (gamer["id"], "REQUEST_HOST_NOT_SIGNED"),
            self.findings(changed),
        )
        self.assertNotIn(
            "unreviewed.example",
            changed["_catalogSources"][gamer["id"]]["exactHosts"],
        )

    def test_contract_rejects_wildcard_and_post(self) -> None:
        for field, value, message in (
            ("exactHttpsHosts", ["*.example.com"], "exact lowercase DNS hosts"),
            ("methods", ["POST"], "only GET and HEAD"),
        ):
            invalid = copy.deepcopy(self.contract)
            request = invalid["sources"][0]["surfaces"]["request"]
            if field == "methods":
                request["rules"][0][field] = value
            else:
                request[field] = value
            with self.subTest(field=field):
                with self.assertRaisesRegex(ValueError, message):
                    load_contract(self.write_contract(invalid))

    def test_catalog_rejects_unknown_capability_and_hash_mismatch(self) -> None:
        for mutation, message in (
            ("unknown-capability", "unknown namedCapabilities"),
            ("hash-mismatch", "policyHash mismatch"),
        ):
            catalog = load_catalog()
            source = catalog["releases"][0]["sources"][0]
            if mutation == "unknown-capability":
                source["namedCapabilities"] = sorted(source["namedCapabilities"] + ["raw_socket"])
                source["policyHash"] = policy_sha256(self.contract["sources"][0])
            else:
                source["policyHash"] = "00" * 32
            with self.subTest(mutation=mutation):
                with self.assertRaisesRegex(ValueError, message):
                    load_catalog(self.write_catalog(catalog))


if __name__ == "__main__":
    unittest.main()
