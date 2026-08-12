#!/usr/bin/env python3
"""Decrypt and validate the one-time extension signing-secret migration payload.

Secret values and decrypted content are never printed. By default the validated
payload remains only in a private temporary directory and is deleted before exit.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any


MAX_PAYLOAD_BYTES = 65536
REQUIRED_FIELDS = frozenset(
    {
        "SIGNING_KEY",
        "KEY_STORE_PASSWORD",
        "KEY_ALIAS",
        "KEY_PASSWORD",
        "SIGNING_CERT_SHA256",
    }
)
SHA256_RE = re.compile(r"^[0-9A-F]{64}$")


class MigrationValidationError(ValueError):
    """Raised when the encrypted migration payload cannot be trusted."""


def _run_quiet(command: list[str], *, env: dict[str, str] | None = None) -> None:
    try:
        subprocess.run(
            command,
            check=True,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            env=env,
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        raise MigrationValidationError("cryptographic validation command failed") from exc


def decrypt_payload(
    encrypted: Path,
    recipient_certificate: Path,
    recipient_private_key: Path,
    destination: Path,
    *,
    openssl: str = "openssl",
) -> None:
    if not encrypted.is_file() or encrypted.stat().st_size == 0:
        raise MigrationValidationError("encrypted DER artifact is missing or empty")
    _run_quiet(
        [
            openssl,
            "cms",
            "-decrypt",
            "-binary",
            "-inform",
            "DER",
            "-in",
            str(encrypted),
            "-recip",
            str(recipient_certificate),
            "-inkey",
            str(recipient_private_key),
            "-out",
            str(destination),
        ]
    )
    destination.chmod(0o600)


def _reject_duplicate_fields(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise MigrationValidationError("payload contains a duplicate field")
        result[key] = value
    return result


def load_and_validate_schema(payload_file: Path) -> dict[str, str]:
    size = payload_file.stat().st_size
    if size == 0 or size >= MAX_PAYLOAD_BYTES:
        raise MigrationValidationError("decrypted payload must be non-empty and smaller than 64 KiB")
    try:
        payload = json.loads(
            payload_file.read_text(encoding="utf-8"),
            object_pairs_hook=_reject_duplicate_fields,
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise MigrationValidationError("decrypted payload is not valid UTF-8 JSON") from exc
    if not isinstance(payload, dict) or set(payload) != REQUIRED_FIELDS:
        raise MigrationValidationError("payload schema does not contain exactly the required fields")
    if any(not isinstance(value, str) or not value for value in payload.values()):
        raise MigrationValidationError("every migration field must be a non-empty string")
    fingerprint = re.sub(r"[\s:]", "", payload["SIGNING_CERT_SHA256"]).upper()
    if not SHA256_RE.fullmatch(fingerprint):
        raise MigrationValidationError("SIGNING_CERT_SHA256 is invalid")
    payload["SIGNING_CERT_SHA256"] = fingerprint
    return payload


def verify_keystore_certificate(
    payload: dict[str, str],
    work_dir: Path,
    *,
    keytool: str = "keytool",
    openssl: str = "openssl",
) -> None:
    try:
        keystore_bytes = base64.b64decode(payload["SIGNING_KEY"], validate=True)
    except (ValueError, binascii.Error) as exc:
        raise MigrationValidationError("SIGNING_KEY is not valid base64") from exc
    if not keystore_bytes:
        raise MigrationValidationError("decoded signing keystore is empty")

    keystore = work_dir / "signing-keystore"
    exported_certificate = work_dir / "signing-certificate.der"
    keystore.write_bytes(keystore_bytes)
    keystore.chmod(0o600)
    child_env = {
        "PATH": os.environ.get("PATH", ""),
        "MIGRATION_STORE_PASSWORD": payload["KEY_STORE_PASSWORD"],
    }
    _run_quiet(
        [
            keytool,
            "-exportcert",
            "-keystore",
            str(keystore),
            "-storepass:env",
            "MIGRATION_STORE_PASSWORD",
            "-alias",
            payload["KEY_ALIAS"],
            "-file",
            str(exported_certificate),
        ],
        env=child_env,
    )
    try:
        result = subprocess.run(
            [
                openssl,
                "x509",
                "-inform",
                "DER",
                "-in",
                str(exported_certificate),
                "-noout",
                "-fingerprint",
                "-sha256",
            ],
            check=True,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        raise MigrationValidationError("could not fingerprint the signing certificate") from exc
    match = re.search(r"Fingerprint=([^\n]+)", result.stdout)
    actual = re.sub(r"[\s:]", "", match.group(1)).upper() if match else ""
    if actual != payload["SIGNING_CERT_SHA256"]:
        raise MigrationValidationError("signing certificate SHA-256 does not match the payload")


def decrypt_and_validate(
    encrypted: Path,
    recipient_certificate: Path,
    recipient_private_key: Path,
    *,
    output: Path | None = None,
    openssl: str = "openssl",
    keytool: str = "keytool",
) -> None:
    with tempfile.TemporaryDirectory(prefix="newshub-signing-migration-") as temporary:
        work_dir = Path(temporary)
        work_dir.chmod(0o700)
        decrypted = work_dir / "payload.json"
        decrypt_payload(
            encrypted,
            recipient_certificate,
            recipient_private_key,
            decrypted,
            openssl=openssl,
        )
        payload = load_and_validate_schema(decrypted)
        verify_keystore_certificate(payload, work_dir, keytool=keytool, openssl=openssl)
        if output is not None:
            if output.exists():
                raise MigrationValidationError("refusing to overwrite an existing output file")
            output.parent.mkdir(parents=True, exist_ok=True)
            with output.open("xb") as destination:
                destination.write(decrypted.read_bytes())
            output.chmod(0o600)
        for path in work_dir.iterdir():
            if path.is_file():
                with path.open("r+b") as handle:
                    handle.write(b"\0" * path.stat().st_size)
                    handle.flush()
                    os.fsync(handle.fileno())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--encrypted", required=True, type=Path)
    parser.add_argument("--recipient-cert", required=True, type=Path)
    parser.add_argument("--recipient-key", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--openssl", default="openssl")
    parser.add_argument("--keytool", default="keytool")
    args = parser.parse_args()
    try:
        decrypt_and_validate(
            args.encrypted,
            args.recipient_cert,
            args.recipient_key,
            output=args.output,
            openssl=args.openssl,
            keytool=args.keytool,
        )
    except (MigrationValidationError, OSError) as exc:
        print(f"signing migration validation failed: {exc}", file=sys.stderr)
        return 2
    print("signing migration payload decrypted and validated")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
