#!/usr/bin/env python3
"""Build and atomically publish a validated extensions distribution candidate."""
from __future__ import annotations

import argparse
import json
import os
import shutil
import tempfile
from pathlib import Path
from typing import Callable

from release_catalog import (
    DEFAULT_CATALOG_PATH,
    load_catalog,
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
    read_registry,
    validate_release_bundles,
)


def _index_source(source: dict) -> dict:
    return {key: source[key] for key in ("id", "name", "lang", "baseUrl")}


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
    expected_signing_cert_sha256: str,
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
    extensions: list[dict] = []
    prepared_apks: list[tuple[Path, str]] = []
    for apk_path in sorted(apk_input.glob("*.apk")):
        module = module_from_apk_name(apk_path.name, catalog)
        release = release_by_module[module]
        metadata = metadata_reader(str(apk_path), aapt)
        if metadata.get("pkg") != release["package"]:
            raise ValueError(
                f"unexpected package for {module}: "
                f"expected={release['package']}, actual={metadata.get('pkg')}",
            )
        registry = read_registry(str(apk_path))
        sources = [_index_source(source) for source in registry["sources"]]
        languages = {source["lang"] for source in sources}
        extensions.append({
            "pkg": metadata["pkg"],
            "name": registry["name"],
            "versionCode": metadata["versionCode"],
            "versionName": metadata["versionName"],
            "lang": next(iter(languages)) if len(languages) == 1 else "",
            "apkName": apk_path.name,
            "iconName": release["icon"]["name"],
            "sha256": sha256_file(apk_path),
            "sources": sources,
        })
        prepared_apks.append((apk_path, apk_path.name))

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
            expected_signing_cert_sha256=expected_signing_cert_sha256,
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
    parser.add_argument(
        "--signing-cert-sha256",
        default=os.environ.get("SIGNING_CERT_SHA256"),
    )
    args = parser.parse_args()
    if not args.signing_cert_sha256:
        raise SystemExit("SIGNING_CERT_SHA256 is required")
    aapt = args.aapt or find_tool("AAPT", ("aapt", "aapt2"))
    apksigner = args.apksigner or find_tool("APKSIGNER", ("apksigner",))
    extensions = generate_index(
        args.apk_dir,
        args.output_dir,
        aapt,
        apksigner,
        args.signing_cert_sha256,
        catalog=load_catalog(args.catalog),
    )
    print(
        f"Published validated distribution: APKs={len(extensions)}, "
        f"Sources={sum(len(item['sources']) for item in extensions)}",
    )


if __name__ == "__main__":
    main()
