import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

from extension_automation import (
    PolicyError,
    extract_issue_number,
    normalize_issue,
    validate_attestation,
    validate_review,
    validate_paths,
)
import bump_release_version


ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "release-catalog.json"
SHA = "a" * 40


def health_payload(**overrides):
    payload = {
        "schemaVersion": 1,
        "sourceId": "tw.kevinzhang.newshub.extension.gamer",
        "operation": "getBoardPage",
        "failureClass": "parser-contract",
        "targetHost": "forum.gamer.com.tw",
        "fingerprint": "sha256:" + "b" * 64,
        "observedAt": "2026-08-11T01:23:45Z",
        "summary": "Board list returned no usable entries.",
    }
    payload.update(overrides)
    return payload


class ExtensionAutomationTest(unittest.TestCase):
    def issue_file(self, directory: Path, payload: dict) -> Path:
        path = directory / "issue.md"
        path.write_text(
            "Automated health report.\n\n<!-- newshub-extension-health:v1\n"
            + json.dumps(payload)
            + "\n-->\n",
            encoding="utf-8",
        )
        return path

    def test_normalizes_catalog_owned_health_payload(self):
        with tempfile.TemporaryDirectory() as temp:
            normalized = normalize_issue(self.issue_file(Path(temp), health_payload()), CATALOG)
        self.assertEqual(normalized["sourceModule"], "gamer")
        self.assertEqual(normalized["releaseModule"], "gamer")
        self.assertEqual(
            normalized["sourceClassName"], "tw.kevinzhang.newshub.extension.gamer.GamerSource"
        )
        self.assertEqual(normalized["testTask"], ":src:gamer:testDebugUnitTest")

    def test_rejects_secret_like_summary(self):
        with tempfile.TemporaryDirectory() as temp:
            issue = self.issue_file(Path(temp), health_payload(summary="Authorization: Bearer abc"))
            with self.assertRaisesRegex(PolicyError, "secret-like"):
                normalize_issue(issue, CATALOG)

    def test_rejects_host_not_bound_to_source(self):
        with tempfile.TemporaryDirectory() as temp:
            issue = self.issue_file(Path(temp), health_payload(targetHost="eyny.com"))
            with self.assertRaisesRegex(PolicyError, "not an exact host"):
                normalize_issue(issue, CATALOG)

    def test_accepts_one_exact_successful_attestation(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "checks.json"
            path.write_text(
                json.dumps(
                    {
                        "check_runs": [
                            {
                                "name": "newshub-extension-live-verification",
                                "head_sha": SHA,
                                "status": "completed",
                                "conclusion": "success",
                                "output": {"title": f"verified-sha:{SHA}"},
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )
            match = validate_attestation(path, SHA, "newshub-extension-live-verification")
        self.assertEqual(match["head_sha"], SHA)

    def test_rejects_attestation_for_other_sha(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "checks.json"
            path.write_text(json.dumps({"check_runs": []}), encoding="utf-8")
            with self.assertRaisesRegex(PolicyError, "exactly one"):
                validate_attestation(path, SHA, "newshub-extension-live-verification")

    def test_review_must_name_exact_head(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "review.json"
            path.write_text(
                json.dumps(
                    {
                        "verdict": "approve",
                        "reviewedHeadSha": "c" * 40,
                        "summary": "Looks good.",
                        "findings": [],
                    }
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(PolicyError, "current PR head"):
                validate_review(path, SHA)

    def test_extracts_single_fix_issue(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "pr.md"
            path.write_text("Automated repair.\n\nFixes #42\n", encoding="utf-8")
            self.assertEqual(extract_issue_number(path), 42)

    def test_deterministic_bump_updates_release_and_source_once(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            build = root / "src/gamer/build.gradle.kts"
            source = (
                root
                / "src/gamer/src/main/kotlin/tw/kevinzhang/newshub/extension/gamer/GamerSource.kt"
            )
            build.parent.mkdir(parents=True)
            source.parent.mkdir(parents=True)
            (root / "release-catalog.json").write_text(
                json.dumps(
                    {
                        "releases": [
                            {
                                "module": "gamer",
                                "assembleTask": ":src:gamer:assembleRelease",
                                "apkOutput": "out.apk",
                                "sources": [
                                    {
                                        "id": "gamer-id",
                                        "module": "gamer",
                                        "className": "tw.kevinzhang.newshub.extension.gamer.GamerSource",
                                        "testTask": ":src:gamer:testDebugUnitTest",
                                        "exactHosts": ["example.com"],
                                    }
                                ],
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )
            build.write_text(
                'set("extVersionCode", 5)\nset("extVersionName", "0.0.5")\n', encoding="utf-8"
            )
            source.write_text("override val version: Int = 5\n", encoding="utf-8")
            previous = bump_release_version.ROOT
            bump_release_version.ROOT = root
            try:
                bump_release_version.bump(
                    {
                        "sourceId": "gamer-id",
                        "sourceModule": "gamer",
                        "sourceClassName": "tw.kevinzhang.newshub.extension.gamer.GamerSource",
                        "releaseModule": "gamer",
                    }
                )
            finally:
                bump_release_version.ROOT = previous
            self.assertIn('set("extVersionCode", 6)', build.read_text(encoding="utf-8"))
            self.assertIn('set("extVersionName", "0.0.6")', build.read_text(encoding="utf-8"))
            self.assertEqual(source.read_text(encoding="utf-8"), "override val version: Int = 6\n")

    def test_deterministic_bump_supports_multi_source_bundle_release(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            build = root / "src/komica/build.gradle.kts"
            source = root / "src/akraft/src/main/kotlin/example/AkraftSource.kt"
            build.parent.mkdir(parents=True)
            source.parent.mkdir(parents=True)
            (root / "release-catalog.json").write_text(
                json.dumps(
                    {
                        "releases": [
                            {
                                "module": "komica",
                                "assembleTask": ":src:komica:assembleRelease",
                                "apkOutput": "out.apk",
                                "sources": [
                                    {
                                        "id": "akraft-id",
                                        "module": "akraft",
                                        "className": "example.AkraftSource",
                                        "testTask": ":src:akraft:testDebugUnitTest",
                                        "exactHosts": ["example.com"],
                                    }
                                ],
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )
            build.write_text(
                'set("bundleVersionCode", 5)\nset("bundleVersionName", "0.3.2")\n',
                encoding="utf-8",
            )
            source.write_text("override val version = 2\n", encoding="utf-8")
            previous = bump_release_version.ROOT
            bump_release_version.ROOT = root
            try:
                bump_release_version.bump(
                    {
                        "sourceId": "akraft-id",
                        "sourceModule": "akraft",
                        "sourceClassName": "example.AkraftSource",
                        "releaseModule": "komica",
                    }
                )
            finally:
                bump_release_version.ROOT = previous
            self.assertIn('set("bundleVersionCode", 6)', build.read_text(encoding="utf-8"))
            self.assertIn('set("bundleVersionName", "0.3.3")', build.read_text(encoding="utf-8"))
            self.assertEqual(source.read_text(encoding="utf-8"), "override val version = 3\n")

    def test_pre_bump_agent_policy_allows_only_code_and_test(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.email", "test@example.com"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.name", "Test"], cwd=repo, check=True)
            parser = repo / "src/gamer/src/main/kotlin/BoardParser.kt"
            test = repo / "src/gamer/src/test/kotlin/BoardParserTest.kt"
            parser.parent.mkdir(parents=True)
            test.parent.mkdir(parents=True)
            parser.write_text("class BoardParser\n", encoding="utf-8")
            test.write_text("class BoardParserTest\n", encoding="utf-8")
            subprocess.run(["git", "add", "."], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-qm", "base"], cwd=repo, check=True)
            base = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
            parser.write_text("class FixedBoardParser\n", encoding="utf-8")
            test.write_text("class BoardParserRegressionTest\n", encoding="utf-8")
            issue = repo / ".git/health.json"
            issue.write_text(
                json.dumps(
                    {
                        "sourceModule": "gamer",
                        "sourceClassName": "tw.kevinzhang.newshub.extension.gamer.GamerSource",
                        "releaseModule": "gamer",
                    }
                ),
                encoding="utf-8",
            )
            previous = Path.cwd()
            os.chdir(repo)
            try:
                paths = validate_paths(issue, base, None, allow_version_bump=False)
            finally:
                os.chdir(previous)
        self.assertEqual(
            paths,
            [
                "src/gamer/src/main/kotlin/BoardParser.kt",
                "src/gamer/src/test/kotlin/BoardParserTest.kt",
            ],
        )

    def test_changed_path_policy_accepts_parser_test_and_version_only(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.email", "test@example.com"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.name", "Test"], cwd=repo, check=True)
            source = repo / "src/gamer/src/main/kotlin/BoardParser.kt"
            source_version = (
                repo
                / "src/gamer/src/main/kotlin/tw/kevinzhang/newshub/extension/gamer/GamerSource.kt"
            )
            test = repo / "src/gamer/src/test/kotlin/BoardParserTest.kt"
            build = repo / "src/gamer/build.gradle.kts"
            source.parent.mkdir(parents=True)
            source_version.parent.mkdir(parents=True)
            test.parent.mkdir(parents=True)
            source.write_text("class Parser\n", encoding="utf-8")
            source_version.write_text("override val version = 1\n", encoding="utf-8")
            test.write_text("class ParserTest\n", encoding="utf-8")
            build.write_text(
                'set("extVersionCode", 1)\nset("extVersionName", "1.0")\n', encoding="utf-8"
            )
            subprocess.run(["git", "add", "."], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-qm", "base"], cwd=repo, check=True)
            base = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
            source.write_text("class Parser2\n", encoding="utf-8")
            source_version.write_text("override val version = 2\n", encoding="utf-8")
            test.write_text("class ParserRegressionTest\n", encoding="utf-8")
            build.write_text(
                'set("extVersionCode", 2)\nset("extVersionName", "1.1")\n', encoding="utf-8"
            )
            input_path = repo / ".git/input.json"
            input_path.write_text(
                json.dumps(
                    {
                        "sourceModule": "gamer",
                        "sourceClassName": "tw.kevinzhang.newshub.extension.gamer.GamerSource",
                        "releaseModule": "gamer",
                    }
                ),
                encoding="utf-8",
            )
            result = subprocess.run(
                [
                    "python3",
                    str(ROOT / "scripts/extension_automation.py"),
                    "validate-paths",
                    "--input",
                    str(input_path),
                    "--base",
                    base,
                ],
                cwd=repo,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("BoardParser.kt", result.stdout)

    def test_changed_path_policy_rejects_authentication_file(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.email", "test@example.com"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.name", "Test"], cwd=repo, check=True)
            auth = repo / "src/gamer/src/main/kotlin/Authentication.kt"
            source_version = (
                repo
                / "src/gamer/src/main/kotlin/tw/kevinzhang/newshub/extension/gamer/GamerSource.kt"
            )
            test = repo / "src/gamer/src/test/kotlin/AuthTest.kt"
            build = repo / "src/gamer/build.gradle.kts"
            auth.parent.mkdir(parents=True)
            source_version.parent.mkdir(parents=True)
            test.parent.mkdir(parents=True)
            auth.write_text("class Authentication\n", encoding="utf-8")
            source_version.write_text("override val version = 1\n", encoding="utf-8")
            test.write_text("class AuthTest\n", encoding="utf-8")
            build.write_text(
                'set("extVersionCode", 1)\nset("extVersionName", "1.0")\n', encoding="utf-8"
            )
            subprocess.run(["git", "add", "."], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-qm", "base"], cwd=repo, check=True)
            base = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
            auth.write_text("class Authentication2\n", encoding="utf-8")
            source_version.write_text("override val version = 2\n", encoding="utf-8")
            test.write_text("class AuthRegressionTest\n", encoding="utf-8")
            build.write_text(
                'set("extVersionCode", 2)\nset("extVersionName", "1.1")\n', encoding="utf-8"
            )
            input_path = repo / ".git/input.json"
            input_path.write_text(
                json.dumps(
                    {
                        "sourceModule": "gamer",
                        "sourceClassName": "tw.kevinzhang.newshub.extension.gamer.GamerSource",
                        "releaseModule": "gamer",
                    }
                ),
                encoding="utf-8",
            )
            result = subprocess.run(
                [
                    "python3",
                    str(ROOT / "scripts/extension_automation.py"),
                    "validate-paths",
                    "--input",
                    str(input_path),
                    "--base",
                    base,
                ],
                cwd=repo,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
        self.assertEqual(result.returncode, 2)
        self.assertIn("outside the repair allowlist", result.stderr)


if __name__ == "__main__":
    unittest.main()
