#!/usr/bin/env python3
"""Stable compatibility CLI for validating an agent-produced repair patch."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

from extension_automation import PolicyError, normalize_issue, validate_paths


ROOT = Path(os.environ.get("GITHUB_WORKSPACE", Path(__file__).resolve().parents[1])).resolve()


def _full_sha(ref: str) -> str:
    return subprocess.check_output(
        ["git", "rev-parse", "--verify", f"{ref}^{{commit}}"], cwd=ROOT, text=True
    ).strip()


def _normalized_issue(issue_json: Path, catalog: Path) -> tuple[dict, Path | None]:
    payload = json.loads(issue_json.read_text(encoding="utf-8"))
    if isinstance(payload, dict) and {
        "sourceModule",
        "sourceClassName",
        "releaseModule",
        "testTask",
    }.issubset(payload):
        return payload, None
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".md", delete=False) as body:
        body.write("<!-- newshub-extension-health:v1\n")
        json.dump(payload, body, ensure_ascii=False)
        body.write("\n-->\n")
        body_path = Path(body.name)
    try:
        return normalize_issue(body_path, catalog), body_path
    except Exception:
        body_path.unlink(missing_ok=True)
        raise


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--issue-json", type=Path, required=True)
    parser.add_argument("--base-ref", required=True)
    parser.add_argument("--allow-version-bump", action="store_true")
    parser.add_argument("--print-test-task", action="store_true")
    parser.add_argument("--catalog", type=Path, default=ROOT / "release-catalog.json")
    args = parser.parse_args()
    temporary_body: Path | None = None
    temporary_issue: Path | None = None
    try:
        issue, temporary_body = _normalized_issue(args.issue_json, args.catalog)
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".json", delete=False) as output:
            json.dump(issue, output, ensure_ascii=False)
            temporary_issue = Path(output.name)
        base_sha = _full_sha(args.base_ref)
        previous = Path.cwd()
        os.chdir(ROOT)
        try:
            changed = validate_paths(
                temporary_issue, base_sha, None, allow_version_bump=args.allow_version_bump
            )
        finally:
            os.chdir(previous)
        print(issue["testTask"] if args.print_test_task else "\n".join(changed))
    except (OSError, subprocess.CalledProcessError, json.JSONDecodeError, PolicyError) as exc:
        print(f"agent patch rejected: {exc}", file=sys.stderr)
        return 2
    finally:
        if temporary_body:
            temporary_body.unlink(missing_ok=True)
        if temporary_issue:
            temporary_issue.unlink(missing_ok=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
