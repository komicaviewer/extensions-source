#!/usr/bin/env python3
"""Validate a health payload and export catalog-owned Cloud Build context."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from extension_automation import PolicyError
from validate_agent_patch import _normalized_issue


def export_context(issue_json: Path, catalog: Path, output_dir: Path) -> dict:
    normalized, temporary_body = _normalized_issue(issue_json, catalog)
    if temporary_body:
        temporary_body.unlink(missing_ok=True)
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "health.json").write_text(
        json.dumps(normalized, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    fields = {
        "test-task": normalized["testTask"],
        "assemble-task": normalized["assembleTask"],
        "apk-output": normalized["apkOutput"],
        "source-id": normalized["sourceId"],
        "release-module": normalized["releaseModule"],
        "package": normalized["package"],
    }
    for filename, value in fields.items():
        if "\n" in value or "\0" in value:
            raise PolicyError(f"catalog field for {filename} contains an unsafe separator")
        (output_dir / f"{filename}.txt").write_text(value + "\n", encoding="utf-8")
    return normalized


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--issue-json", type=Path, required=True)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        export_context(args.issue_json, args.catalog, args.output_dir)
    except (OSError, json.JSONDecodeError, PolicyError, KeyError) as exc:
        print(f"Cloud Build context rejected: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
