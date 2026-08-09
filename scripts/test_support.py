from __future__ import annotations

import json
import shutil
import zipfile
from pathlib import Path

from release_catalog import artifact_name, metadata_for_release
from validate_distribution import sha256_file


TEST_CERT = "AB" * 32


def write_release_apk(
    directory: Path,
    catalog: dict,
    release: dict,
    *,
    version_name: str = "1.0.0",
    legacy_registry: dict | None = None,
    include_dex: bool = True,
) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / artifact_name(release, version_name)
    release_metadata = metadata_for_release(catalog, release)
    with zipfile.ZipFile(path, "w") as apk:
        if legacy_registry is not None:
            write_deterministic_zip_entry(
                apk,
                "assets/newshub-extension.json",
                json.dumps(legacy_registry),
            )
        if include_dex:
            markers = "\n".join(
                source["className"].replace(".", "/") for source in release_metadata["sources"]
            )
            write_deterministic_zip_entry(apk, "classes.dex", markers)
    return path


def write_deterministic_zip_entry(apk: zipfile.ZipFile, name: str, contents: str) -> None:
    info = zipfile.ZipInfo(name, date_time=(2024, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    apk.writestr(info, contents)


def write_complete_apks(
    directory: Path,
    catalog: dict,
    *,
    version_name: str = "1.0.0",
) -> list[Path]:
    return [
        write_release_apk(directory, catalog, release, version_name=version_name)
        for release in catalog["releases"]
    ]


def metadata_reader_for(catalog: dict, *, version_code: int = 1):
    packages = {
        artifact_name(release, "1.0.0"): release["package"]
        for release in catalog["releases"]
    }

    def reader(apk_path: str, _aapt: str) -> dict:
        name = Path(apk_path).name
        return {
            "pkg": packages[name],
            "versionCode": version_code,
            "versionName": "1.0.0",
        }

    return reader


def signature_reader(_apk_path: str, _apksigner: str) -> str:
    return TEST_CERT


def build_distribution_tree(
    tree: Path,
    catalog: dict,
    *,
    version_code: int = 1,
    version_name: str = "1.0.0",
) -> list[dict]:
    apk_dir = tree / "apk"
    icon_dir = tree / "icon"
    apk_dir.mkdir(parents=True, exist_ok=True)
    icon_dir.mkdir(parents=True, exist_ok=True)
    entries: list[dict] = []
    catalog_root = Path(catalog["_root"])
    for release in catalog["releases"]:
        apk = write_release_apk(apk_dir, catalog, release, version_name=version_name)
        metadata = metadata_for_release(catalog, release)
        shutil.copy2(
            catalog_root / release["icon"]["source"],
            icon_dir / release["icon"]["name"],
        )
        sources = [
            {key: source[key] for key in ("id", "name", "lang", "baseUrl")}
            for source in metadata["sources"]
        ]
        languages = {source["lang"] for source in sources}
        entries.append({
            "pkg": release["package"],
            "name": metadata["name"],
            "versionCode": version_code,
            "versionName": version_name,
            "lang": next(iter(languages)) if len(languages) == 1 else "",
            "apkName": apk.name,
            "iconName": release["icon"]["name"],
            "sha256": sha256_file(apk),
            "sources": sources,
        })
    entries.sort(key=lambda item: item["pkg"])
    write_indexes(tree, entries)
    return entries


def write_indexes(tree: Path, entries: list[dict]) -> None:
    (tree / "index.json").write_text(
        json.dumps(entries, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    (tree / "index.min.json").write_text(
        json.dumps(entries, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
