#!/usr/bin/env python3
"""Deterministically bump the owning APK and Source versions for one repair."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path

from extension_automation import PolicyError, _catalog_index


ROOT = Path(os.environ.get("GITHUB_WORKSPACE", Path(__file__).resolve().parents[1])).resolve()


def _replace_one(text: str, pattern: re.Pattern[str], replacement) -> str:
    matches = list(pattern.finditer(text))
    if len(matches) != 1:
        raise PolicyError(f"expected exactly one version assignment matching {pattern.pattern}")
    match = matches[0]
    return text[: match.start()] + replacement(match) + text[match.end() :]


def bump(issue: dict) -> tuple[Path, Path]:
    for field in ("sourceId", "sourceModule", "sourceClassName", "releaseModule"):
        if not isinstance(issue.get(field), str) or not issue[field]:
            raise PolicyError(f"normalized issue JSON is missing {field}")
    catalog_entry = _catalog_index(ROOT / "release-catalog.json").get(issue["sourceId"])
    if catalog_entry is None:
        raise PolicyError("Source ID is not present in release-catalog.json")
    for field in ("sourceModule", "sourceClassName", "releaseModule"):
        if issue[field] != catalog_entry[field]:
            raise PolicyError(f"normalized issue {field} does not match release-catalog.json")
    build_path = ROOT / "src" / catalog_entry["releaseModule"] / "build.gradle.kts"
    source_path = (
        ROOT
        / "src"
        / catalog_entry["sourceModule"]
        / "src/main/kotlin"
        / Path(*catalog_entry["sourceClassName"].split("."))
    ).with_suffix(".kt")
    build_text = build_path.read_text(encoding="utf-8")
    build_text = _replace_one(
        build_text,
        re.compile(r'set\("extVersionCode",\s*([0-9]+)\)'),
        lambda match: f'set("extVersionCode", {int(match.group(1)) + 1})',
    )
    build_text = _replace_one(
        build_text,
        re.compile(r'set\("extVersionName",\s*"([0-9]+)\.([0-9]+)\.([0-9]+)"\)'),
        lambda match: (
            f'set("extVersionName", "{match.group(1)}.{match.group(2)}.'
            f'{int(match.group(3)) + 1}")'
        ),
    )
    source_text = source_path.read_text(encoding="utf-8")
    source_text = _replace_one(
        source_text,
        re.compile(r"override val version(?::\s*Int)?\s*=\s*([0-9]+)"),
        lambda match: match.group(0).rsplit(match.group(1), 1)[0] + str(int(match.group(1)) + 1),
    )
    build_path.write_text(build_text, encoding="utf-8")
    source_path.write_text(source_text, encoding="utf-8")
    return build_path, source_path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--issue-json", type=Path, required=True)
    args = parser.parse_args()
    try:
        issue = json.loads(args.issue_json.read_text(encoding="utf-8"))
        for changed in bump(issue):
            print(changed.relative_to(ROOT))
    except (OSError, json.JSONDecodeError, PolicyError) as exc:
        print(f"version bump rejected: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
