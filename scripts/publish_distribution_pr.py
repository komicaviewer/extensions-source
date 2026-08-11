#!/usr/bin/env python3
"""Push, open, and exact-SHA merge one validated distribution PR."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path


REPOSITORY_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
ALLOWED_PATH_RE = re.compile(
    r"^(?:apk/|icon/|targets/apk/[^/]+\.apk$|metadata/timestamp\.json$|"
    r"metadata/[1-9][0-9]*\.(?:root|snapshot|targets)\.json$|index\.json$|index\.min\.json$)"
)
ADMISSION_STATUS_CONTEXT = "GCP distribution admission / verify"
ADMISSION_STATUS_DESCRIPTION = "GCP distribution admission passed for exact candidate"


def validate_staged_paths(paths: list[str]) -> None:
    rejected = [path for path in paths if not ALLOWED_PATH_RE.match(path)]
    if rejected:
        raise ValueError("distribution paths outside the allowlist: " + ", ".join(rejected))


def _api(repository: str, token: str, method: str, route: str, payload: dict | None = None) -> dict:
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repository}{route}",
        data=body,
        method=method,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "newshub-extension-cloudbuild",
            **({"Content-Type": "application/json"} if body else {}),
        },
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        if response.status == 204:
            return {}
        return json.load(response)


def _git(repo: Path, *args: str, env: dict[str, str] | None = None, capture: bool = False) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=repo,
        env=env,
        check=True,
        text=True,
        stdout=subprocess.PIPE if capture else None,
    )
    return result.stdout.strip() if capture else ""


def publish(repo: Path, repository: str, token_file: Path, build_id: str, source_sha: str) -> str:
    if not REPOSITORY_RE.fullmatch(repository):
        raise ValueError("invalid destination repository")
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        raise ValueError("source SHA must be a full lowercase Git SHA")
    if not re.fullmatch(r"[A-Za-z0-9-]{8,80}", build_id):
        raise ValueError("invalid Cloud Build ID")
    token = token_file.read_text(encoding="utf-8").strip()
    if not token:
        raise ValueError("GitHub App token file is empty")
    managed = ["apk", "icon", "index.json", "index.min.json"]
    if (repo / "metadata").exists():
        managed.append("metadata")
    if (repo / "targets").exists():
        managed.append("targets")
    _git(repo, "add", "--", *managed)
    staged = _git(repo, "diff", "--cached", "--name-only", capture=True).splitlines()
    if not staged:
        return "no-op"
    validate_staged_paths(staged)
    branch = f"automation/extensions-cloudbuild-{build_id}"
    _git(repo, "config", "user.name", "newshub-extension-publisher[bot]")
    _git(repo, "config", "user.email", "newshub-extension-publisher[bot]@users.noreply.github.com")
    _git(repo, "switch", "-c", branch)
    _git(repo, "commit", "-m", "chore: 更新 extensions 發布候選")
    head_sha = _git(repo, "rev-parse", "HEAD", capture=True)
    with tempfile.TemporaryDirectory(prefix="github-askpass-") as temp:
        askpass = Path(temp) / "askpass.sh"
        askpass.write_text(
            "#!/bin/sh\ncase \"$1\" in *Username*) echo x-access-token;; *) cat \"$GITHUB_TOKEN_FILE\";; esac\n",
            encoding="utf-8",
        )
        askpass.chmod(0o700)
        git_env = {
            **os.environ,
            "GIT_ASKPASS": str(askpass),
            "GIT_TERMINAL_PROMPT": "0",
            "GITHUB_TOKEN_FILE": str(token_file),
        }
        _git(repo, "push", "--set-upstream", "origin", branch, env=git_env)
    pr = _api(
        repository,
        token,
        "POST",
        "/pulls",
        {
            "base": "main",
            "head": branch,
            "title": "chore: 更新 extensions 發布候選",
            "body": (
                "由 GCP Cloud Build 完成 catalog、簽章、完整性與歷史相容性驗證。\n\n"
                f"Source SHA: `{source_sha}`\nCloud Build ID: `{build_id}`"
            ),
        },
    )
    number = pr.get("number")
    if not isinstance(number, int):
        raise ValueError("GitHub did not return a pull request number")
    _api(
        repository,
        token,
        "POST",
        f"/statuses/{head_sha}",
        {
            "state": "success",
            "context": ADMISSION_STATUS_CONTEXT,
            "description": ADMISSION_STATUS_DESCRIPTION,
        },
    )
    merged = _api(
        repository,
        token,
        "PUT",
        f"/pulls/{number}/merge",
        {"sha": head_sha, "merge_method": "squash", "commit_title": "chore: 更新 extensions 發布"},
    )
    if merged.get("merged") is not True:
        raise ValueError(f"distribution PR #{number} was not merged")
    try:
        _api(repository, token, "DELETE", f"/git/refs/heads/{branch}")
    except urllib.error.HTTPError as exc:
        if exc.code != 422:
            raise
    return str(number)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--token-file", type=Path, required=True)
    parser.add_argument("--build-id", required=True)
    parser.add_argument("--source-sha", required=True)
    args = parser.parse_args()
    try:
        result = publish(
            args.repo.resolve(), args.repository, args.token_file, args.build_id, args.source_sha
        )
        print(result)
    except (OSError, ValueError, subprocess.CalledProcessError, urllib.error.URLError) as exc:
        print(f"distribution publication failed: {exc}", file=sys.stderr)
        return 2
    finally:
        args.token_file.unlink(missing_ok=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
