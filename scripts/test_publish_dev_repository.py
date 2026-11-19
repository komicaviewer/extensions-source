#!/usr/bin/env python3
import base64
import copy
import datetime as dt
import hashlib
import json
import subprocess
from pathlib import Path

import pytest

import publish_dev_repository as dev
from release_catalog import artifact_name, load_catalog, releases_by_module

NOW = dt.datetime(2026, 11, 20, 12, 0, tzinfo=dt.timezone.utc)
CATALOG = load_catalog()
RELEASES = releases_by_module(CATALOG)


@pytest.fixture
def tool_readers(monkeypatch):
    """Replace aapt/apksigner readers with values keyed by APK file name."""
    apks: dict[str, dict] = {}

    def read_metadata(path, _aapt):
        return dict(apks[Path(path).name]["metadata"])

    def read_signer(path, _apksigner):
        return apks[Path(path).name]["signer"]

    monkeypatch.setattr(dev, "read_apk_metadata", read_metadata)
    monkeypatch.setattr(dev, "read_signing_fingerprint", read_signer)
    return apks


def make_apk(directory: Path, readers: dict, module: str, *, version: str = "1.0.0", code: int = 1,
             package_suffix: str = ".debug", signer: str = dev.DEV_DEBUG_CERT_SHA256) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    release = RELEASES[module]
    path = directory / artifact_name(release, version)
    path.write_bytes(f"{module}-{version}-apk".encode())
    readers[path.name] = {
        "metadata": {"pkg": release["package"] + package_suffix, "versionCode": code, "versionName": version},
        "signer": signer,
    }
    return path


def publish(apks, out, keys, **kwargs):
    return dev.publish(apks, out, keys, aapt="aapt", apksigner="apksigner", now=kwargs.pop("now", NOW), **kwargs)


def load(path: Path) -> dict:
    return json.loads(path.read_bytes())


def verify_envelope(raw: bytes, root_signed: dict, role: str, tmp: Path) -> None:
    """Independently check the role threshold with openssl, as the Host verifier does."""
    envelope = json.loads(raw)
    payload = dev.canonical(envelope["signed"])
    role_def = root_signed["roles"][role]
    accepted = set()
    for index, signature in enumerate(envelope["signatures"]):
        keyid = signature["keyid"]
        if keyid not in role_def["keyids"]:
            continue
        der = base64.b64decode(root_signed["keys"][keyid]["keyval"]["public"])
        assert hashlib.sha256(der).hexdigest() == keyid
        der_path, sig_path, data_path = (tmp / f"{role}-{index}.{ext}" for ext in ("der", "sig", "bin"))
        der_path.write_bytes(der)
        sig_path.write_bytes(base64.b64decode(signature["sig"]))
        data_path.write_bytes(payload)
        pem = subprocess.run(["openssl", "pkey", "-pubin", "-inform", "DER", "-in", str(der_path)],
                             check=True, capture_output=True).stdout
        pem_path = der_path.with_suffix(".pem")
        pem_path.write_bytes(pem)
        result = subprocess.run(["openssl", "dgst", "-sha256", "-verify", str(pem_path),
                                 "-signature", str(sig_path), str(data_path)], capture_output=True)
        if result.returncode == 0:
            accepted.add(keyid)
    assert len(accepted) >= role_def["threshold"], role


def resolve_chain(out: Path, tmp: Path) -> tuple[dict, dict, dict]:
    """Follow timestamp -> snapshot -> targets exactly like TrustedRepositoryClient.refresh."""
    metadata = out / "metadata"
    root = load(metadata / "root.json")["signed"]
    timestamp_raw = (metadata / "timestamp.json").read_bytes()
    verify_envelope(timestamp_raw, root, "timestamp", tmp)
    timestamp = json.loads(timestamp_raw)["signed"]
    snap_desc = timestamp["meta"]["snapshot.json"]
    snapshot_raw = (metadata / f"{snap_desc['version']}.snapshot.json").read_bytes()
    assert len(snapshot_raw) == snap_desc["length"]
    assert hashlib.sha256(snapshot_raw).hexdigest() == snap_desc["hashes"]["sha256"]
    verify_envelope(snapshot_raw, root, "snapshot", tmp)
    snapshot = json.loads(snapshot_raw)["signed"]
    assert snapshot["version"] == snap_desc["version"]
    targets_desc = snapshot["meta"]["targets.json"]
    targets_raw = (metadata / f"{targets_desc['version']}.targets.json").read_bytes()
    assert len(targets_raw) == targets_desc["length"]
    assert hashlib.sha256(targets_raw).hexdigest() == targets_desc["hashes"]["sha256"]
    verify_envelope(targets_raw, root, "targets", tmp)
    targets = json.loads(targets_raw)["signed"]
    assert targets["version"] == targets_desc["version"]
    return timestamp, snapshot, targets


