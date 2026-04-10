#!/usr/bin/env python3
"""
Reads APKs from <apk_dir>, extracts metadata from AndroidManifest.xml,
generates index.json and copies APKs to <output_dir>/apk/.
"""
import json
import os
import shutil
import hashlib
import sys
import zipfile
import xml.etree.ElementTree as ET

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(65536), b''):
            h.update(chunk)
    return h.hexdigest()

def parse_apk_manifest(apk_path):
    """Extract metadata from APK's AndroidManifest.xml using aapt2 or zipfile."""
    # Try using aapt2 if available
    import subprocess
    try:
        result = subprocess.run(
            ['aapt2', 'dump', 'xmltree', '--file', 'AndroidManifest.xml', apk_path],
            capture_output=True, text=True, timeout=30
        )
        if result.returncode == 0:
            return parse_aapt2_output(result.stdout, apk_path)
    except (FileNotFoundError, subprocess.TimeoutExpired):
        pass

    # Fallback: try aapt
    try:
        result = subprocess.run(
            ['aapt', 'dump', 'badging', apk_path],
            capture_output=True, text=True, timeout=30
        )
        if result.returncode == 0:
            return parse_aapt_output(result.stdout, apk_path)
    except (FileNotFoundError, subprocess.TimeoutExpired):
        pass

    return None

def parse_aapt_output(output, apk_path):
    meta = {}
    for line in output.splitlines():
        if line.startswith("package:"):
            parts = dict(p.split('=', 1) for p in line[8:].split() if '=' in p)
            meta['pkg'] = parts.get('name', '').strip("'")
            meta['versionCode'] = int(parts.get('versionCode', '1').strip("'"))
            meta['versionName'] = parts.get('versionName', '1.0').strip("'")
        if 'newshub.extension.name' in line:
            meta['name'] = line.split('=')[-1].strip().strip("'")
        if 'newshub.extension.source_class' in line:
            meta['sourceClass'] = line.split('=')[-1].strip().strip("'")
    return meta if 'pkg' in meta else None

def parse_aapt2_output(output, apk_path):
    # Simple parser for aapt2 xmltree output
    return None  # fallback to aapt

def main():
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} <apk_dir> <output_dir>")
        sys.exit(1)

    apk_dir = sys.argv[1]
    output_dir = sys.argv[2]

    os.makedirs(os.path.join(output_dir, 'apk'), exist_ok=True)
    os.makedirs(os.path.join(output_dir, 'icon'), exist_ok=True)

    extensions = []

    for apk_file in os.listdir(apk_dir):
        if not apk_file.endswith('.apk'):
            continue
        apk_path = os.path.join(apk_dir, apk_file)

        meta = parse_apk_manifest(apk_path)
        if not meta:
            print(f"WARNING: Could not parse metadata from {apk_file}, skipping")
            continue

        sha = sha256_file(apk_path)
        dest_apk = os.path.join(output_dir, 'apk', apk_file)
        shutil.copy2(apk_path, dest_apk)

        extensions.append({
            'pkg': meta['pkg'],
            'name': meta.get('name', meta['pkg']),
            'versionCode': meta['versionCode'],
            'versionName': meta['versionName'],
            'lang': 'zh-TW',  # TODO: read from manifest meta-data
            'apkName': apk_file,
            'iconName': f"{meta['pkg']}.png",
            'sha256': sha,
            'sources': []  # TODO: read source list from manifest
        })
        print(f"Processed: {meta['pkg']} v{meta['versionName']}")

    index_path = os.path.join(output_dir, 'index.json')
    with open(index_path, 'w', encoding='utf-8') as f:
        json.dump(extensions, f, ensure_ascii=False, indent=2)
    print(f"Written index.json with {len(extensions)} extensions")

if __name__ == '__main__':
    main()
