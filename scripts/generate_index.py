#!/usr/bin/env python3
"""Build and atomically publish a validated extensions distribution candidate."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import tempfile
import zipfile
from pathlib import Path
from typing import Callable

from release_catalog import (
    DEFAULT_CATALOG_PATH,
    load_catalog,
    metadata_for_release,
    releases_by_module,
)
from validate_distribution import (
    find_tool,
    read_apk_metadata,
    read_signing_fingerprint,
    sha256_file,
    validate_distribution_tree,
)
from validate_release_bundles import (
    module_from_apk_name,
    validate_release_bundles,
)


def _index_source(source: dict) -> dict:
    return {key: source[key] for key in ("id", "name", "lang", "baseUrl")}


SIGNATURE_ENTRY_RE = re.compile(
    r"^META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))$",
    re.IGNORECASE,
)


def apk_payload_sha256(apk_path: Path) -> str:
    """Hash APK entry names and contents without packaging/signature bytes."""
    digest = hashlib.sha256()
    try:
        with zipfile.ZipFile(apk_path) as archive:
            entries = [
                entry for entry in archive.infolist()
                if not entry.is_dir()
                and not SIGNATURE_ENTRY_RE.fullmatch(entry.filename)
                and entry.filename != "META-INF/version-control-info.textproto"
            ]
            names = [entry.filename for entry in entries]
            if len(names) != len(set(names)):
                raise ValueError(f"APK contains duplicate ZIP entries: {apk_path.name}")
            for entry in sorted(entries, key=lambda item: item.filename):
                name = entry.filename.encode("utf-8")
                content = archive.read(entry)
                digest.update(len(name).to_bytes(8, "big"))
                digest.update(name)
                digest.update(len(content).to_bytes(8, "big"))
                digest.update(content)
    except (OSError, zipfile.BadZipFile) as exc:
        raise ValueError(f"invalid APK archive {apk_path.name}: {exc}") from exc
    return digest.hexdigest()


def _baseline_entries(output: Path) -> dict[str, dict]:
    try:
        pretty = json.loads((output / "index.json").read_text(encoding="utf-8"))
        compact = json.loads((output / "index.min.json").read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return {}
    if pretty != compact or not isinstance(pretty, list):
        return {}
    return {
        item["pkg"]: item
        for item in pretty
        if isinstance(item, dict) and isinstance(item.get("pkg"), str)
    }


def _select_apk(
    fresh_apk: Path,
    metadata: dict,
    baseline: dict | None,
    output: Path,
) -> tuple[Path, str]:
    fresh_sha = sha256_file(fresh_apk)
    if baseline is None or metadata["versionCode"] != baseline.get("versionCode"):
        return fresh_apk, fresh_sha
    if metadata["versionName"] != baseline.get("versionName"):
        raise ValueError(
            f"versionName changed without versionCode bump for {metadata['pkg']}: "
            f"old={baseline.get('versionName')!r}, new={metadata['versionName']!r}",
        )
    baseline_name = baseline.get("apkName")
    if not isinstance(baseline_name, str) or Path(baseline_name).name != baseline_name:
        raise ValueError(f"invalid baseline APK name for {metadata['pkg']}")
    baseline_apk = output / "apk" / baseline_name
    if not baseline_apk.is_file() or sha256_file(baseline_apk) != baseline.get("sha256"):
        raise ValueError(f"baseline APK is missing or corrupted for {metadata['pkg']}")
    if apk_payload_sha256(fresh_apk) != apk_payload_sha256(baseline_apk):
        raise ValueError(f"APK payload changed without versionCode bump for {metadata['pkg']}")
    return baseline_apk, baseline["sha256"]


def _remove_path(path: Path) -> None:
    if path.is_dir() and not path.is_symlink():
        shutil.rmtree(path)
    elif path.exists() or path.is_symlink():
        path.unlink()


def _publish_staged_tree(stage: Path, output: Path) -> None:
    """Replace managed distribution paths, restoring all of them on any error."""
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="extensions-backup-", dir=output.parent) as backup_name:
        backup = Path(backup_name)
        managed = ("apk", "icon", "index.json", "index.min.json")
        installed: list[str] = []
        moved_to_backup: list[str] = []
        output_created = False
        try:
            if not output.exists():
                output.mkdir()
                output_created = True
            for name in managed:
                current = output / name
                if current.exists() or current.is_symlink():
                    os.replace(current, backup / name)
                    moved_to_backup.append(name)
            for name in managed:
                os.replace(stage / name, output / name)
                installed.append(name)
        except BaseException:
            for name in reversed(installed):
                _remove_path(output / name)
            for name in reversed(moved_to_backup):
                os.replace(backup / name, output / name)
            if output_created and output.exists() and not any(output.iterdir()):
                output.rmdir()
            raise


def generate_index(
    apk_dir: str,
    output_dir: str,
    aapt: str,
    apksigner: str,
    *,
    catalog: dict | None = None,
    metadata_reader: Callable[[str, str], dict] = read_apk_metadata,
    signature_reader: Callable[[str, str], str] = read_signing_fingerprint,
    bundle_validator: Callable[..., dict[str, dict]] = validate_release_bundles,
    distribution_validator: Callable[..., list[dict]] = validate_distribution_tree,
) -> list[dict]:
    catalog = catalog or load_catalog()
    apk_input = Path(apk_dir).resolve()
    output = Path(output_dir).resolve()

    # All reads and validation happen before the existing output is touched.
    bundle_validator(str(apk_input), catalog)
    release_by_module = releases_by_module(catalog)
    baseline_by_package = _baseline_entries(output)
    extensions: list[dict] = []
    prepared_apks: list[tuple[Path, str]] = []
    for apk_path in sorted(apk_input.glob("*.apk")):
        module = module_from_apk_name(apk_path.name, catalog)
        release = release_by_module[module]
        apk_metadata = metadata_reader(str(apk_path), aapt)
        if apk_metadata.get("pkg") != release["package"]:
            raise ValueError(
                f"unexpected package for {module}: "
                f"expected={release['package']}, actual={apk_metadata.get('pkg')}",
            )
        selected_apk, selected_sha = _select_apk(
            apk_path,
            apk_metadata,
            baseline_by_package.get(apk_metadata["pkg"]),
            output,
        )
        release_metadata = metadata_for_release(catalog, release)
        sources = [_index_source(source) for source in release_metadata["sources"]]
        languages = {source["lang"] for source in sources}
        extensions.append({
            "pkg": apk_metadata["pkg"],
            "name": release_metadata["name"],
            "versionCode": apk_metadata["versionCode"],
            "versionName": apk_metadata["versionName"],
            "lang": next(iter(languages)) if len(languages) == 1 else "",
            "apkName": apk_path.name,
            "iconName": release["icon"]["name"],
            "sha256": selected_sha,
            "sources": sources,
        })
        prepared_apks.append((selected_apk, apk_path.name))

    extensions.sort(key=lambda extension: extension["pkg"])
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="extensions-candidate-", dir=output.parent) as stage_name:
        stage = Path(stage_name)
        (stage / "apk").mkdir()
        (stage / "icon").mkdir()
        for source, name in prepared_apks:
            shutil.copy2(source, stage / "apk" / name)
        catalog_root = Path(catalog["_root"])
        for release in catalog["releases"]:
            shutil.copy2(
                catalog_root / release["icon"]["source"],
                stage / "icon" / release["icon"]["name"],
            )
        (stage / "index.json").write_text(
            json.dumps(extensions, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        (stage / "index.min.json").write_text(
            json.dumps(extensions, ensure_ascii=False, separators=(",", ":")) + "\n",
            encoding="utf-8",
        )

        baseline = None
        if (output / "index.json").is_file() and (output / "index.min.json").is_file():
            baseline = str(output)
        distribution_validator(
            str(stage),
            catalog,
            aapt=aapt,
            apksigner=apksigner,
            baseline_dir=baseline,
            metadata_reader=metadata_reader,
            signature_reader=signature_reader,
        )
        _publish_staged_tree(stage, output)
    return extensions


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk_dir")
    parser.add_argument("output_dir")
    parser.add_argument("--catalog", default=str(DEFAULT_CATALOG_PATH))
    parser.add_argument("--aapt", default=os.environ.get("AAPT"))
    parser.add_argument("--apksigner", default=os.environ.get("APKSIGNER"))
    args = parser.parse_args()
    aapt = args.aapt or find_tool("AAPT", ("aapt", "aapt2"))
    apksigner = args.apksigner or find_tool("APKSIGNER", ("apksigner",))
    extensions = generate_index(
        args.apk_dir,
        args.output_dir,
        aapt,
        apksigner,
        catalog=load_catalog(args.catalog),
    )
    print(
        f"Published validated distribution: APKs={len(extensions)}, "
        f"Sources={sum(len(item['sources']) for item in extensions)}",
    )


if __name__ == "__main__":
    main()
