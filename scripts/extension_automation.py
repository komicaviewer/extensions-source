#!/usr/bin/env python3
"""Fail-closed policy helpers for the extension repair automation.

This module is intentionally standard-library only so workflows can copy the
version from the trusted base branch into ``RUNNER_TEMP`` before checking out or
executing candidate code.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any


ISSUE_MARKER = "newshub-extension-health:v1"
FINGERPRINT_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
SHA_RE = re.compile(r"^[0-9a-f]{40}$")
HOST_RE = re.compile(r"^[a-z0-9.-]{1,253}$")
SAFE_OPERATION = {
    "getBoardCategories",
    "getBoardPage",
    "getThreadSummaries",
    "getThreadPage",
    "validateSession",
}
SAFE_FAILURE_CLASS = {
    "auth-required",
    "ci-infra",
    "host-regression",
    "parser-contract",
    "rate-limited",
    "site-outage",
}
SECRET_LIKE_RE = re.compile(
    r"(?i)(authorization\s*:|bearer\s+[a-z0-9._~+/-]+=*|cookie\s*:|"
    r"set-cookie\s*:|password\s*[=:]|api[_-]?key\s*[=:]|-----begin [^-]+-----)"
)


class PolicyError(ValueError):
    """Raised when automation input violates a fail-closed policy."""


def _load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PolicyError(f"cannot read valid JSON from {path}: {exc}") from exc


def _write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _require_text(value: Any, field: str, *, maximum: int) -> str:
    if not isinstance(value, str) or not value or len(value) > maximum:
        raise PolicyError(f"{field} must be non-empty text no longer than {maximum} characters")
    if any(ord(char) < 32 and char not in "\n\t" for char in value):
        raise PolicyError(f"{field} contains control characters")
    return value


def _require_safe_text(value: Any, field: str, *, maximum: int) -> str:
    text = _require_text(value, field, maximum=maximum)
    if SECRET_LIKE_RE.search(text):
        raise PolicyError(f"{field} contains secret-like material")
    return text


def _catalog_index(catalog_path: Path) -> dict[str, dict[str, Any]]:
    catalog = _load_json(catalog_path)
    if not isinstance(catalog, dict) or not isinstance(catalog.get("releases"), list):
        raise PolicyError("release catalog has no releases array")
    sources: dict[str, dict[str, Any]] = {}
    for release in catalog["releases"]:
        if not isinstance(release, dict) or not isinstance(release.get("sources"), list):
            raise PolicyError("invalid release catalog entry")
        for source in release["sources"]:
            source_id = source.get("id") if isinstance(source, dict) else None
            if not isinstance(source_id, str) or source_id in sources:
                raise PolicyError("release catalog contains an invalid or duplicate Source ID")
            sources[source_id] = {
                "sourceModule": source.get("module"),
                "sourceClassName": source.get("className"),
                "testTask": source.get("testTask"),
                "releaseModule": release.get("module"),
                "assembleTask": release.get("assembleTask"),
                "apkOutput": release.get("apkOutput"),
                "exactHosts": source.get("exactHosts"),
            }
    return sources


def _extract_issue_payload(body: str) -> dict[str, Any]:
    pattern = re.compile(
        rf"<!--\s*{re.escape(ISSUE_MARKER)}\s*(\{{.*?\}})\s*-->", re.DOTALL
    )
    matches = pattern.findall(body)
    if len(matches) != 1:
        raise PolicyError(f"issue body must contain exactly one {ISSUE_MARKER} payload")
    try:
        payload = json.loads(matches[0])
    except json.JSONDecodeError as exc:
        raise PolicyError(f"issue health payload is invalid JSON: {exc}") from exc
    if not isinstance(payload, dict):
        raise PolicyError("issue health payload must be an object")
    return payload


def normalize_issue(body_path: Path, catalog_path: Path) -> dict[str, Any]:
    payload = _extract_issue_payload(body_path.read_text(encoding="utf-8"))
    expected = {
        "schemaVersion",
        "sourceId",
        "operation",
        "failureClass",
        "targetHost",
        "fingerprint",
        "observedAt",
        "summary",
    }
    if set(payload) != expected:
        raise PolicyError(f"health payload fields must be exactly: {', '.join(sorted(expected))}")
    if payload["schemaVersion"] != 1:
        raise PolicyError("unsupported health payload schemaVersion")

    source_id = _require_text(payload["sourceId"], "sourceId", maximum=160)
    catalog_entry = _catalog_index(catalog_path).get(source_id)
    if catalog_entry is None:
        raise PolicyError(f"Source ID is not present in release-catalog.json: {source_id}")

    operation = _require_text(payload["operation"], "operation", maximum=64)
    if operation not in SAFE_OPERATION:
        raise PolicyError(f"unsupported operation: {operation}")
    failure_class = _require_text(payload["failureClass"], "failureClass", maximum=64)
    if failure_class not in SAFE_FAILURE_CLASS:
        raise PolicyError(f"unsupported failureClass: {failure_class}")
    target_host = _require_text(payload["targetHost"], "targetHost", maximum=253).lower()
    if not HOST_RE.fullmatch(target_host) or target_host not in catalog_entry["exactHosts"]:
        raise PolicyError("targetHost is not an exact host authorized for this Source")
    fingerprint = _require_text(payload["fingerprint"], "fingerprint", maximum=71)
    if not FINGERPRINT_RE.fullmatch(fingerprint):
        raise PolicyError("fingerprint must be sha256 followed by 64 lowercase hex characters")

    normalized = {
        **payload,
        "sourceId": source_id,
        "targetHost": target_host,
        "observedAt": _require_safe_text(payload["observedAt"], "observedAt", maximum=64),
        "summary": _require_safe_text(payload["summary"], "summary", maximum=500),
        **catalog_entry,
    }
    for name in (
        "sourceModule",
        "sourceClassName",
        "testTask",
        "releaseModule",
        "assembleTask",
        "apkOutput",
    ):
        _require_text(normalized[name], name, maximum=300)
    return normalized


def _git_paths(base: str, head: str | None) -> list[str]:
    if not SHA_RE.fullmatch(base):
        raise PolicyError("base must be a full lowercase Git SHA")
    command = ["git", "diff", "--name-only", "-z", base]
    if head:
        if not SHA_RE.fullmatch(head):
            raise PolicyError("head must be a full lowercase Git SHA")
        command.append(head)
    result = subprocess.run(command, check=True, stdout=subprocess.PIPE)
    paths = [item.decode("utf-8") for item in result.stdout.split(b"\0") if item]
    if head is None:
        untracked = subprocess.run(
            ["git", "ls-files", "--others", "--exclude-standard", "-z"],
            check=True,
            stdout=subprocess.PIPE,
        )
        paths.extend(item.decode("utf-8") for item in untracked.stdout.split(b"\0") if item)
    return sorted(set(paths))


def _is_allowed_source_path(path: str, source_module: str) -> bool:
    test_root = f"src/{source_module}/src/test/"
    if path.startswith(test_root):
        return not path.endswith((".jks", ".keystore"))

    production_roots = (
        f"src/{source_module}/src/main/java/",
        f"src/{source_module}/src/main/kotlin/",
    )
    if not path.startswith(production_roots) or not path.endswith((".kt", ".java")):
        return False
    relative = path.rsplit("/", 1)[-1]
    stem = relative.rsplit(".", 1)[0]
    return (
        "/parser/" in path
        or "/request/" in path
        or "/model/" in path
        or stem.endswith(("Parser", "RequestBuilder", "Boards", "BoardCatalog", "ThreadPager"))
        or stem in {"ParsedModels"}
        or ("/interactor/" in path and stem.startswith("Get"))
    )


def _validate_version_diff(base: str, head: str | None, path: str) -> None:
    command = ["git", "diff", "--unified=0", base]
    if head:
        command.append(head)
    command.extend(["--", path])
    diff = subprocess.run(command, check=True, text=True, stdout=subprocess.PIPE).stdout
    changed = [
        line[1:].strip()
        for line in diff.splitlines()
        if line.startswith(("+", "-")) and not line.startswith(("+++", "---"))
    ]
    version_code = re.compile(r'^set\("extVersionCode",\s*[0-9]+\)$')
    version_name = re.compile(r'^set\("extVersionName",\s*"[0-9A-Za-z._+-]+"\)$')
    if not changed or any(not (version_code.fullmatch(line) or version_name.fullmatch(line)) for line in changed):
        raise PolicyError(f"{path} may change only versionCode/versionName assignments")


def _validate_source_version_diff(base: str, head: str | None, path: str) -> None:
    command = ["git", "diff", "--unified=0", base]
    if head:
        command.append(head)
    command.extend(["--", path])
    diff = subprocess.run(command, check=True, text=True, stdout=subprocess.PIPE).stdout
    changed = [
        line[1:].strip()
        for line in diff.splitlines()
        if line.startswith(("+", "-")) and not line.startswith(("+++", "---"))
    ]
    source_version = re.compile(r"^override val version(?::\s*Int)?\s*=\s*[0-9]+$")
    if not changed or any(not source_version.fullmatch(line) for line in changed):
        raise PolicyError(f"{path} may change only the Source version assignment")


def validate_paths(
    input_path: Path, base: str, head: str | None, *, allow_version_bump: bool = True
) -> list[str]:
    issue = _load_json(input_path)
    source_module = _require_text(issue.get("sourceModule"), "sourceModule", maximum=120)
    source_class = _require_text(issue.get("sourceClassName"), "sourceClassName", maximum=240)
    release_module = _require_text(issue.get("releaseModule"), "releaseModule", maximum=120)
    paths = _git_paths(base, head)
    if not paths:
        raise PolicyError("automation produced no changed files")
    version_path = f"src/{release_module}/build.gradle.kts"
    source_version_path = f"src/{source_module}/src/main/kotlin/{source_class.replace('.', '/')}.kt"
    version_paths = {version_path, source_version_path} if allow_version_bump else set()
    rejected = [
        path
        for path in paths
        if path not in version_paths
        and not _is_allowed_source_path(path, source_module)
    ]
    if rejected:
        raise PolicyError("changed paths outside the repair allowlist: " + ", ".join(rejected))
    production_prefixes = (
        f"src/{source_module}/src/main/java/",
        f"src/{source_module}/src/main/kotlin/",
    )
    if not any(path.startswith(production_prefixes) for path in paths):
        raise PolicyError("repair must change at least one allowlisted production parser/request file")
    if not any(path.startswith(f"src/{source_module}/src/test/") for path in paths):
        raise PolicyError("repair must add or change a Source-local regression test or fixture")
    if allow_version_bump:
        if version_path not in paths:
            raise PolicyError(f"repair must bump versionCode/versionName in {version_path}")
        if source_version_path not in paths:
            raise PolicyError(f"repair must bump the Source version in {source_version_path}")
        _validate_version_diff(base, head, version_path)
        _validate_source_version_diff(base, head, source_version_path)
    return paths


def validate_attestation(checks_path: Path, sha: str, check_name: str) -> dict[str, Any]:
    if not SHA_RE.fullmatch(sha):
        raise PolicyError("attested SHA must be a full lowercase Git SHA")
    payload = _load_json(checks_path)
    checks = payload.get("check_runs") if isinstance(payload, dict) else None
    if not isinstance(checks, list):
        raise PolicyError("checks payload has no check_runs array")
    matches = [
        check
        for check in checks
        if isinstance(check, dict)
        and check.get("name") == check_name
        and check.get("head_sha") == sha
        and check.get("status") == "completed"
        and check.get("conclusion") == "success"
        and isinstance(check.get("output"), dict)
        and check["output"].get("title") == f"verified-sha:{sha}"
    ]
    if len(matches) != 1:
        raise PolicyError(f"expected exactly one successful {check_name} attestation for {sha}")
    return matches[0]


def validate_review(review_path: Path, sha: str) -> dict[str, Any]:
    if not SHA_RE.fullmatch(sha):
        raise PolicyError("reviewed SHA must be a full lowercase Git SHA")
    review = _load_json(review_path)
    if not isinstance(review, dict) or set(review) != {
        "verdict",
        "reviewedHeadSha",
        "summary",
        "findings",
    }:
        raise PolicyError("review result has unexpected fields")
    if review.get("verdict") not in {"approve", "request_changes"}:
        raise PolicyError("review verdict is invalid")
    if review.get("reviewedHeadSha") != sha:
        raise PolicyError("review result does not attest the current PR head SHA")
    _require_safe_text(review.get("summary"), "summary", maximum=1000)
    findings = review.get("findings")
    if not isinstance(findings, list) or len(findings) > 10:
        raise PolicyError("review findings must be an array of at most ten entries")
    for index, finding in enumerate(findings):
        _require_safe_text(finding, f"findings[{index}]", maximum=500)
    return review


def extract_issue_number(body_path: Path) -> int:
    body = body_path.read_text(encoding="utf-8")
    matches = re.findall(r"(?im)^Fixes\s+#([1-9][0-9]*)\s*$", body)
    if len(matches) != 1:
        raise PolicyError("automation PR body must contain exactly one `Fixes #<issue>` line")
    return int(matches[0])


def _github_output(path: Path, values: dict[str, str]) -> None:
    with path.open("a", encoding="utf-8") as output:
        for key, value in values.items():
            if "\n" in value:
                raise PolicyError(f"GitHub output {key} contains a newline")
            output.write(f"{key}={value}\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    prepare = subparsers.add_parser("prepare-issue")
    prepare.add_argument("--body-file", type=Path, required=True)
    prepare.add_argument("--catalog", type=Path, required=True)
    prepare.add_argument("--output", type=Path, required=True)
    prepare.add_argument("--github-output", type=Path)

    paths = subparsers.add_parser("validate-paths")
    paths.add_argument("--input", type=Path, required=True)
    paths.add_argument("--base", required=True)
    paths.add_argument("--head")

    attestation = subparsers.add_parser("validate-attestation")
    attestation.add_argument("--checks-json", type=Path, required=True)
    attestation.add_argument("--sha", required=True)
    attestation.add_argument("--check-name", default="newshub-extension-live-verification")

    review = subparsers.add_parser("validate-review")
    review.add_argument("--review-file", type=Path, required=True)
    review.add_argument("--sha", required=True)

    issue = subparsers.add_parser("extract-issue-number")
    issue.add_argument("--body-file", type=Path, required=True)
    issue.add_argument("--github-output", type=Path)

    args = parser.parse_args()
    try:
        if args.command == "prepare-issue":
            normalized = normalize_issue(args.body_file, args.catalog)
            _write_json(args.output, normalized)
            if args.github_output:
                _github_output(
                    args.github_output,
                    {
                        "source_id": normalized["sourceId"],
                        "source_module": normalized["sourceModule"],
                        "release_module": normalized["releaseModule"],
                        "test_task": normalized["testTask"],
                        "assemble_task": normalized["assembleTask"],
                        "apk_output": normalized["apkOutput"],
                    },
                )
        elif args.command == "validate-paths":
            for changed_path in validate_paths(args.input, args.base, args.head):
                print(changed_path)
        elif args.command == "validate-attestation":
            validate_attestation(args.checks_json, args.sha, args.check_name)
        elif args.command == "validate-review":
            review_result = validate_review(args.review_file, args.sha)
            print(review_result["verdict"])
        else:
            number = extract_issue_number(args.body_file)
            print(number)
            if args.github_output:
                _github_output(args.github_output, {"issue_number": str(number)})
    except (OSError, subprocess.CalledProcessError, PolicyError) as exc:
        print(f"extension automation policy rejected input: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
