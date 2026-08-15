#!/usr/bin/env python3
"""Validate the reviewed host inventory without deriving authority from literals.

The inventory is intentionally an admission contract, not a source scanner.  A URL
literal may be evidence for a human-reviewed entry, but it can never add a host to
the signed release policy automatically.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT_PATH = REPO_ROOT / "source-host-contracts.json"
DEFAULT_CATALOG_PATH = REPO_ROOT / "release-catalog.json"
HOST = re.compile(
    r"(?=.{1,253}\Z)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+"
    r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?",
)
SOURCE_KEYS = {"id", "module", "namedCapabilities", "surfaces"}
SURFACE_KEYS = {"request", "resource", "external", "auth"}
READ_KEYS = {"exactHttpsHosts", "dynamicFromContent", "evidence"}
REQUEST_KEYS = READ_KEYS | {"rules", "blockedHttpHosts"}
REQUEST_RULE_KEYS = {"exactHttpsHosts", "methods", "pathPrefixes", "credentialed"}
KNOWN_CAPABILITIES = {
    "external_link",
    "eyny_challenge_proof",
    "ptt_adult_consent_status",
    "resource_read",
}


@dataclass(frozen=True)
class AuditFinding:
    source_id: str
    code: str
    hosts: tuple[str, ...] = ()

    def line(self) -> str:
        suffix = f": {', '.join(self.hosts)}" if self.hosts else ""
        return f"{self.source_id}\t{self.code}{suffix}"


def _read_json(path: Path, label: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"invalid {label} {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be an object")
    return value


def _hosts(value: Any, label: str) -> list[str]:
    if (
        not isinstance(value, list)
        or len(value) > 32
        or value != sorted(set(value))
        or any(not isinstance(host, str) or not HOST.fullmatch(host) for host in value)
    ):
        raise ValueError(f"{label} must be a sorted unique array of exact lowercase DNS hosts")
    return value


def _evidence(value: Any, label: str, root: Path) -> list[str]:
    if (
        not isinstance(value, list) or not value
        or value != sorted(set(value))
        or any(not isinstance(item, str) or not item for item in value)
    ):
        raise ValueError(f"{label} must be a sorted unique non-empty path array")
    for item in value:
        path = (root / item).resolve()
        if not path.is_relative_to(root) or not path.is_file():
            raise ValueError(f"{label} points outside the repository or to a missing file: {item}")
    return value


def load_contract(
    path: str | Path = DEFAULT_CONTRACT_PATH,
    catalog_path: str | Path | None = None,
) -> dict[str, Any]:
    contract_path = Path(path).resolve()
    root = contract_path.parent
    contract = _read_json(contract_path, "source host contract")
    if set(contract) != {"schemaVersion", "sources"} or contract["schemaVersion"] != 1:
        raise ValueError("source host contract must contain schemaVersion 1 and sources")
    sources = contract["sources"]
    if not isinstance(sources, list) or not sources:
        raise ValueError("source host contract sources must be a non-empty array")

    catalog_sources: dict[str, dict[str, Any]] | None = None
    if catalog_path is not None:
        from release_catalog import load_catalog

        catalog = load_catalog(catalog_path)
        catalog_sources = {
            source["id"]: source
            for release in catalog["releases"]
            for source in release["sources"]
        }
    seen: set[str] = set()
    for index, source in enumerate(sources):
        label = f"sources[{index}]"
        if not isinstance(source, dict) or set(source) != SOURCE_KEYS:
            raise ValueError(f"{label} must contain exactly {sorted(SOURCE_KEYS)}")
        source_id = source["id"]
        if not isinstance(source_id, str) or not source_id or source_id in seen:
            raise ValueError(f"{label}.id is invalid or duplicated: {source_id}")
        if catalog_sources is not None and source_id not in catalog_sources:
            raise ValueError(f"{label}.id is unknown: {source_id}")
        if catalog_sources is not None and source["module"] != catalog_sources[source_id]["module"]:
            raise ValueError(f"{source_id} module does not match release catalog")
        capabilities = source["namedCapabilities"]
        if (
            not isinstance(capabilities, list) or capabilities != sorted(set(capabilities))
            or any(value not in KNOWN_CAPABILITIES for value in capabilities)
        ):
            raise ValueError(f"{source_id}.namedCapabilities must be a sorted known capability array")
        seen.add(source_id)
        surfaces = source["surfaces"]
        if not isinstance(surfaces, dict) or set(surfaces) != SURFACE_KEYS:
            raise ValueError(f"{source_id}.surfaces must contain exactly {sorted(SURFACE_KEYS)}")
        for name, surface in surfaces.items():
            expected_keys = REQUEST_KEYS if name == "request" else READ_KEYS
            if not isinstance(surface, dict) or set(surface) != expected_keys:
                raise ValueError(f"{source_id}.{name} must contain exactly {sorted(expected_keys)}")
            _hosts(surface["exactHttpsHosts"], f"{source_id}.{name}.exactHttpsHosts")
            if not isinstance(surface["dynamicFromContent"], bool):
                raise ValueError(f"{source_id}.{name}.dynamicFromContent must be boolean")
            _evidence(surface["evidence"], f"{source_id}.{name}.evidence", root)
            if name == "request":
                _hosts(surface["blockedHttpHosts"], f"{source_id}.request.blockedHttpHosts")
                if surface["dynamicFromContent"]:
                    raise ValueError(f"{source_id}.request cannot derive authority from response content")
                rules = surface["rules"]
                if not isinstance(rules, list) or not rules or len(rules) > 32:
                    raise ValueError(f"{source_id}.request.rules must be a bounded non-empty array")
                normalized_rules = []
                for rule_index, rule in enumerate(rules):
                    rule_label = f"{source_id}.request.rules[{rule_index}]"
                    if not isinstance(rule, dict) or set(rule) != REQUEST_RULE_KEYS:
                        raise ValueError(f"{rule_label} must contain exactly {sorted(REQUEST_RULE_KEYS)}")
                    hosts = _hosts(rule["exactHttpsHosts"], f"{rule_label}.exactHttpsHosts")
                    if not hosts:
                        raise ValueError(f"{rule_label}.exactHttpsHosts must not be empty")
                    methods = rule["methods"]
                    if (
                        not isinstance(methods, list) or not methods
                        or methods != sorted(set(methods))
                        or any(method not in {"GET", "HEAD"} for method in methods)
                    ):
                        raise ValueError(f"{rule_label}.methods may contain only GET and HEAD")
                    prefixes = rule["pathPrefixes"]
                    if (
                        not isinstance(prefixes, list) or not prefixes
                        or len(prefixes) > 32
                        or prefixes != sorted(set(prefixes))
                        or any(
                            not isinstance(prefix, str) or not prefix.startswith('/') or len(prefix) > 256
                            or any(ord(character) < 0x20 or ord(character) == 0x7f for character in prefix)
                            for prefix in prefixes
                        )
                    ):
                        raise ValueError(f"{rule_label}.pathPrefixes must be bounded absolute paths")
                    if not isinstance(rule["credentialed"], bool):
                        raise ValueError(f"{rule_label}.credentialed must be boolean")
                    normalized_rules.append(json.dumps(rule, sort_keys=True, separators=(",", ":")))
                if len(normalized_rules) != len(set(normalized_rules)):
                    raise ValueError(f"{source_id}.request.rules contains duplicates")
                rule_hosts = sorted({host for rule in rules for host in rule["exactHttpsHosts"]})
                if surface["exactHttpsHosts"] != rule_hosts:
                    raise ValueError(f"{source_id}.request exact host mirror must equal its rule union")
        all_hosts = {
            host
            for surface in surfaces.values()
            for host in surface["exactHttpsHosts"]
        }
        if len(all_hosts) > 32:
            raise ValueError(f"{source_id} exceeds the combined Host limit")
    if catalog_sources is not None and seen != set(catalog_sources):
        raise ValueError(
            "source host contract/catalog mismatch: "
            f"missing={sorted(set(catalog_sources) - seen)}, extra={sorted(seen - set(catalog_sources))}",
        )
    if catalog_sources is not None:
        contract["_catalogSources"] = catalog_sources
    return contract


def network_policy(source: dict[str, Any]) -> dict[str, Any]:
    """Build v2 authority exclusively from the reviewed contract entry."""
    surfaces = source["surfaces"]
    request = surfaces["request"]
    return {
        "schemaVersion": 2,
        "request": {
            "rules": [{
                "exactHosts": rule["exactHttpsHosts"],
                "operation": {
                    "name": "source_read",
                    "methods": rule["methods"],
                    "pathPrefixes": rule["pathPrefixes"],
                    "credentialed": rule["credentialed"],
                },
            } for rule in request["rules"]],
        },
        "resource": {"exactHosts": surfaces["resource"]["exactHttpsHosts"]},
        "external": {"exactHosts": surfaces["external"]["exactHttpsHosts"]},
        "auth": {"exactHosts": surfaces["auth"]["exactHttpsHosts"]},
        "namedCapabilities": source["namedCapabilities"],
    }


def policy_sha256(source: dict[str, Any]) -> str:
    canonical = json.dumps(
        network_policy(source), ensure_ascii=False, sort_keys=True,
        separators=(",", ":"), allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def audit(contract: dict[str, Any]) -> list[AuditFinding]:
    """Compare explicit request hosts to v1 policy; never suggest or mutate policy."""
    catalog_sources = contract["_catalogSources"]
    findings: list[AuditFinding] = []
    for source in contract["sources"]:
        source_id = source["id"]
        surfaces = source["surfaces"]
        request = surfaces["request"]
        catalog_source = catalog_sources[source_id]
        policy_hosts = set(catalog_source["exactHosts"])
        missing = sorted(set(request["exactHttpsHosts"]) - policy_hosts)
        if missing:
            findings.append(AuditFinding(source_id, "REQUEST_HOST_NOT_SIGNED", tuple(missing)))
        blocked = request["blockedHttpHosts"]
        if blocked:
            findings.append(AuditFinding(source_id, "INSECURE_REQUEST_HOST", tuple(blocked)))
        undocumented = sorted(policy_hosts - set(request["exactHttpsHosts"]))
        if undocumented:
            findings.append(AuditFinding(source_id, "SIGNED_REQUEST_HOST_NOT_IN_CONTRACT", tuple(undocumented)))
        if catalog_source.get("policyVersion") != 2:
            findings.append(AuditFinding(source_id, "CATALOG_POLICY_NOT_V2"))
        if catalog_source.get("namedCapabilities") != source["namedCapabilities"]:
            findings.append(AuditFinding(source_id, "CATALOG_CAPABILITY_MISMATCH"))
        if catalog_source.get("policyHash") != policy_sha256(source):
            findings.append(AuditFinding(source_id, "POLICY_HASH_MISMATCH"))
    return findings


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--contract", default=str(DEFAULT_CONTRACT_PATH))
    parser.add_argument("--catalog", default=str(DEFAULT_CATALOG_PATH))
    parser.add_argument("--fail-on-unresolved", action="store_true")
    args = parser.parse_args()
    contract = load_contract(args.contract, args.catalog)
    findings = audit(contract)
    print(f"Source host contract validation passed: Sources={len(contract['sources'])}")
    for finding in findings:
        print(finding.line())
    if args.fail_on_unresolved and findings:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
