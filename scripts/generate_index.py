#!/usr/bin/env python3
"""
Reads bundle APKs from <apk_dir>, extracts package metadata using aapt and
Source metadata from assets/newshub-extension.json, then regenerates index.json
and index.min.json and copies APKs to <output_dir>/apk/.

Usage: generate_index.py <apk_dir> <output_dir>
Requires: aapt in PATH or AAPT env var pointing to the binary.
"""
import json
import os
import re
import shutil
import hashlib
import subprocess
import sys
import zipfile


REGISTRY_ASSET_PATH = "assets/newshub-extension.json"
REGISTRY_SCHEMA_VERSION = 1
SOURCE_FIELDS = ("className", "id", "name", "lang", "baseUrl")


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


def read_registry(apk_path: str) -> dict:
    try:
        with zipfile.ZipFile(apk_path) as apk:
            registry = json.loads(apk.read(REGISTRY_ASSET_PATH).decode("utf-8"))
    except KeyError as e:
        raise ValueError(f"missing {REGISTRY_ASSET_PATH}") from e
    except (zipfile.BadZipFile, UnicodeDecodeError, json.JSONDecodeError) as e:
        raise ValueError(f"invalid extension registry: {e}") from e

    if registry.get("schemaVersion") != REGISTRY_SCHEMA_VERSION:
        raise ValueError(f"unsupported registry schemaVersion: {registry.get('schemaVersion')}")
    if not isinstance(registry.get("name"), str) or not registry["name"].strip():
        raise ValueError("registry name must be non-empty")
    sources = registry.get("sources")
    if not isinstance(sources, list) or not sources:
        raise ValueError("registry sources must be a non-empty array")

    source_ids = set()
    for index, source in enumerate(sources):
        if not isinstance(source, dict):
            raise ValueError(f"sources[{index}] must be an object")
        for field in SOURCE_FIELDS:
            if not isinstance(source.get(field), str) or not source[field].strip():
                raise ValueError(f"sources[{index}].{field} must be non-empty")
        source_id = source["id"]
        if source_id in source_ids:
            raise ValueError(f"duplicate source id: {source_id}")
        source_ids.add(source_id)
    return registry


def main():
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <apk_dir> <output_dir>")
        sys.exit(1)
    apk_dir, output_dir = sys.argv[1:]

    aapt = find_aapt()
    print(f"Using aapt: {aapt}")

    os.makedirs(os.path.join(output_dir, "apk"), exist_ok=True)
    os.makedirs(os.path.join(output_dir, "icon"), exist_ok=True)
    for old_apk in os.listdir(os.path.join(output_dir, "apk")):
        if old_apk.endswith(".apk"):
            os.remove(os.path.join(output_dir, "apk", old_apk))

    extensions = []
    for apk_file in sorted(os.listdir(apk_dir)):
        if not apk_file.endswith(".apk"):
            continue
        apk_path = os.path.join(apk_dir, apk_file)
        print(f"Processing: {apk_file}")

        meta = parse_apk(apk_path, aapt)
        if not meta.get("pkg"):
            print("  WARNING: no pkg from aapt, skipping")
            continue

        registry = read_registry(apk_path)

        sha = sha256_file(apk_path)
        shutil.copy2(apk_path, os.path.join(output_dir, "apk", apk_file))

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


if __name__ == "__main__":
    main()
