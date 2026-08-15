import base64
import json
import re
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import github_app_token
import materialize_publisher_signing_bundle
import publish_distribution_pr
from cloudbuild_export_context import export_context
from extension_automation import PolicyError
from publish_distribution_pr import validate_staged_paths
from sign_release_bundle import sign_bundle
from materialize_publisher_signing_bundle import (
    PublisherBundleError,
    materialize,
    parse_bundle,
)


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

    def test_signer_accepts_strict_private_credentials_file(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            (directory / "newshub-gamer-v1.apk").write_bytes(b"unsigned")
            cert = "A" * 64
            credentials = directory / "gamer.json"
            credentials.write_text(
                json.dumps(
                    {
                        "keyB64": base64.b64encode(b"keystore").decode(),
                        "storePassword": "store-secret",
                        "alias": "alias",
                        "keyPassword": "key-secret",
                        "certificateSha256": cert,
                    }
                )
            )

            def fake_run(args, **_kwargs):
                if "verify" in args:
                    return SimpleNamespace(
                        stdout=f"Signer #1 certificate SHA-256 digest: {cert}\n"
                    )
                return SimpleNamespace(stdout="")

            with mock.patch("sign_release_bundle.subprocess.run", side_effect=fake_run):
                output = sign_bundle(
                    "gamer",
                    directory,
                    directory / "out",
                    "apksigner",
                    credentials_file=credentials,
                )
            self.assertTrue(output.is_file())

    def test_publisher_bundle_is_strict_and_materializes_private_files(self):
        signing = {
            module: {
                "keyB64": "a2V5",
                "storePassword": "store",
                "alias": "alias",
                "keyPassword": "key",
                "certificateSha256": "aa:" * 31 + "aa",
            }
            for module in materialize_publisher_signing_bundle.SIGNING_MODULES
        }
        raw = json.dumps(
            {
                "schemaVersion": 1,
                "bundleType": "publisher-signing",
                "privateKeyPem": "-----BEGIN PRIVATE KEY-----\nYWJj\n-----END PRIVATE KEY-----\n",
                "signing": signing,
            }
        ).encode()
        payload = parse_bundle(raw)
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "private"
            materialize(payload, output)
            files = {path.relative_to(output).as_posix() for path in output.rglob("*") if path.is_file()}
            self.assertEqual(
                {"github-app-private-key.pem"}
                | {f"signing/{module}.json" for module in signing},
                files,
            )
            self.assertTrue(all(path.stat().st_mode & 0o777 == 0o600 for path in output.rglob("*") if path.is_file()))
        with self.assertRaisesRegex(PublisherBundleError, "strict allowlist"):
            parse_bundle(raw[:-1] + b',"unexpected":"secret"}')

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

    def test_publisher_installation_token_keeps_status_write_scope(self):
        source = (ROOT / "scripts/github_app_token.py").read_text(encoding="utf-8")
        self.assertIn('"contents": "write"', source)
        self.assertIn('"pull_requests": "write"', source)
        self.assertIn('"statuses": "write"', source)

    def test_distribution_publish_allowlist(self):
        validate_staged_paths(
            ["apk/example.apk", "icon/example.png", "index.json", "index.min.json"]
        )
        with self.assertRaisesRegex(ValueError, "outside the allowlist"):
            validate_staged_paths([".github/workflows/publish.yml"])

    def test_distribution_status_is_exact_head_after_admission_and_before_merge(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            token_file = directory / "token"
            token_file.write_text("installation-token")
            calls = []

            def fake_git(_repo, *args, **_kwargs):
                calls.append(("git", args, None))
                if args[:3] == ("diff", "--cached", "--name-only"):
                    return "index.json"
                if args[:2] == ("rev-parse", "HEAD"):
                    return "d" * 40
                return ""

            def fake_api(_repository, _token, method, route, payload=None):
                calls.append((method, route, payload))
                if method == "POST" and route == "/pulls":
                    return {"number": 17}
                if method == "PUT":
                    return {"merged": True}
                return {}

            with mock.patch.object(publish_distribution_pr, "_git", side_effect=fake_git), mock.patch.object(
                publish_distribution_pr, "_api", side_effect=fake_api
            ):
                result = publish_distribution_pr.publish(
                    directory,
                    "komicaviewer/extensions",
                    token_file,
                    "build-12345678",
                    "a" * 40,
                )

        self.assertEqual("17", result)
        status_index = next(
            index for index, call in enumerate(calls)
            if call[0] == "POST" and call[1] == "/statuses/" + "d" * 40
        )
        pr_index = next(index for index, call in enumerate(calls) if call[0] == "POST" and call[1] == "/pulls")
        merge_index = next(index for index, call in enumerate(calls) if call[0] == "PUT")
        self.assertLess(pr_index, status_index)
        self.assertLess(status_index, merge_index)
        self.assertEqual(
            {
                "state": "success",
                "context": "GCP distribution admission / verify",
                "description": "GCP distribution admission passed for exact candidate",
            },
            calls[status_index][2],
        )
        config = (ROOT / "cloudbuild/publish.yaml").read_text(encoding="utf-8")
        self.assertLess(
            config.index("id: generate-and-admit-distribution"),
            config.index("id: publish-exact-distribution-pr"),
        )
        self.assertIn("waitFor: [generate-and-admit-distribution]", config)

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
        self.assertIn("id: cleanup-private-material", config)
        self.assertIn("waitFor: [publish-exact-distribution-pr]", config)
        for module in ("eyny", "gamer", "hackernews", "komica", "komica2", "mobile01", "ptt"):
            self.assertIn(f"id: sign-{module}", config)
            self.assertIn(
                f"--credentials-file /workspace/secure/publisher/signing/{module}.json",
                config,
            )

    def test_publish_prefixes_every_secret_manager_reference(self):
        config = (ROOT / "cloudbuild/publish.yaml").read_text(encoding="utf-8")
        version_names = [
            value.strip() for value in re.findall(r"versionName:\s*([^,\n]+)", config)
        ]
        self.assertEqual(5, len(version_names))
        self.assertIn("_SECRET_PREFIX: REQUIRED_SECRET_PREFIX", config)
        self.assertIn(
            "projects/$PROJECT_ID/secrets/${_SECRET_PREFIX}-publisher-signing-bundle/versions/${_PUBLISHER_SIGNING_BUNDLE_VERSION}",
            version_names,
        )
        for role in ("targets-a", "targets-b", "snapshot", "timestamp"):
            self.assertTrue(
                any(f"-tuf-{role}-key/versions/${{" in value for value in version_names),
                role,
            )
        for env in (
            "TUF_TARGETS_A_KEY",
            "TUF_TARGETS_B_KEY",
            "TUF_SNAPSHOT_KEY",
            "TUF_TIMESTAMP_KEY",
        ):
            self.assertEqual(1, len(re.findall(rf"secretEnv: \[{env}\]", config)), env)
            self.assertEqual(1, len(re.findall(rf"^\s+env: {env}$", config, re.MULTILINE)), env)
        self.assertNotIn("versions/latest", config)
        self.assertIn("GITHUB_APP_ID=${_PUBLISHER_GITHUB_APP_ID}", config)
        self.assertIn(
            "GITHUB_APP_INSTALLATION_ID=${_PUBLISHER_GITHUB_APP_INSTALLATION_ID}",
            config,
        )
        self.assertNotIn("GITHUB_APP_ID,", config)
        self.assertNotIn("GITHUB_APP_PRIVATE_KEY]", config)

    def test_publish_requires_controller_supplied_exact_merge_commit(self):
        config = (ROOT / "cloudbuild/publish.yaml").read_text(encoding="utf-8")
        self.assertIn("_SOURCE_SHA: REQUIRED_EXACT_MERGE_COMMIT_SHA", config)
        self.assertNotIn("_SOURCE_SHA: $COMMIT_SHA", config)

    def test_repository_has_no_active_github_action_workflow(self):
        workflows = ROOT / ".github/workflows"
        self.assertFalse(workflows.exists() and any(workflows.glob("*.yml")))
        self.assertFalse(workflows.exists() and any(workflows.glob("*.yaml")))


if __name__ == "__main__":
    unittest.main()
