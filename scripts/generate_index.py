#!/usr/bin/env python3
"""
Reads APKs from <apk_dir>, extracts metadata using aapt,
generates index.json, and copies APKs to <output_dir>/apk/.

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
        m = re.match(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", line)
        if m:
            meta["pkg"] = m.group(1)
            meta["versionCode"] = int(m.group(2))
            meta["versionName"] = m.group(3)

        m = re.match(r"application-label(?:-\w+)?:'(.+)'", line)
        if m and "name" not in meta:
            meta["name"] = m.group(1)

        m = re.match(r"meta-data: name='([^']+)' value='([^']*)'", line)
        if m:
            k, v = m.group(1), m.group(2)
            if k == "newshub.extension.name":        meta["name"] = v
            elif k == "newshub.extension.source_id":       meta["source_id"] = v
            elif k == "newshub.extension.source_name":     meta["source_name"] = v
            elif k == "newshub.extension.source_lang":     meta["source_lang"] = v
            elif k == "newshub.extension.source_base_url": meta["source_base_url"] = v
    return meta


def main():
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} <apk_dir> <output_dir>")
        sys.exit(1)

    apk_dir, output_dir = sys.argv[1], sys.argv[2]
    aapt = find_aapt()
    print(f"Using aapt: {aapt}")

    os.makedirs(os.path.join(output_dir, "apk"), exist_ok=True)
    os.makedirs(os.path.join(output_dir, "icon"), exist_ok=True)

    extensions = []
    for apk_file in sorted(os.listdir(apk_dir)):
        if not apk_file.endswith(".apk"):
            continue
        apk_path = os.path.join(apk_dir, apk_file)
        print(f"Processing: {apk_file}")

        meta = parse_apk(apk_path, aapt)
        if not meta.get("pkg"):
            print("  WARNING: no pkg, skipping")
            continue

        sha = sha256_file(apk_path)
        shutil.copy2(apk_path, os.path.join(output_dir, "apk", apk_file))

        sources = []
        if meta.get("source_id"):
            sources.append({
                "id":      meta["source_id"],
                "name":    meta.get("source_name", meta.get("name", "")),
                "lang":    meta.get("source_lang", ""),
                "baseUrl": meta.get("source_base_url", ""),
            })

        extensions.append({
            "pkg":         meta["pkg"],
            "name":        meta.get("name", meta["pkg"]),
            "versionCode": meta.get("versionCode", 1),
            "versionName": meta.get("versionName", "1.0"),
            "lang":        meta.get("source_lang", ""),
            "apkName":     apk_file,
            "iconName":    f"{meta['pkg']}.png",
            "sha256":      sha,
            "sources":     sources,
        })
        print(f"  OK: {meta['pkg']} v{meta.get('versionName','?')}, sources={len(sources)}")

    # Merge with existing index.json (upsert by pkg)
    index_path = os.path.join(output_dir, "index.json")
    existing = []
    if os.path.exists(index_path):
        with open(index_path, encoding="utf-8") as f:
            try:
                existing = json.load(f)
            except json.JSONDecodeError:
                existing = []

    new_pkgs = {e["pkg"] for e in extensions}
    merged = sorted(
        [e for e in existing if e["pkg"] not in new_pkgs] + extensions,
        key=lambda e: e["pkg"]
    )

    with open(index_path, "w", encoding="utf-8") as f:
        json.dump(merged, f, ensure_ascii=False, indent=2)
    print(f"\nWritten index.json: {len(merged)} extensions ({len(extensions)} updated)")


if __name__ == "__main__":
    main()
