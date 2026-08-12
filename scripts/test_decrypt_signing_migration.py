import base64
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from decrypt_signing_migration import (
    MAX_PAYLOAD_BYTES,
    MigrationValidationError,
    decrypt_and_validate,
    load_and_validate_schema,
)


ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(shutil.which("openssl") and shutil.which("keytool"), "crypto tools required")
class SigningMigrationIntegrationTest(unittest.TestCase):
    def _fixture(self, directory: Path) -> tuple[Path, Path, Path, dict[str, str]]:
        recipient_key = directory / "recipient.key"
        recipient_cert = directory / "recipient.pem"
        subprocess.run(
            [
                "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                "-subj", "/CN=one-time-migration", "-days", "1",
                "-keyout", str(recipient_key), "-out", str(recipient_cert),
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        keystore = directory / "signer.p12"
        subprocess.run(
            [
                "keytool", "-genkeypair", "-noprompt", "-storetype", "PKCS12",
                "-keystore", str(keystore), "-storepass", "store-pass",
                "-keypass", "store-pass", "-alias", "extensions",
                "-keyalg", "RSA", "-dname", "CN=NewsHub Extensions", "-validity", "1",
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        certificate = directory / "signer.der"
        subprocess.run(
            [
                "keytool", "-exportcert", "-keystore", str(keystore),
                "-storepass", "store-pass", "-alias", "extensions",
                "-file", str(certificate),
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        fingerprint = subprocess.run(
            [
                "openssl", "x509", "-inform", "DER", "-in", str(certificate),
                "-noout", "-fingerprint", "-sha256",
            ],
            check=True,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        ).stdout.split("=", 1)[1].strip().replace(":", "")
        payload = {
            "SIGNING_KEY": base64.b64encode(keystore.read_bytes()).decode("ascii"),
            "KEY_STORE_PASSWORD": "store-pass",
            "KEY_ALIAS": "extensions",
            "KEY_PASSWORD": "store-pass",
            "SIGNING_CERT_SHA256": fingerprint,
        }
        return recipient_key, recipient_cert, keystore, payload

    def _encrypt(self, directory: Path, recipient_cert: Path, payload: dict[str, str]) -> Path:
        plaintext = directory / "payload.json"
        encrypted = directory / "payload.der"
        plaintext.write_text(json.dumps(payload, separators=(",", ":")), encoding="utf-8")
        subprocess.run(
            [
                "openssl", "cms", "-encrypt", "-binary", "-aes-256-cbc",
                "-outform", "DER", "-recip", str(recipient_cert),
                "-in", str(plaintext), "-out", str(encrypted),
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        plaintext.unlink()
        return encrypted

    def test_decrypts_validates_fingerprint_and_writes_private_output(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            recipient_key, recipient_cert, _keystore, payload = self._fixture(directory)
            encrypted = self._encrypt(directory, recipient_cert, payload)
            output = directory / "validated.json"
            decrypt_and_validate(encrypted, recipient_cert, recipient_key, output=output)
            self.assertEqual(0o600, output.stat().st_mode & 0o777)
            self.assertEqual(set(payload), set(json.loads(output.read_text())))

    def test_rejects_signing_certificate_mismatch(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            recipient_key, recipient_cert, _keystore, payload = self._fixture(directory)
            payload["SIGNING_CERT_SHA256"] = "0" * 64
            encrypted = self._encrypt(directory, recipient_cert, payload)
            with self.assertRaisesRegex(MigrationValidationError, "does not match"):
                decrypt_and_validate(encrypted, recipient_cert, recipient_key)

    def test_workflow_is_manual_bounded_and_uploads_only_der(self):
        workflow = (ROOT / ".github/workflows/temporary-export-signing-secrets.yml").read_text()
        self.assertIn("workflow_dispatch:", workflow)
        self.assertNotIn("\n  push:", workflow)
        self.assertNotIn("pull_request", workflow)
        self.assertIn("timeout-minutes: 10", workflow)
        self.assertIn("retention-days: 1", workflow)
        self.assertIn("/signing-migration.der", workflow)
        self.assertNotIn("path: ${{ steps.encrypt.outputs.encrypted_dir }}/payload.json", workflow)
        self.assertIn("SIGNING_CERT_SHA256: ${{ secrets.SIGNING_CERT_SHA256 }}", workflow)
        self.assertNotIn("EXTENSIONS_REPO_TOKEN", workflow)
        job_header, upload_step = workflow.split("      - name: Upload encrypted DER only", 1)
        self.assertIn("        env:\n          SIGNING_KEY:", job_header)
        self.assertNotIn("SIGNING_KEY: ${{ secrets.SIGNING_KEY }}", upload_step)


class SigningMigrationSchemaTest(unittest.TestCase):
    def test_rejects_extra_field(self):
        with tempfile.TemporaryDirectory() as temp:
            payload = Path(temp) / "payload.json"
            payload.write_text(
                json.dumps(
                    {
                        "SIGNING_KEY": "a2V5",
                        "KEY_STORE_PASSWORD": "store",
                        "KEY_ALIAS": "alias",
                        "KEY_PASSWORD": "key",
                        "SIGNING_CERT_SHA256": "A" * 64,
                        "EXTENSIONS_REPO_TOKEN": "must-not-migrate",
                    }
                )
            )
            with self.assertRaisesRegex(MigrationValidationError, "exactly"):
                load_and_validate_schema(payload)

    def test_rejects_payload_at_64_kib_limit(self):
        with tempfile.TemporaryDirectory() as temp:
            payload = Path(temp) / "payload.json"
            payload.write_bytes(b" " * MAX_PAYLOAD_BYTES)
            with self.assertRaisesRegex(MigrationValidationError, "smaller than 64 KiB"):
                load_and_validate_schema(payload)