def test_init_creates_root_once_and_refuses_overwrite(tmp_path, capsys):
    keys, out = tmp_path / "keys", tmp_path / "out"
    root_path = dev.init_repository(out, keys, now=NOW)

    assert root_path == out / "metadata" / "root.json"
    raw = root_path.read_bytes()
    assert (out / "metadata" / "1.root.json").read_bytes() == raw
    root = json.loads(raw)["signed"]
    assert root["version"] == 1 and root["consistentSnapshot"] is True
    assert {role: value["threshold"] for role, value in root["roles"].items()} == {
        "root": 2, "targets": 2, "snapshot": 1, "timestamp": 1,
    }
    verify_envelope(raw, root, "root", tmp_path)
    assert keys.stat().st_mode & 0o777 == 0o700
    assert all(path.stat().st_mode & 0o777 == 0o600 for path in keys.iterdir())
    assert not list(out.rglob("*.pem"))
    printed = capsys.readouterr().out
    assert hashlib.sha256(raw).hexdigest() in printed
    assert "PRIVATE KEY" not in printed

    with pytest.raises(ValueError, match="already"):
        dev.init_repository(out, tmp_path / "other-keys", now=NOW)
    with pytest.raises(ValueError, match="already"):
        dev.init_repository(tmp_path / "other-out", keys, now=NOW)
    assert root_path.read_bytes() == raw


def test_init_rejects_keys_inside_output(tmp_path):
    out = tmp_path / "out"
    with pytest.raises(ValueError, match="keys"):
        dev.init_repository(out, out / "keys", now=NOW)


def test_rejects_non_debug_signer(tmp_path, tool_readers):
    keys, out = tmp_path / "keys", tmp_path / "out"
    dev.init_repository(out, keys, now=NOW)
    apk = make_apk(tmp_path / "apks", tool_readers, "ptt", signer="ab" * 32)

    with pytest.raises(ValueError, match="signer"):
        publish([apk], out, keys)
    assert not (out / "metadata" / "timestamp.json").exists()
    assert not list((out / "targets").rglob("*.apk")) if (out / "targets").exists() else True


def test_rejects_package_without_debug_suffix(tmp_path, tool_readers):
    keys, out = tmp_path / "keys", tmp_path / "out"
    dev.init_repository(out, keys, now=NOW)
    apk = make_apk(tmp_path / "apks", tool_readers, "ptt", package_suffix="")

    with pytest.raises(ValueError, match="package"):
        publish([apk], out, keys)
    assert not (out / "metadata" / "timestamp.json").exists()


def test_publish_requires_initialized_repository(tmp_path, tool_readers):
    apk = make_apk(tmp_path / "apks", tool_readers, "ptt")
    with pytest.raises(ValueError, match="init"):
        publish([apk], tmp_path / "out", tmp_path / "keys")


def test_republishing_one_module_keeps_other_targets_and_bumps_versions(tmp_path, tool_readers):
    keys, out = tmp_path / "keys", tmp_path / "out"
    dev.init_repository(out, keys, now=NOW)
    apks = tmp_path / "apks"
    first = [make_apk(apks, tool_readers, module) for module in ("ptt", "gamer")]
    publish(first, out, keys)

    timestamp, snapshot, targets = resolve_chain(out, tmp_path)
    assert (timestamp["version"], snapshot["version"], targets["version"]) == (1, 1, 1)
    ptt_v1 = artifact_name(RELEASES["ptt"], "1.0.0")
    gamer_v1 = artifact_name(RELEASES["gamer"], "1.0.0")
    assert set(targets["targets"]) == {f"apk/{ptt_v1}", f"apk/{gamer_v1}"}
    ptt = targets["targets"][f"apk/{ptt_v1}"]
    assert ptt["custom"]["packageName"] == RELEASES["ptt"]["package"] + ".debug"
    assert ptt["custom"]["lineageRootSha256"] == dev.DEV_DEBUG_CERT_SHA256
    assert ptt["custom"]["apkSignerPins"] == [dev.DEV_DEBUG_CERT_SHA256]
    assert ptt["custom"]["versionCode"] == 1
    assert {source["id"] for source in ptt["custom"]["sources"]} == {
        source["id"] for source in RELEASES["ptt"]["sources"]
    }
    apk_bytes = (out / "targets" / "apk" / ptt_v1).read_bytes()
    assert ptt["length"] == len(apk_bytes)
    assert ptt["hashes"]["sha256"] == hashlib.sha256(apk_bytes).hexdigest()
    assert targets["custom"]["repository"] == CATALOG["repository"]
    gamer_entry = targets["targets"][f"apk/{gamer_v1}"]

    second = make_apk(tmp_path / "apks2", tool_readers, "ptt", version="1.0.1", code=2)
    publish([second], out, keys, now=NOW + dt.timedelta(hours=1))

    timestamp, snapshot, targets = resolve_chain(out, tmp_path)
    assert (timestamp["version"], snapshot["version"], targets["version"]) == (2, 2, 2)
    ptt_v2 = artifact_name(RELEASES["ptt"], "1.0.1")
    assert set(targets["targets"]) == {f"apk/{ptt_v2}", f"apk/{gamer_v1}"}
    assert targets["targets"][f"apk/{gamer_v1}"] == gamer_entry
    assert targets["targets"][f"apk/{ptt_v2}"]["custom"]["versionCode"] == 2
    assert not (out / "targets" / "apk" / ptt_v1).exists()
    assert (out / "targets" / "apk" / gamer_v1).exists()
    # Earlier consistent-snapshot files stay for clients that are mid-refresh.
    assert (out / "metadata" / "1.targets.json").exists()
    assert (out / "metadata" / "1.snapshot.json").exists()


