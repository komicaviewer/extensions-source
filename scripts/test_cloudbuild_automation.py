import base64
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import github_app_token
from cloudbuild_export_context import export_context
from extension_automation import PolicyError
from publish_distribution_pr import validate_staged_paths
from sign_release_bundle import sign_bundle


ROOT = Path(__file__).resolve().parents[1]


class CloudBuildAutomationTest(unittest.TestCase):
    def test_export_context_normalizes_raw_health_payload(self):
        payload = {
            "schemaVersion": 1,
            "sourceId": "tw.kevinzhang.newshub.extension.gamer",
            "operation": "getBoardPage",
            "failureClass": "parser-contract",
            "targetHost": "forum.gamer.com.tw",
            "fingerprint": "sha256:" + "a" * 64,
            "observedAt": "2026-08-12T00:00:00Z",
            "summary": "Board parser returned no usable entries.",
        }
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            issue = directory / "issue.json"
            issue.write_text(json.dumps(payload), encoding="utf-8")
            normalized = export_context(issue, ROOT / "release-catalog.json", directory / "out")
            self.assertEqual(normalized["sourceModule"], "gamer")
            self.assertEqual(
                (directory / "out/test-task.txt").read_text(encoding="utf-8").strip(),
                ":src:gamer:testDebugUnitTest",
            )

    def test_signer_rejects_missing_or_duplicate_module_apk_before_secrets(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            with self.assertRaisesRegex(PolicyError, "exactly one"):
                sign_bundle("gamer", directory, directory / "out", "apksigner", "GAMER")
            (directory / "newshub-gamer-v1.apk").write_bytes(b"one")
            (directory / "newshub-gamer-v2.apk").write_bytes(b"two")
            with self.assertRaisesRegex(PolicyError, "exactly one"):
                sign_bundle("gamer", directory, directory / "out", "apksigner", "GAMER")

    def test_signer_passes_passwords_only_through_child_environment(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            (directory / "newshub-gamer-v1.apk").write_bytes(b"unsigned")
            cert = "A" * 64
            environment = {
                "GAMER_SIGNING_KEY_B64": base64.b64encode(b"keystore").decode(),
                "GAMER_KEY_STORE_PASSWORD": "store-secret",
                "GAMER_KEY_ALIAS": "alias",
                "GAMER_KEY_PASSWORD": "key-secret",
                "GAMER_SIGNING_CERT_SHA256": cert,
            }
            calls = []

            def fake_run(args, **kwargs):
                calls.append((args, kwargs.get("env", {})))
                if "verify" in args:
                    return SimpleNamespace(
                        stdout=f"Signer #1 certificate SHA-256 digest: {cert}\n"
                    )
                return SimpleNamespace(stdout="")

            with mock.patch.dict("os.environ", environment, clear=False), mock.patch(
                "sign_release_bundle.subprocess.run", side_effect=fake_run
            ):
                output = sign_bundle(
                    "gamer", directory, directory / "out", "apksigner", "GAMER"
                )
            self.assertTrue(output.is_file())
            rendered_args = " ".join(str(item) for args, _env in calls for item in args)
            self.assertNotIn("store-secret", rendered_args)
            self.assertNotIn("key-secret", rendered_args)

    def test_github_app_jwt_is_short_lived(self):
        with mock.patch.object(
            github_app_token.subprocess,
            "run",
            return_value=SimpleNamespace(stdout=b"signature"),
        ):
            token = github_app_token.make_jwt("123", "private-key", now=1_700_000_000)
        _, payload, _ = token.split(".")
        decoded = base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4))
        claims = json.loads(decoded)
        self.assertEqual(claims["exp"] - claims["iat"], 570)
        self.assertEqual(claims["iss"], "123")

    def test_distribution_publish_allowlist(self):
        validate_staged_paths(
            ["apk/example.apk", "icon/example.png", "index.json", "index.min.json"]
        )
        with self.assertRaisesRegex(ValueError, "outside the allowlist"):
            validate_staged_paths([".github/workflows/publish.yml"])

    def test_pr_candidate_is_zero_secret_and_bounded(self):
        config = (ROOT / "cloudbuild/pr-candidate.yaml").read_text(encoding="utf-8")
        self.assertNotIn("secretEnv:", config)
        self.assertNotIn("availableSecrets:", config)
        self.assertIn("machineType: E2_STANDARD_2", config)
        self.assertIn("timeout: 2700s", config)
        self.assertIn("queueTtl: 600s", config)
        self.assertNotIn("retry", config.lower())

    def test_publish_uses_secret_manager_and_short_lived_app_token(self):
        config = (ROOT / "cloudbuild/publish.yaml").read_text(encoding="utf-8")
        self.assertIn("availableSecrets:", config)
        self.assertIn("secretEnv:", config)
        self.assertIn("github_app_token.py", config)
        self.assertIn("machineType: E2_STANDARD_2", config)
        self.assertIn("timeout: 3000s", config)
        self.assertNotIn("retry", config.lower())
        for module in ("eyny", "gamer", "hackernews", "komica", "komica2", "mobile01", "ptt"):
            self.assertIn(f"id: sign-{module}", config)

    def test_repository_has_no_active_github_action_workflow(self):
        workflows = ROOT / ".github/workflows"
        self.assertFalse(workflows.exists() and any(workflows.glob("*.yml")))
        self.assertFalse(workflows.exists() and any(workflows.glob("*.yaml")))


if __name__ == "__main__":
    unittest.main()
