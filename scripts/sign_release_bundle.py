#!/usr/bin/env python3
"""Sign exactly one catalog release bundle without exposing secret values."""

from __future__ import annotations

import argparse
import base64
import binascii
import os
import re
import subprocess
import sys
import tempfile
import json
from pathlib import Path

from extension_automation import PolicyError


MODULE_RE = re.compile(r"^[a-z0-9-]+$")
CREDENTIAL_KEYS = {"keyB64", "storePassword", "alias", "keyPassword", "certificateSha256"}


def _required_env(prefix: str, suffix: str) -> str:
    name = f"{prefix}_{suffix}"
    value = os.environ.get(name, "")
    if not value:
        raise PolicyError(f"required secret environment variable is missing: {name}")
    return value


def _load_credentials(path: Path) -> tuple[str, str, str, str, str]:
    if not path.is_file() or path.stat().st_size <= 0 or path.stat().st_size >= 65_536:
        raise PolicyError("signing credentials file is missing or too large")
    try:
        def no_duplicates(pairs: list[tuple[str, object]]) -> dict[str, object]:
            value: dict[str, object] = {}
            for key, item in pairs:
                if key in value:
                    raise PolicyError("signing credentials contain a duplicate key")
                value[key] = item
            return value

        payload = json.loads(
            path.read_text(encoding="utf-8"), object_pairs_hook=no_duplicates
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise PolicyError("signing credentials file is not valid UTF-8 JSON") from exc
    if not isinstance(payload, dict) or set(payload) != CREDENTIAL_KEYS:
        raise PolicyError("signing credentials do not match the strict schema")
    if any(not isinstance(payload[key], str) or not payload[key] or "\x00" in payload[key] for key in CREDENTIAL_KEYS):
        raise PolicyError("signing credentials contain an invalid value")
    return (
        payload["keyB64"],
        payload["storePassword"],
        payload["alias"],
        payload["keyPassword"],
        payload["certificateSha256"],
    )


def sign_bundle(
    module: str,
    input_dir: Path,
    output_dir: Path,
    apksigner: str,
    prefix: str | None = None,
    credentials_file: Path | None = None,
) -> Path:
    if not MODULE_RE.fullmatch(module):
        raise PolicyError("invalid release module")
    candidates = sorted(input_dir.glob(f"newshub-{module}-v*.apk"))
    if len(candidates) != 1:
        raise PolicyError(f"expected exactly one unsigned APK for {module}, found {len(candidates)}")
    if (prefix is None) == (credentials_file is None):
        raise PolicyError("exactly one signing credential source is required")
    if credentials_file is not None:
        key_b64, store_password, alias, key_password, expected_raw = _load_credentials(credentials_file)
    else:
        assert prefix is not None
        key_b64 = _required_env(prefix, "SIGNING_KEY_B64")
        store_password = _required_env(prefix, "KEY_STORE_PASSWORD")
        alias = _required_env(prefix, "KEY_ALIAS")
        key_password = _required_env(prefix, "KEY_PASSWORD")
        expected_raw = _required_env(prefix, "SIGNING_CERT_SHA256")
    expected = re.sub(r"[\s:]", "", expected_raw).upper()
    if not re.fullmatch(r"[0-9A-F]{64}", expected):
        raise PolicyError(f"invalid signing certificate SHA-256 for {module}")
    try:
        key_bytes = base64.b64decode(key_b64, validate=True)
    except (ValueError, binascii.Error) as exc:
        raise PolicyError(f"invalid base64 signing key for {module}") from exc
    output_dir.mkdir(parents=True, exist_ok=True)
    output = output_dir / candidates[0].name
    output.write_bytes(candidates[0].read_bytes())
    with tempfile.NamedTemporaryFile(prefix=f"{module}-", suffix=".jks", delete=False) as handle:
        handle.write(key_bytes)
        keystore = Path(handle.name)
    keystore.chmod(0o600)
    child_env = {
        "PATH": os.environ.get("PATH", ""),
        "KEY_STORE_PASSWORD_INPUT": store_password,
        "KEY_PASSWORD_INPUT": key_password,
    }
    try:
        subprocess.run(
            ["keytool", "-list", "-keystore", str(keystore), "-storepass:env", "KEY_STORE_PASSWORD_INPUT", "-alias", alias],
            check=True,
            env=child_env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        subprocess.run(
            [
                apksigner,
                "sign",
                "--ks",
                str(keystore),
                "--ks-key-alias",
                alias,
                "--ks-pass",
                "env:KEY_STORE_PASSWORD_INPUT",
                "--key-pass",
                "env:KEY_PASSWORD_INPUT",
                str(output),
            ],
            check=True,
            env=child_env,
        )
        verification = subprocess.run(
            [apksigner, "verify", "--verbose", "--print-certs", str(output)],
            check=True,
            text=True,
            stdout=subprocess.PIPE,
            env=child_env,
        ).stdout
    finally:
        keystore.unlink(missing_ok=True)
    match = re.search(r"^Signer #1 certificate SHA-256 digest:\s*([^\n]+)$", verification, re.MULTILINE)
    actual = re.sub(r"[\s:]", "", match.group(1)).upper() if match else ""
    if actual != expected:
        output.unlink(missing_ok=True)
        raise PolicyError(f"signing certificate mismatch for {module}")
    return output


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--module", required=True)
    parser.add_argument("--input-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--apksigner", required=True)
    credential_source = parser.add_mutually_exclusive_group(required=True)
    credential_source.add_argument("--env-prefix")
    credential_source.add_argument("--credentials-file", type=Path)
    args = parser.parse_args()
    try:
        output = sign_bundle(
            args.module,
            args.input_dir,
            args.output_dir,
            args.apksigner,
            args.env_prefix,
            args.credentials_file,
        )
        print(output.name)
    except (OSError, subprocess.CalledProcessError, PolicyError) as exc:
        print(f"bundle signing failed: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
