#!/usr/bin/env python3
"""Exchange GitHub App material for one short-lived installation token."""

from __future__ import annotations

import argparse
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


def _b64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def make_jwt(app_id: str, private_key: str, now: int | None = None) -> str:
    if not app_id.isdigit() or not private_key.strip():
        raise ValueError("invalid GitHub App material")
    now = int(time.time()) if now is None else now
    header = _b64url(json.dumps({"alg": "RS256", "typ": "JWT"}, separators=(",", ":")).encode())
    payload = _b64url(
        json.dumps({"iat": now - 30, "exp": now + 540, "iss": app_id}, separators=(",", ":")).encode()
    )
    unsigned = f"{header}.{payload}".encode("ascii")
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".pem", delete=False) as handle:
        handle.write(private_key)
        key_path = Path(handle.name)
    key_path.chmod(0o600)
    try:
        signature = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", str(key_path)],
            input=unsigned,
            check=True,
            stdout=subprocess.PIPE,
        ).stdout
    finally:
        key_path.unlink(missing_ok=True)
    return f"{unsigned.decode('ascii')}.{_b64url(signature)}"


def exchange_token(
    app_id: str, installation_id: str, private_key: str, repository: str
) -> tuple[str, str]:
    if not installation_id.isdigit():
        raise ValueError("invalid GitHub App installation ID")
    if not repository or "/" in repository:
        raise ValueError("invalid GitHub App repository scope")
    jwt = make_jwt(app_id, private_key)
    request = urllib.request.Request(
        f"https://api.github.com/app/installations/{installation_id}/access_tokens",
        data=json.dumps(
            {
                "repositories": [repository],
                "permissions": {
                    "contents": "write",
                    "pull_requests": "write",
                    "statuses": "write",
                },
            }
        ).encode("utf-8"),
        method="POST",
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {jwt}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "newshub-extension-cloudbuild",
        },
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        payload = json.load(response)
    token = payload.get("token")
    expires_at = payload.get("expires_at")
    if not isinstance(token, str) or not token or not isinstance(expires_at, str):
        raise ValueError("GitHub App token response is incomplete")
    expiry = datetime.fromisoformat(expires_at.replace("Z", "+00:00"))
    remaining = (expiry - datetime.now(timezone.utc)).total_seconds()
    if remaining <= 0 or remaining > 3900:
        raise ValueError("GitHub App token expiry is outside the expected short-lived bound")
    return token, expires_at


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--private-key", type=Path, required=True)
    args = parser.parse_args()
    try:
        private_key = args.private_key.read_text(encoding="utf-8")
        token, expires_at = exchange_token(
            os.environ.get("GITHUB_APP_ID", ""),
            os.environ.get("GITHUB_APP_INSTALLATION_ID", ""),
            private_key,
            os.environ.get("GITHUB_APP_REPOSITORY", ""),
        )
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(token, encoding="utf-8")
        args.output.chmod(0o600)
        (args.output.parent / "github-token-expires-at.txt").write_text(
            expires_at + "\n", encoding="utf-8"
        )
    except (OSError, ValueError, subprocess.CalledProcessError, urllib.error.URLError) as exc:
        print(f"GitHub App token exchange failed: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