@pytest.mark.parametrize("code", [1, 0])
def test_rejects_version_code_that_does_not_increase(tmp_path, tool_readers, code):
    keys, out = tmp_path / "keys", tmp_path / "out"
    dev.init_repository(out, keys, now=NOW)
    publish([make_apk(tmp_path / "apks", tool_readers, "ptt", version="1.0.0", code=1)], out, keys)
    before = (out / "metadata" / "timestamp.json").read_bytes()
    # Same versionName with different bytes must not overwrite, and a lower code must not roll back.
    stale = make_apk(tmp_path / "apks2", tool_readers, "ptt", version="1.0.0", code=code)
    stale.write_bytes(b"different-bytes")

    with pytest.raises(ValueError, match="versionCode"):
        publish([stale], out, keys, now=NOW + dt.timedelta(hours=1))
    assert (out / "metadata" / "timestamp.json").read_bytes() == before
    assert (out / "targets" / "apk" / stale.name).read_bytes() != b"different-bytes"


def test_missing_timestamp_with_numbered_metadata_fails(tmp_path, tool_readers):
    keys, out = tmp_path / "keys", tmp_path / "out"
    dev.init_repository(out, keys, now=NOW)
    publish([make_apk(tmp_path / "apks", tool_readers, "ptt")], out, keys)
    (out / "metadata" / "timestamp.json").unlink()

    with pytest.raises(ValueError, match="timestamp"):
        publish([make_apk(tmp_path / "apks", tool_readers, "gamer")], out, keys)


def test_rejects_tampered_existing_chain(tmp_path, tool_readers):
    keys, out = tmp_path / "keys", tmp_path / "out"
    dev.init_repository(out, keys, now=NOW)
    publish([make_apk(tmp_path / "apks", tool_readers, "ptt")], out, keys)
    targets_path = out / "metadata" / "1.targets.json"
    targets_path.write_bytes(targets_path.read_bytes() + b" ")

    with pytest.raises(ValueError, match="targets"):
        publish([make_apk(tmp_path / "apks", tool_readers, "gamer")], out, keys)


def test_policy_hash_mismatch_fails(tmp_path, tool_readers, monkeypatch):
    keys, out = tmp_path / "keys", tmp_path / "out"
    dev.init_repository(out, keys, now=NOW)
    original = dev.catalog_network_policies

    def tampered(catalog):
        policies = copy.deepcopy(original(catalog))
        source_id = RELEASES["ptt"]["sources"][0]["id"]
        policies[source_id]["namedCapabilities"] = []
        return policies

    monkeypatch.setattr(dev, "catalog_network_policies", tampered)
    apk = make_apk(tmp_path / "apks", tool_readers, "ptt")

    with pytest.raises(ValueError, match="network policy hash mismatch"):
        publish([apk], out, keys)
    assert not (out / "metadata" / "timestamp.json").exists()


def test_expiries_follow_dev_durations(tmp_path, tool_readers):
    keys, out = tmp_path / "keys", tmp_path / "out"
    dev.init_repository(out, keys, now=NOW)
    publish([make_apk(tmp_path / "apks", tool_readers, "ptt")], out, keys)

    def expires(name: str) -> dt.datetime:
        value = load(out / "metadata" / name)["signed"]["expires"]
        return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))

    assert expires("root.json") - NOW == dt.timedelta(days=365)
    assert expires("1.targets.json") - NOW == dt.timedelta(days=90)
    assert expires("1.snapshot.json") - NOW == dt.timedelta(days=90)
    assert expires("timestamp.json") - NOW == dt.timedelta(days=30)
