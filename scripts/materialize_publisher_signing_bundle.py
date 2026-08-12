#!/usr/bin/env python3
"""Strictly validate and privately materialize the publisher signing bundle."""

from __future__ import annotations

import argparse
import base64
import binascii
import json
import os
import re
import sys
from pathlib import Path
from typing import Any


MAX_BUNDLE_BYTES = 65_536
SIGNING_MODULES = frozenset(
    {"eyny", "gamer", "hackernews", "komica", "komica2", "mobile01", "ptt"}
)
SIGNING_KEYS = frozenset(
    {"keyB64", "storePassword", "alias", "keyPassword", "certificateSha256"}
)
TOP_LEVEL_KEYS = frozenset({"schemaVersion", "bundleType", "privateKeyPem", "signing"})
PRIVATE_KEY_RE = re.compile(
    r"-----BEGIN (?:RSA )?PRIVATE KEY-----\n[\s\S]+\n-----END (?:RSA )?PRIVATE KEY-----\n?"
)
CERT_SHA256_RE = re.compile(r"[0-9A-Fa-f]{64}")


class PublisherBundleError(ValueError):
    """Raised when publisher signing material fails closed validation."""


def _object_without_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise PublisherBundleError("publisher signing bundle contains a duplicate key")
        value[key] = item
    return value


def _nonempty(value: object, field: str, *, maximum: int = 32_768) -> str:
    if (
        not isinstance(value, str)
        or not value.strip()
        or "\x00" in value
        or len(value.encode("utf-8")) > maximum
    ):
        raise PublisherBundleError(f"{field} is invalid")
    return value


def parse_bundle(raw: bytes) -> dict[str, Any]:
    if not raw or len(raw) >= MAX_BUNDLE_BYTES:
        raise PublisherBundleError("publisher signing bundle must be smaller than 64 KiB")
    try:
        payload = json.loads(raw.decode("utf-8"), object_pairs_hook=_object_without_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise PublisherBundleError("publisher signing bundle is not valid UTF-8 JSON") from exc
    if not isinstance(payload, dict) or set(payload) != TOP_LEVEL_KEYS:
        raise PublisherBundleError("publisher signing bundle keys do not match the strict allowlist")
    if payload.get("schemaVersion") != 1 or payload.get("bundleType") != "publisher-signing":
        raise PublisherBundleError("publisher signing bundle identity is invalid")
    private_key = _nonempty(payload.get("privateKeyPem"), "privateKeyPem")
    if not PRIVATE_KEY_RE.fullmatch(private_key):
        raise PublisherBundleError("privateKeyPem is not a supported PEM private key")
    signing = payload.get("signing")
    if not isinstance(signing, dict) or set(signing) != SIGNING_MODULES:
        raise PublisherBundleError("publisher signing modules do not match the strict allowlist")
    for module, material in signing.items():
        if not isinstance(material, dict) or set(material) != SIGNING_KEYS:
            raise PublisherBundleError(f"{module}: signing keys do not match the strict allowlist")
        for key in SIGNING_KEYS:
            maximum = 32_768 if key == "keyB64" else 4_096
            _nonempty(material.get(key), f"{module}.{key}", maximum=maximum)
        try:
            decoded_key = base64.b64decode(material["keyB64"], validate=True)
        except (ValueError, binascii.Error) as exc:
            raise PublisherBundleError(f"{module}: keyB64 is not valid base64") from exc
        if not decoded_key:
            raise PublisherBundleError(f"{module}: decoded signing key is empty")
        normalized = re.sub(r"[\s:]", "", material["certificateSha256"])
        if not CERT_SHA256_RE.fullmatch(normalized):
            raise PublisherBundleError(f"{module}: certificateSha256 is invalid")
        material["certificateSha256"] = normalized.upper()
    return payload


def _write_private(path: Path, value: bytes) -> None:
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    descriptor = os.open(path, flags, 0o600)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(value)
            handle.flush()
            os.fsync(handle.fileno())
    except BaseException:
        path.unlink(missing_ok=True)
        raise


def materialize(payload: dict[str, Any], output_dir: Path) -> None:
    output_dir.mkdir(parents=True, exist_ok=False, mode=0o700)
    output_dir.chmod(0o700)
    try:
        private_key = payload["privateKeyPem"].encode("utf-8")
        if not private_key.endswith(b"\n"):
            private_key += b"\n"
        _write_private(output_dir / "github-app-private-key.pem", private_key)
        signing_dir = output_dir / "signing"
        signing_dir.mkdir(mode=0o700)
        for module in sorted(SIGNING_MODULES):
            encoded = json.dumps(
                payload["signing"][module],
                ensure_ascii=True,
                separators=(",", ":"),
                sort_keys=True,
            ).encode("utf-8")
            _write_private(signing_dir / f"{module}.json", encoded)
    except BaseException:
        for path in sorted(output_dir.rglob("*"), reverse=True):
            if path.is_file():
                path.unlink(missing_ok=True)
            elif path.is_dir():
                path.rmdir()
        output_dir.rmdir()
        raise


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    try:
        payload = parse_bundle(args.input.read_bytes())
        materialize(payload, args.output_dir)
    except (OSError, PublisherBundleError) as exc:
        print(f"publisher signing bundle validation failed: {exc}", file=sys.stderr)
        return 2
    print("publisher signing bundle validated and privately materialized")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
