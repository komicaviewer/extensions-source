#!/usr/bin/env python3
"""
Reads bundle APKs from <apk_dir>, extracts package metadata using aapt and
Source metadata from assets/newshub-extension.json, then regenerates index.json
and index.min.json and copies APKs to <output_dir>/apk/.

Usage: generate_index.py <apk_dir> <output_dir>
Requires: aapt in PATH or AAPT env var pointing to the binary.
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys

from validate_release_bundles import (
    EXPECTED_RELEASES,
    module_from_apk_name,
    read_registry,
    validate_release_bundles,
)


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def find_aapt() -> str:
    if os.environ.get("AAPT"):
        return os.environ["AAPT"]
    for candidate in ["aapt", "aapt2"]:
        if shutil.which(candidate):
            return candidate
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME", "")
    bt = os.path.join(sdk, "build-tools")
    if os.path.isdir(bt):
        for v in sorted(os.listdir(bt), reverse=True):
            p = os.path.join(bt, v, "aapt")
            if os.path.isfile(p):
                return p
    raise FileNotFoundError("aapt not found. Set AAPT env var or install Android build-tools.")


def parse_apk(apk_path: str, aapt: str) -> dict:
    try:
        r = subprocess.run([aapt, "dump", "badging", apk_path],
                           capture_output=True, text=True, timeout=60)
        if r.returncode != 0:
            print(f"  aapt error: {r.stderr[:200]}")
            return {}
        return _parse_badging(r.stdout)
    except Exception as e:
        print(f"  Exception: {e}")
        return {}


def _parse_badging(output: str) -> dict:
    meta = {}
    for line in output.splitlines():
        # Anchored regex: avoids matching compileSdkVersionCodename at end of line
        m = re.match(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", line)
        if m:
            meta["pkg"] = m.group(1)
            meta["versionCode"] = int(m.group(2))
            meta["versionName"] = m.group(3)

        m = re.match(r"application-label(?:-\w+)?:'(.+)'", line)
        if m and "name" not in meta:
            meta["name"] = m.group(1)

    return meta


def generate_index(apk_dir: str, output_dir: str, aapt: str) -> list[dict]:
    # Establish that the input directory is the complete release before touching output_dir.
    validate_release_bundles(apk_dir)
    extensions = []
    prepared_apks = []
    for apk_file in sorted(os.listdir(apk_dir)):
        if not apk_file.endswith(".apk"):
            continue
        apk_path = os.path.join(apk_dir, apk_file)
        print(f"Processing: {apk_file}")

        module = module_from_apk_name(apk_file)
        meta = parse_apk(apk_path, aapt)
        if not meta.get("pkg"):
            raise ValueError(f"could not read package metadata from {apk_file}")
        expected_package = EXPECTED_RELEASES[module]["package"]
        if meta["pkg"] != expected_package:
            raise ValueError(
                f"unexpected package for {module}: "
                f"expected={expected_package}, actual={meta['pkg']}",
            )

        registry = read_registry(apk_path)

        sha = sha256_file(apk_path)
        prepared_apks.append((apk_path, apk_file))

        sources = [
            {
                "id": source["id"],
                "name": source["name"],
                "lang": source["lang"],
                "baseUrl": source["baseUrl"],
            }
            for source in registry["sources"]
        ]
        languages = {source["lang"] for source in sources}

        extensions.append({
            "pkg":         meta["pkg"],
            "name":        registry["name"],
            "versionCode": meta.get("versionCode", 1),
            "versionName": meta.get("versionName", "1.0"),
            "lang":        next(iter(languages)) if len(languages) == 1 else "",
            "apkName":     apk_file,
            "iconName":    f"{meta['pkg']}.png",
            "sha256":      sha,
            "sources":     sources,
        })
        print(f"  OK: {meta['pkg']} v{meta.get('versionName','?')}, sources={len(sources)}")

    packages = [extension["pkg"] for extension in extensions]
    if len(packages) != len(set(packages)):
        raise ValueError(f"duplicate packages in release input: {packages}")

    os.makedirs(os.path.join(output_dir, "apk"), exist_ok=True)
    os.makedirs(os.path.join(output_dir, "icon"), exist_ok=True)
    for old_apk in os.listdir(os.path.join(output_dir, "apk")):
        if old_apk.endswith(".apk"):
            os.remove(os.path.join(output_dir, "apk", old_apk))
    for apk_path, apk_file in prepared_apks:
        shutil.copy2(apk_path, os.path.join(output_dir, "apk", apk_file))

    # Destructive publication: the supplied bundles are the complete index.
    index_path = os.path.join(output_dir, "index.json")
    extensions.sort(key=lambda extension: extension["pkg"])

    with open(index_path, "w", encoding="utf-8") as f:
        json.dump(extensions, f, ensure_ascii=False, indent=2)
    print(f"\nWritten index.json: {len(extensions)} extensions")

    # Also write index.min.json (minified, same content)
    min_path = os.path.join(output_dir, "index.min.json")
    with open(min_path, "w", encoding="utf-8") as f:
        json.dump(extensions, f, ensure_ascii=False, separators=(",", ":"))
    print(f"Written index.min.json")
    return extensions


def main():
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <apk_dir> <output_dir>")
        sys.exit(1)
    apk_dir, output_dir = sys.argv[1:]

    aapt = find_aapt()
    print(f"Using aapt: {aapt}")
    generate_index(apk_dir, output_dir, aapt)


if __name__ == "__main__":
    main()
