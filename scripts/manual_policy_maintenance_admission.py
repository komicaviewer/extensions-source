#!/usr/bin/env python3
"""Fail-closed admission for a policy-hash-only distribution maintenance PR."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

from source_host_contracts import load_contract, policy_sha256


SHA = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
REPOSITORY = "komicaviewer/extensions"
STATUS_CONTEXT = "GCP distribution admission / verify"
VERIFIER_MAINTENANCE_PAIRS = (
    frozenset(("policy/admission_gate.py", "policy/test_admission_gate.py")),
    frozenset(("policy/trusted_metadata.py", "policy/test_trusted_metadata.py")),
)
VERIFIER_MAINTENANCE_PATHS = sorted(set().union(*VERIFIER_MAINTENANCE_PAIRS))
PROTOCOL_V2_MIGRATION_BASE_SHA = "c7ae0e8ea87cc92ea497347e1912cff32cf21eb3"
PROTOCOL_V2_MIGRATION_HEAD_SHA = "20b8fd21dfe9c724b1bdab518239142818bd94ca"
PROTOCOL_V2_MIGRATION_HASHES = {
    "README.md": "3e202776b7df0d8dac25a1ac582d18caf284945ce6b5242cd0f6cfd7c6d428a0",
    "policy/README.md": "309f56a3f266935231fb5da6b9a20b64b6872b3c6994683314d70cf563f79fca",
    "policy/admission_gate.py": "5905e8f59bcd1a963bd1776aa6e53c2d7a529ece84ee5d9b7988459769cba2ec",
    "policy/admission_policy.json": "7b1a6d569c7df2ed7b7a39717af26fd95e3b5609d6c2762f226c97627435467f",
    "policy/test_trusted_metadata.py": "492858eec02aacafe317eda6af3fe0f317b7fb30014431b0b364400d8a7289b2",
    "policy/trusted_metadata.py": "646cf0f771d4cba2e1c539b36ad7c4accc00d017251896881b87be3282d0107f",
}


class AdmissionError(ValueError):
    pass


def canonical(value: object) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False
    ).encode("utf-8")


def load_json(path: Path, label: str) -> object:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise AdmissionError(f"invalid {label}") from exc


def tree_snapshot(root: Path) -> dict[str, tuple]:
    snapshot: dict[str, tuple] = {}
    for current, directories, filenames in os.walk(root, followlinks=False):
        current_path = Path(current)
        directories[:] = [name for name in directories if name != ".git"]
        for directory in list(directories):
            path = current_path / directory
            if path.is_symlink():
                snapshot[path.relative_to(root).as_posix()] = ("symlink", os.readlink(path))
                directories.remove(directory)
        for filename in filenames:
            path = current_path / filename
            relative = path.relative_to(root).as_posix()
            mode = stat.S_IMODE(path.lstat().st_mode)
            if path.is_symlink():
                snapshot[relative] = ("symlink", mode, os.readlink(path))
            elif path.is_file():
                snapshot[relative] = ("file", mode, hashlib.sha256(path.read_bytes()).hexdigest())
            else:
                snapshot[relative] = ("other", mode)
    return snapshot


def changed_paths(base: Path, candidate: Path) -> list[str]:
    base_snapshot = tree_snapshot(base)
    candidate_snapshot = tree_snapshot(candidate)
    return sorted(
        path
        for path in set(base_snapshot).union(candidate_snapshot)
        if base_snapshot.get(path) != candidate_snapshot.get(path)
    )


def catalog_hashes(
    catalog: object,
    contract_path: Path | None = None,
) -> dict[str, str]:
    if not isinstance(catalog, dict) or set(catalog) < {"schemaVersion", "releases"}:
        raise AdmissionError("release catalog has an invalid root")
    releases = catalog.get("releases")
    if not isinstance(releases, list) or not releases:
        raise AdmissionError("release catalog has no releases")
    reviewed_sources: dict[str, dict] | None = None
    if contract_path is not None:
        try:
            contract = load_contract(contract_path)
        except ValueError as exc:
            raise AdmissionError("reviewed source host contract is invalid") from exc
        reviewed_sources = {source["id"]: source for source in contract["sources"]}

    values: dict[str, str] = {}
    v2_source_ids: set[str] = set()
    for release in releases:
        if not isinstance(release, dict) or not isinstance(release.get("sources"), list):
            raise AdmissionError("release catalog entry is invalid")
        for source in release["sources"]:
            if not isinstance(source, dict):
                raise AdmissionError("release catalog source is invalid")
            source_id = source.get("id")
            expected = source.get("policyHash")
            hosts = source.get("exactHosts")
            capabilities = source.get("namedCapabilities")
            policy_version = source.get("policyVersion", 1)
            if (
                not isinstance(source_id, str)
                or source_id in values
                or not isinstance(expected, str)
                or SHA256.fullmatch(expected) is None
                or not isinstance(hosts, list)
                or not hosts
                or hosts != sorted(set(hosts))
                or not isinstance(capabilities, list)
                or capabilities != sorted(set(capabilities))
            ):
                raise AdmissionError("release catalog source policy is invalid")
            if policy_version == 1:
                policy = {
                    "exactHosts": hosts,
                    "operations": [
                        {
                            "name": "source_read",
                            "methods": ["GET", "HEAD"],
                            "pathPrefixes": ["/"],
                            "credentialed": True,
                        }
                    ],
                    "namedCapabilities": capabilities,
                }
                actual = hashlib.sha256(canonical(policy)).hexdigest()
            elif policy_version == 2:
                if reviewed_sources is None:
                    raise AdmissionError("v2 catalog requires a reviewed source host contract")
                reviewed = reviewed_sources.get(source_id)
                if reviewed is None:
                    raise AdmissionError(f"catalog Source is absent from reviewed contract: {source_id}")
                if hosts != reviewed["surfaces"]["request"]["exactHttpsHosts"]:
                    raise AdmissionError(f"catalog request hosts diverge from reviewed contract: {source_id}")
                if capabilities != reviewed["namedCapabilities"]:
                    raise AdmissionError(f"catalog capabilities diverge from reviewed contract: {source_id}")
                actual = policy_sha256(reviewed)
                v2_source_ids.add(source_id)
            else:
                raise AdmissionError(f"unsupported catalog policy version: {source_id}")
            if actual != expected:
                raise AdmissionError(f"catalog policy hash mismatch: {source_id}")
            values[source_id] = expected
    if reviewed_sources is not None and v2_source_ids != set(reviewed_sources):
        raise AdmissionError("catalog and reviewed contract Source sets differ")
    return values


def policy_sources(policy: object) -> dict[str, dict]:
    if not isinstance(policy, dict) or policy.get("schemaVersion") != 2:
        raise AdmissionError("admission policy schema is invalid")
    releases = policy.get("releases")
    if not isinstance(releases, dict) or not releases:
        raise AdmissionError("admission policy releases are invalid")
    values: dict[str, dict] = {}
    for package, release in releases.items():
        if not isinstance(package, str) or not isinstance(release, dict):
            raise AdmissionError("admission policy release is invalid")
        sources = release.get("sources")
        if not isinstance(sources, list) or not sources:
            raise AdmissionError("admission policy source list is invalid")
        for source in sources:
            if not isinstance(source, dict) or not isinstance(source.get("id"), str):
                raise AdmissionError("admission policy source is invalid")
            source_id = source["id"]
            if source_id in values:
                raise AdmissionError("duplicate admission policy source")
            values[source_id] = {"package": package, **source}
    return values


def validate(
    base: Path,
    candidate: Path,
    catalog_path: Path,
    contract_path: Path | None = None,
) -> list[str]:
    paths = changed_paths(base, candidate)
    if paths != ["policy/admission_policy.json"]:
        raise AdmissionError(f"policy maintenance changed forbidden paths: {paths}")
    base_policy = load_json(base / "policy/admission_policy.json", "base policy")
    candidate_policy = load_json(candidate / "policy/admission_policy.json", "candidate policy")
    base_sources = policy_sources(base_policy)
    candidate_sources = policy_sources(candidate_policy)
    if set(base_sources) != set(candidate_sources):
        raise AdmissionError("policy maintenance changed the Source set")
    catalog = catalog_hashes(load_json(catalog_path, "release catalog"), contract_path)
    if set(candidate_sources) != set(catalog):
        raise AdmissionError("catalog and admission policy Source sets differ")

    changed_sources: list[str] = []
    for source_id in sorted(base_sources):
        before = dict(base_sources[source_id])
        after = dict(candidate_sources[source_id])
        before_hash = before.pop("policyHash", None)
        after_hash = after.pop("policyHash", None)
        if before != after:
            raise AdmissionError(f"policy maintenance changed non-hash authority: {source_id}")
        if after_hash != before_hash:
            if after_hash != catalog[source_id]:
                raise AdmissionError(f"candidate hash does not match signed catalog: {source_id}")
            changed_sources.append(source_id)
    if not changed_sources:
        raise AdmissionError("policy maintenance does not change any policy hash")

    normalized_candidate = json.loads(json.dumps(candidate_policy))
    for release in normalized_candidate["releases"].values():
        for source in release["sources"]:
            source["policyHash"] = base_sources[source["id"]].get("policyHash")
    if normalized_candidate != base_policy:
        raise AdmissionError("policy maintenance changed fields outside Source policy hashes")
    return changed_sources


def validate_verifier_code(base: Path, candidate: Path) -> list[str]:
    paths = changed_paths(base, candidate)
    changed = frozenset(paths)
    allowed = {*VERIFIER_MAINTENANCE_PAIRS, frozenset(VERIFIER_MAINTENANCE_PATHS)}
    if changed not in allowed:
        raise AdmissionError(f"verifier maintenance changed forbidden paths: {paths}")
    return paths


def git_head(root: Path) -> str:
    try:
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=root,
            check=True,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
    except subprocess.CalledProcessError as exc:
        raise AdmissionError("protocol v2 migration input is not an exact git checkout") from exc
    return result.stdout.strip()


def validate_protocol_v2_migration(base: Path, candidate: Path) -> list[str]:
    """Admit only the already-reviewed, exact protocol-v2 distribution migration."""
    if git_head(base) != PROTOCOL_V2_MIGRATION_BASE_SHA:
        raise AdmissionError("protocol v2 migration base SHA mismatch")
    if git_head(candidate) != PROTOCOL_V2_MIGRATION_HEAD_SHA:
        raise AdmissionError("protocol v2 migration head SHA mismatch")

    paths = changed_paths(base, candidate)
    if paths != sorted(PROTOCOL_V2_MIGRATION_HASHES):
        raise AdmissionError(f"protocol v2 migration changed forbidden paths: {paths}")
    for relative, expected in PROTOCOL_V2_MIGRATION_HASHES.items():
        actual = hashlib.sha256((candidate / relative).read_bytes()).hexdigest()
        if actual != expected:
            raise AdmissionError(f"protocol v2 migration content mismatch: {relative}")

    base_policy = load_json(base / "policy/admission_policy.json", "base policy")
    candidate_policy = load_json(candidate / "policy/admission_policy.json", "candidate policy")
    base_sources = policy_sources(base_policy)
    candidate_sources = policy_sources(candidate_policy)
    if set(base_sources) != set(candidate_sources):
        raise AdmissionError("protocol v2 migration changed the Source set")
    for source_id in sorted(base_sources):
        before = dict(base_sources[source_id])
        after = dict(candidate_sources[source_id])
        if before.pop("protocol", None) != 1 or after.pop("protocol", None) != 2:
            raise AdmissionError(f"protocol v2 migration is not exactly 1 to 2: {source_id}")
        if before != after:
            raise AdmissionError(f"protocol v2 migration changed other authority: {source_id}")
    return paths


def github_api(token: str, method: str, route: str, payload: dict | None = None) -> dict:
    data = None if payload is None else canonical(payload)
    request = urllib.request.Request(
        f"https://api.github.com/repos/{REPOSITORY}{route}",
        data=data,
        method=method,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "newshub-policy-maintenance-admission",
            **({"Content-Type": "application/json"} if data else {}),
        },
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.load(response)


def post_status(token_file: Path, pr_number: int, base_sha: str, head_sha: str) -> None:
    token = token_file.read_text(encoding="utf-8").strip()
    if not token:
        raise AdmissionError("GitHub App token file is empty")
    pr = github_api(token, "GET", f"/pulls/{pr_number}")
    if (
        pr.get("state") != "open"
        or pr.get("base", {}).get("sha") != base_sha
        or pr.get("head", {}).get("sha") != head_sha
    ):
        raise AdmissionError("pull request no longer matches the admitted exact SHAs")
    github_api(
        token,
        "POST",
        f"/statuses/{head_sha}",
        {
            "state": "success",
            "context": STATUS_CONTEXT,
            "description": "GCP policy-hash maintenance admission passed",
        },
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", type=Path)
    parser.add_argument("--candidate", type=Path)
    parser.add_argument("--catalog", type=Path)
    parser.add_argument("--contract", type=Path)
    parser.add_argument("--token-file", type=Path)
    parser.add_argument("--pr-number", type=int)
    parser.add_argument("--base-sha")
    parser.add_argument("--head-sha")
    parser.add_argument("--post-status", action="store_true")
    parser.add_argument("--policy-code-maintenance", action="store_true")
    parser.add_argument("--protocol-v2-migration", action="store_true")
    args = parser.parse_args()
    try:
        if args.post_status:
            if not args.token_file or not args.pr_number or not args.base_sha or not args.head_sha:
                raise AdmissionError("status mode arguments are incomplete")
            if SHA.fullmatch(args.base_sha) is None or SHA.fullmatch(args.head_sha) is None:
                raise AdmissionError("status mode requires exact lowercase SHAs")
            post_status(args.token_file, args.pr_number, args.base_sha, args.head_sha)
            print("exact-head policy maintenance status published")
        else:
            if not args.base or not args.candidate:
                raise AdmissionError("validation mode arguments are incomplete")
            if args.protocol_v2_migration:
                changed = validate_protocol_v2_migration(
                    args.base.resolve(), args.candidate.resolve()
                )
                print("exact protocol v2 migration admitted for paths: " + ", ".join(changed))
            elif args.policy_code_maintenance:
                changed = validate_verifier_code(args.base.resolve(), args.candidate.resolve())
                print("policy verifier maintenance admitted for paths: " + ", ".join(changed))
            else:
                if not args.catalog or not args.contract:
                    raise AdmissionError(
                        "hash maintenance requires exact release catalog and reviewed contract paths"
                    )
                changed = validate(
                    args.base.resolve(), args.candidate.resolve(), args.catalog.resolve(),
                    args.contract.resolve(),
                )
                print("policy maintenance admitted for Sources: " + ", ".join(changed))
    except (AdmissionError, OSError, subprocess.SubprocessError, urllib.error.URLError) as exc:
        print(f"policy maintenance admission failed: {exc}", file=sys.stderr)
        return 2
    finally:
        if args.post_status and args.token_file:
            args.token_file.unlink(missing_ok=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
