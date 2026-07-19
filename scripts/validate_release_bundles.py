#!/usr/bin/env python3
"""Validate the exact bundle and Source set published by release CI."""
import os
import sys

from generate_index import read_registry


EXPECTED_SOURCES = {
    "NewsHub: Komica": {
        "tw.kevinzhang.komica.twocat",
        "tw.kevinzhang.komica.sora",
    },
    "NewsHub: Komica2": {
        "tw.kevinzhang.komica2.twocat",
        "tw.kevinzhang.komica2.sora",
        "tw.kevinzhang.komica2.zawarudo",
    },
}


def validate_release_bundles(apk_dir: str) -> None:
    registries = {}
    for apk_name in sorted(os.listdir(apk_dir)):
        if not apk_name.endswith(".apk"):
            continue
        registry = read_registry(os.path.join(apk_dir, apk_name))
        name = registry["name"]
        if name in registries:
            raise ValueError(f"duplicate bundle name: {name}")
        registries[name] = {source["id"] for source in registry["sources"]}

    if set(registries) != set(EXPECTED_SOURCES):
        raise ValueError(
            f"unexpected bundle set: expected={sorted(EXPECTED_SOURCES)}, "
            f"actual={sorted(registries)}",
        )
    for name, expected_sources in EXPECTED_SOURCES.items():
        if registries[name] != expected_sources:
            raise ValueError(
                f"unexpected sources for {name}: expected={sorted(expected_sources)}, "
                f"actual={sorted(registries[name])}",
            )


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(f"Usage: {sys.argv[0]} <apk_dir>")
    validate_release_bundles(sys.argv[1])
    print("Release bundle registry validation passed")


if __name__ == "__main__":
    main()
