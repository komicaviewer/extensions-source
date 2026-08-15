#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import datetime as dt
from pathlib import Path
import subprocess
import sys
from typing import Any

from generate_trusted_metadata import MetadataBuildError, canonical, expiry, key_id, public_der, sign


def key_record(private_key: Path) -> dict[str, Any]:
    return {
        "keytype": "ecdsa",
        "scheme": "ecdsa-sha2-nistp256",
        "keyval": {"public": base64.b64encode(public_der(private_key)).decode("ascii")},
    }


def bootstrap(
    output: Path,
    root_keys: list[Path],
    targets_keys: list[Path],
    snapshot_key: Path,
    timestamp_key: Path,
    *,
    now: dt.datetime | None = None,
) -> dict[str, Any]:
    if output.exists():
        raise MetadataBuildError("production root output already exists")
    if len(root_keys) != 2 or len(targets_keys) != 2:
        raise MetadataBuildError("root and targets roles each require exactly two keys")
    all_keys = [*root_keys, *targets_keys, snapshot_key, timestamp_key]
    keyids = [key_id(path) for path in all_keys]
    if len(keyids) != len(set(keyids)):
        raise MetadataBuildError("TUF role keys must be distinct")
    now = now or dt.datetime.now(dt.timezone.utc)
    if now.tzinfo is None:
        raise MetadataBuildError("root metadata clock must be timezone-aware")
    signed = {
        "_type": "root",
        "specVersion": "1.0",
        "version": 1,
        "expires": expiry(now, 1825),
        "consistentSnapshot": True,
        "keys": {key_id(path): key_record(path) for path in all_keys},
        "roles": {
            "root": {"keyids": [key_id(path) for path in root_keys], "threshold": 2},
            "targets": {"keyids": [key_id(path) for path in targets_keys], "threshold": 2},
            "snapshot": {"keyids": [key_id(snapshot_key)], "threshold": 1},
            "timestamp": {"keyids": [key_id(timestamp_key)], "threshold": 1},
        },
    }
    envelope = sign(signed, root_keys)
    output.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
    output.write_bytes(canonical(envelope))
    return envelope


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--root-key", type=Path, action="append", required=True)
    parser.add_argument("--targets-key", type=Path, action="append", required=True)
    parser.add_argument("--snapshot-key", type=Path, required=True)
    parser.add_argument("--timestamp-key", type=Path, required=True)
    args = parser.parse_args()
    try:
        envelope = bootstrap(
            args.output.resolve(), args.root_key, args.targets_key,
            args.snapshot_key, args.timestamp_key,
        )
    except (OSError, subprocess.SubprocessError, MetadataBuildError, ValueError) as exc:
        print(f"production root bootstrap failed: {exc}", file=sys.stderr)
        return 2
    print("bootstrapped production root " + str(envelope["signed"]["version"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
