import importlib.util
import io
import json
import os
from pathlib import Path
import secrets
import stat
import subprocess
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from typing import Any
from unittest import mock


HELPER_PATH = Path(__file__).resolve().parents[1] / "provision-public-otlp-keys.py"
SPEC = importlib.util.spec_from_file_location("provision_public_otlp_keys", HELPER_PATH)
if SPEC is None or SPEC.loader is None:
    raise ImportError(f"Cannot load provisioning helper at {HELPER_PATH}")
keys: Any = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(keys)

LOCAL_TEST_KEY = "test-only-local-key"
GITHUB_TEST_KEY = "test-only-github-key"


class PublicOtlpKeysTests(unittest.TestCase):
    def make_paths(self, directory: Path, local_content: bytes = b"# test startup file\n"):
        startup = directory / "startup"
        backup = directory / "startup.backup"
        destination = directory / "public-otlp-auth.sops.yaml"
        startup.write_bytes(local_content)
        startup.chmod(0o644)
        return startup, backup, destination

    def patch_paths(self, paths):
        startup, backup, destination = paths
        return mock.patch.multiple(
            keys, BASHRC=startup, BACKUP=backup, DESTINATION=destination
        )

    def test_auth_config_routes_aliases_and_removes_authorization_header(self):
        local_token, github_token = "test-local-token", "test-github-token"
        users = keys.auth_config((local_token, github_token))["users"]
        self.assertEqual(["peer-reviewer-local", "peer-reviewer-github"],
                         [user["name"] for user in users])

        expected_paths = ["^/v1/traces$", "^/v1/logs$", "^/v1/metrics$"]
        for user, token, port in zip(users, (local_token, github_token), (4318, 4319)):
            self.assertEqual(token, user["bearer_token"])
            self.assertFalse(user["dump_request_on_errors"])
            self.assertEqual(["Authorization:"], user["headers"])
            self.assertEqual([{
                "src_paths": expected_paths,
                "url_prefix": (
                    "http://alloy-otlp-gateway.monitoring.svc.cluster.local:" + str(port)
                ),
            }], user["url_map"])

    def test_run_uses_stdin_and_suppresses_captured_failure_output(self):
        marker = "test-only-output-marker"
        failed = subprocess.CompletedProcess(
            ["sops", "--decrypt"], 9, stdout=marker.encode(), stderr=marker.encode()
        )
        stdout, stderr = io.StringIO(), io.StringIO()
        with mock.patch.object(subprocess, "run", return_value=failed) as run_process:
            with redirect_stdout(stdout), redirect_stderr(stderr):
                with self.assertRaisesRegex(RuntimeError, r"sops failed \(exit 9\); output suppressed") as error:
                    keys.run(["sops", "--decrypt"], b"test-only-input")

        run_process.assert_called_once_with(
            ["sops", "--decrypt"], input=b"test-only-input", capture_output=True, check=False
        )
        self.assertNotIn(marker, str(error.exception))
        self.assertEqual("", stdout.getvalue())
        self.assertEqual("", stderr.getvalue())

    def test_main_encrypts_round_trip_and_submits_only_github_key(self):
        with tempfile.TemporaryDirectory() as temporary:
            original = b"# preserve this synthetic startup content\n"
            paths = self.make_paths(Path(temporary), original)
            startup, backup, destination = paths
            calls = []
            encrypted_document = {}
            github_list_responses = iter((b"[]", b'[ {"name": "CALCIFER_OTLP_API_KEY"} ]'))

            def fake_subprocess_run(arguments, input=None, capture_output=False, check=True):
                self.assertTrue(capture_output)
                self.assertFalse(check)
                calls.append((list(arguments), input))
                if arguments[:3] == ["gh", "secret", "list"]:
                    return subprocess.CompletedProcess(arguments, 0, next(github_list_responses), b"")
                if arguments[:2] == ["sops", "--encrypt"]:
                    self.assertIsNotNone(input)
                    document = json.loads(input)
                    encrypted_document["value"] = document
                    return subprocess.CompletedProcess(
                        arguments, 0, b"synthetic-encrypted-document", b""
                    )
                if arguments[:2] == ["sops", "filestatus"]:
                    return subprocess.CompletedProcess(arguments, 0, b'{"encrypted": true}', b"")
                if arguments[:2] == ["sops", "--decrypt"]:
                    return subprocess.CompletedProcess(
                        arguments, 0, json.dumps(encrypted_document["value"]).encode(), b""
                    )
                if arguments[:3] == ["gh", "secret", "set"]:
                    return subprocess.CompletedProcess(arguments, 0, b"", b"")
                self.fail(f"Unexpected mocked subprocess command: {arguments[0]}")

            stdout, stderr = io.StringIO(), io.StringIO()
            with self.patch_paths(paths), \
                    mock.patch.object(os, "umask"), \
                    mock.patch.object(secrets, "token_urlsafe",
                                      side_effect=[LOCAL_TEST_KEY, GITHUB_TEST_KEY]) as generate_key, \
                    mock.patch.object(subprocess, "run", side_effect=fake_subprocess_run):
                with redirect_stdout(stdout), redirect_stderr(stderr):
                    keys.main()

            generate_key.assert_has_calls([mock.call(32), mock.call(32)])
            self.assertEqual(2, generate_key.call_count)
            document = encrypted_document["value"]
            self.assertEqual("v1", document["apiVersion"])
            self.assertEqual("public-otlp-auth", document["metadata"]["name"])
            self.assertEqual("monitoring", document["metadata"]["namespace"])
            self.assertEqual(keys.auth_config((LOCAL_TEST_KEY, GITHUB_TEST_KEY)),
                             json.loads(document["stringData"]["auth.yaml"]))

            github_set_calls = [
                (arguments, payload) for arguments, payload in calls
                if arguments[:3] == ["gh", "secret", "set"]
            ]
            self.assertEqual(1, len(github_set_calls))
            self.assertEqual(GITHUB_TEST_KEY.encode(), github_set_calls[0][1])
            self.assertNotEqual(LOCAL_TEST_KEY.encode(), github_set_calls[0][1])
            self.assertEqual(2, sum(arguments[:3] == ["gh", "secret", "list"]
                                    for arguments, _ in calls))
            self.assertTrue(any(arguments[:2] == ["sops", "--decrypt"]
                                for arguments, _ in calls))

            self.assertEqual(original, backup.read_bytes())
            self.assertTrue(startup.read_bytes().startswith(original))
            self.assertIn(f"export {keys.KEY}={LOCAL_TEST_KEY}\n".encode(), startup.read_bytes())
            self.assertEqual(b"synthetic-encrypted-document", destination.read_bytes())
            self.assertEqual(0o600, stat.S_IMODE(startup.stat().st_mode))
            self.assertEqual(0o600, stat.S_IMODE(backup.stat().st_mode))
            self.assertEqual(0o600, stat.S_IMODE(destination.stat().st_mode))
            for output in (stdout.getvalue(), stderr.getvalue()):
                self.assertNotIn(LOCAL_TEST_KEY, output)
                self.assertNotIn(GITHUB_TEST_KEY, output)

    def test_main_refuses_existing_local_export(self):
        content = f"export {keys.KEY}=test-only-existing-local-value\n".encode()
        with tempfile.TemporaryDirectory() as temporary:
            paths = self.make_paths(Path(temporary), content)
            run_helper = mock.Mock()
            with self.patch_paths(paths), mock.patch.object(os, "umask"), \
                    mock.patch.dict(vars(keys), {"run": run_helper}):
                with self.assertRaisesRegex(RuntimeError, "local export already exists"):
                    keys.main()
            run_helper.assert_not_called()
            self.assertEqual(content, paths[0].read_bytes())
            self.assertFalse(paths[2].exists())

    def test_main_refuses_existing_github_secret(self):
        content = b"# no local key yet\n"
        with tempfile.TemporaryDirectory() as temporary:
            paths = self.make_paths(Path(temporary), content)
            existing = b'[{"name":"CALCIFER_OTLP_API_KEY"}]'
            run_helper = mock.Mock(return_value=existing)
            with self.patch_paths(paths), mock.patch.object(os, "umask"), \
                    mock.patch.dict(vars(keys), {"run": run_helper}):
                with self.assertRaisesRegex(RuntimeError, "GitHub secret already exists"):
                    keys.main()
            run_helper.assert_called_once_with(
                ["gh", "secret", "list", "--repo", keys.REPOSITORY, "--json", "name"]
            )
            self.assertEqual(content, paths[0].read_bytes())
            self.assertFalse(paths[2].exists())

    def test_main_refuses_existing_destination_without_overwriting(self):
        with tempfile.TemporaryDirectory() as temporary:
            paths = self.make_paths(Path(temporary))
            paths[2].write_bytes(b"test-only-existing-destination")
            run_helper = mock.Mock()
            with self.patch_paths(paths), mock.patch.object(os, "umask"), \
                    mock.patch.dict(vars(keys), {"run": run_helper}):
                with self.assertRaisesRegex(RuntimeError, "destination or backup already exists"):
                    keys.main()
            run_helper.assert_not_called()
            self.assertEqual(b"test-only-existing-destination", paths[2].read_bytes())

    def test_main_refuses_existing_backup_without_overwriting(self):
        with tempfile.TemporaryDirectory() as temporary:
            paths = self.make_paths(Path(temporary))
            paths[1].write_bytes(b"test-only-existing-backup")
            run_helper = mock.Mock()
            with self.patch_paths(paths), mock.patch.object(os, "umask"), \
                    mock.patch.dict(vars(keys), {"run": run_helper}):
                with self.assertRaisesRegex(RuntimeError, "destination or backup already exists"):
                    keys.main()
            run_helper.assert_not_called()
            self.assertEqual(b"test-only-existing-backup", paths[1].read_bytes())


if __name__ == "__main__":
    unittest.main()