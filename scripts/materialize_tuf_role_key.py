#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from typing import Any


KEYS = {"schemaVersion", "bundleType", "role", "slot", "keyId", "privateKeyPem"}
KEY_ID = re.compile(r"^[0-9a-f]{64}$")
ROLES = {"targets", "snapshot", "timestamp"}


class RoleKeyError(ValueError):
    pass


def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise RoleKeyError("duplicate role-key field")
        value[key] = item
    return value


def public_key_id(private_key: bytes) -> str:
    with tempfile.TemporaryDirectory(prefix="newshub-tuf-key-") as temporary:
        private = Path(temporary) / "private.pem"
        public = Path(temporary) / "public.der"
        private.write_bytes(private_key)
        private.chmod(0o600)
        result = subprocess.run(
            ["openssl", "pkey", "-in", str(private), "-pubout", "-outform", "DER", "-out", str(public)],
            check=False,
            capture_output=True,
            timeout=20,
        )
        if result.returncode != 0:
            raise RoleKeyError("privateKeyPem is not a valid private key")
        inspected = subprocess.run(
            ["openssl", "pkey", "-pubin", "-inform", "DER", "-in", str(public), "-text_pub", "-noout"],
            check=False,
            capture_output=True,
            timeout=20,
        )
        if inspected.returncode != 0 or b"ASN1 OID: prime256v1" not in inspected.stdout:
            raise RoleKeyError("TUF role key must use ECDSA P-256")
        return hashlib.sha256(public.read_bytes()).hexdigest()


def parse(raw: bytes, expected_role: str, expected_slot: str, expected_key_id: str) -> bytes:
    if not raw or len(raw) > 16_384:
        raise RoleKeyError("role-key bundle has invalid size")
    try:
        payload = json.loads(raw.decode("utf-8"), object_pairs_hook=reject_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RoleKeyError("role-key bundle is not valid UTF-8 JSON") from exc
    if not isinstance(payload, dict) or set(payload) != KEYS:
        raise RoleKeyError("role-key bundle fields do not match the strict allowlist")
    if payload.get("schemaVersion") != 1 or payload.get("bundleType") != "tuf-role-key":
        raise RoleKeyError("role-key bundle identity is invalid")
    role = payload.get("role")
    slot = payload.get("slot")
    key_id = payload.get("keyId")
    private_key = payload.get("privateKeyPem")
    if role not in ROLES or role != expected_role or slot != expected_slot:
        raise RoleKeyError("role-key bundle role or slot is invalid")
    if not isinstance(key_id, str) or not KEY_ID.fullmatch(key_id) or key_id != expected_key_id:
        raise RoleKeyError("role-key bundle keyId does not match its IaC pin")
    if not isinstance(private_key, str) or "\x00" in private_key or len(private_key) > 8_192:
        raise RoleKeyError("privateKeyPem is invalid")
    encoded = private_key.encode("utf-8")
    if public_key_id(encoded) != key_id:
        raise RoleKeyError("privateKeyPem does not match keyId")
    return encoded


def write_private(path: Path, value: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "wb") as handle:
        handle.write(value)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--expected-role", required=True)
    parser.add_argument("--expected-slot", required=True)
    parser.add_argument("--expected-key-id", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        value = parse(
            args.input.read_bytes(), args.expected_role, args.expected_slot, args.expected_key_id
        )
        write_private(args.output, value)
    except (OSError, subprocess.SubprocessError, RoleKeyError) as exc:
        print(f"TUF role-key materialization failed: {exc}", file=sys.stderr)
        return 2
    print(f"materialized TUF {args.expected_role}/{args.expected_slot} key {args.expected_key_id}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
