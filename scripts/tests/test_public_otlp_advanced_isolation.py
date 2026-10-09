#!/usr/bin/env python3
"""Isolated guard tests: no Docker, network, credentials or cluster access.

Run with Python -B; the runner reports fixed outcomes/counts only, never unittest
tracebacks, mock arguments, response bodies or exception details.
"""

from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
import secrets
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import check_public_otlp_advanced as advanced


class IsolationTests(unittest.TestCase):
    def setUp(self):
        self.tokens = (secrets.token_urlsafe(32), secrets.token_urlsafe(32))
        self.sandbox = advanced.BoundedSandbox(self.tokens)
        self.temporary = tempfile.TemporaryDirectory(prefix="otlp-advanced-unit-", dir="/tmp")
        self.addCleanup(self.temporary.cleanup)
        self.test = advanced.Acceptance(self.sandbox, Path(self.temporary.name))

    def test_import_preserves_runtime_command(self):
        self.assertIsNot(advanced.runtime.command, advanced.docker)

    def test_credentials_only_in_restricted_mounted_file(self):
        self.assertEqual(self.test.auth.stat().st_mode & 0o777, 0o600)
        config = json.loads(self.test.auth.read_text())
        self.assertEqual({user["bearer_token"] for user in config["users"]}, set(self.tokens))

    def test_docker_creations_are_resource_bounded_without_secret_argv(self):
        cid = "a" * 64
        completed = subprocess.CompletedProcess([], 0, cid.encode(), b"")
        with patch.object(advanced.subprocess, "run", return_value=completed) as run:
            advanced.docker(["docker", "create", "--tmpfs", "/tmp", "--memory", "128m",
                             "--network", self.sandbox.network, advanced.IMAGES["auth"],
                             "-auth.config=" + str(self.test.auth)])
        argv = run.call_args.args[0]
        for token in self.tokens:
            self.assertFalse(any(token in item for item in argv))
        for flag in ("--pull=never", "--cpus=1", "--memory-swap", "--pids-limit=128",
                     "--cap-drop=ALL", "--log-opt=max-size=2m", "--log-opt=max-file=1"):
            self.assertIn(flag, argv)
        self.assertEqual(argv[argv.index("--tmpfs") + 1], "/tmp:size=64m,mode=1777")
        self.assertEqual(argv[argv.index("--memory-swap") + 1], "128m")
        self.assertTrue(run.call_args.kwargs["capture_output"])
        self.assertEqual(run.call_args.kwargs["timeout"], 12)

    def test_failed_subprocess_output_never_becomes_exception(self):
        completed = subprocess.CompletedProcess([], 1, self.tokens[0].encode(), self.tokens[1].encode())
        with patch.object(advanced.subprocess, "run", return_value=completed):
            with self.assertRaises(advanced.CheckFailed) as failure:
                advanced.docker(["docker", "image", "inspect", advanced.IMAGES["alloy"]])
        self.assertEqual(str(failure.exception), "")

    def test_cleanup_uses_exact_owned_ids(self):
        self.sandbox.containers = ["a" * 64, "b" * 64]
        self.sandbox.network_id = "c" * 64
        with patch.object(self.sandbox, "check_logs") as logs, patch.object(advanced, "docker") as docker:
            self.sandbox.close()
        self.assertEqual([call.args[0] for call in docker.call_args_list], [
            ["docker", "rm", "-f", "b" * 64], ["docker", "rm", "-f", "a" * 64],
            ["docker", "network", "rm", "c" * 64]])
        self.assertEqual(logs.call_count, 2)
        self.assertEqual(self.sandbox.containers, [])
        self.assertEqual(self.sandbox.network_id, "")

    def test_cleanup_continues_after_log_guard_failure(self):
        self.sandbox.containers = ["a" * 64, "b" * 64]
        self.sandbox.network_id = "c" * 64
        with patch.object(self.sandbox, "check_logs", side_effect=advanced.CheckFailed), \
                patch.object(advanced, "docker") as docker:
            with self.assertRaises(advanced.CheckFailed):
                self.sandbox.close()
        self.assertEqual(docker.call_count, 3)
        self.assertEqual(self.sandbox.containers, [])

    def test_retire_rejects_unowned_id(self):
        with patch.object(advanced, "docker") as docker:
            with self.assertRaises(advanced.CheckFailed):
                self.sandbox.retire("d" * 64)
        docker.assert_not_called()

    def test_failed_start_still_tracks_id_for_cleanup(self):
        cid = "a" * 64
        with patch.object(advanced, "docker", side_effect=[cid.encode(), advanced.CheckFailed]):
            with self.assertRaises(advanced.CheckFailed):
                self.sandbox.start(advanced.IMAGES["logs"], 9428, "test-only", [])
        self.assertEqual(self.sandbox.containers, [cid])

    def test_http_rejects_off_sandbox_and_credential_urls_before_request(self):
        with patch.object(advanced.runtime, "request") as request:
            for url in ("http://example.invalid:80", "http://localhost:80", "https://127.0.0.1:80",
                        "http://127.0.0.1", "http://user@127.0.0.1:80"):
                with self.assertRaises(advanced.CheckFailed):
                    self.test.http(url, {}, self.tokens[0])
        request.assert_not_called()

    def test_redirect_is_rejected(self):
        with self.assertRaises(advanced.CheckFailed):
            advanced.NoRedirect().redirect_request(None, None, 302, "", {}, "http://example.invalid")

    def test_response_credential_guard(self):
        with patch.object(advanced.runtime, "request", return_value=(200, self.tokens[0].encode())):
            with self.assertRaises(advanced.CheckFailed):
                self.test.http("http://127.0.0.1:8427", {}, self.tokens[0])

    def test_sampling_export_barrier_counts_only_retained_decisions(self):
        metrics = [("decisions_total", '{sampled="true",decision="sampled"}', 7),
                   ("decisions_total", '{sampled="false",decision="not_sampled"}', 9),
                   ("other", '{sampled="true"}', 99),
                   ("decisions_total", '{not_sampled="true"}', 99)]
        with patch.object(self.test, "metrics", return_value=metrics):
            self.assertEqual(self.test.count("decisions", sampled=True), 7)
            self.assertEqual(self.test.count("decisions", sampled=False), 9)

    def test_container_log_disclosure_guards(self):
        for output in (self.tokens[0].encode(), advanced.runtime.BODY_MARKER.encode(),
                       b"Authorization: redacted", b"Bearer redacted"):
            with patch.object(advanced, "docker", return_value=output):
                with self.assertRaises(advanced.CheckFailed):
                    self.sandbox.check_logs("a" * 64)

    def test_test_only_replacements_do_not_write_production_config(self):
        original = advanced.ALLOY.read_bytes()
        result = self.test.test_config("test-only-unit", {'check_interval = "1s"': 'check_interval = "100ms"'})
        self.assertEqual(result.parent, Path(self.temporary.name))
        self.assertEqual(advanced.ALLOY.read_bytes(), original)
        self.assertEqual(result.read_text().count('check_interval = "100ms"'), 2)
        with self.assertRaises(advanced.CheckFailed):
            self.test.test_config("test-only-unit", {"nonexistent sentinel": "replacement"})

    def test_backend_restart_refreshes_ephemeral_query_port(self):
        self.test.backend_ids["logs"] = "a" * 64
        self.test.backends["logs"] = "http://127.0.0.1:12345"
        with patch.object(advanced, "docker", side_effect=[b"", b"127.0.0.1:23456\n"]), \
                patch.object(self.test, "http", return_value=(200, b"")) as http:
            self.test.restart_backend("logs")
        self.assertEqual(self.test.backends["logs"], "http://127.0.0.1:23456")
        http.assert_called_once_with("http://127.0.0.1:23456/health")

    def test_memory_refusal_reads_pinned_processor_counter_names(self):
        counters = {"otelcol_processor_memory_limiter_refused_log_records": 2,
                    "otelcol_processor_memory_limiter_refused_spans": 4}
        state = json.dumps({"Running": True, "OOMKilled": False}).encode()
        with patch.object(self.test, "pipeline"), patch.object(advanced.time, "sleep"), \
                patch.object(self.test, "ingest", return_value=(503, b"")), \
                patch.object(self.test, "count", side_effect=lambda name: counters.get(name, 0)) as count, \
                patch.object(self.test, "log_rows", return_value=set()), \
                patch.object(advanced, "docker", return_value=state):
            self.test.memory_refusal()
        self.assertEqual({call.args[0] for call in count.call_args_list}, set(counters))

    def test_direct_backend_fixture_is_sent_as_protobuf_without_auth(self):
        payload = self.test.log_protobuf(400, 123456789)
        self.assertIsInstance(payload, bytes)
        self.assertIn(advanced.runtime.BODY_MARKER.encode(), payload)
        self.assertIn(b"acceptance.seq", payload)
        with patch.object(advanced.urllib.request, "urlopen") as urlopen:
            response = urlopen.return_value.__enter__.return_value
            response.status, response.read.return_value = 200, b""
            self.assertEqual(self.test.http("http://127.0.0.1:9428", payload), (200, b""))
        request = urlopen.call_args.args[0]
        self.assertEqual(request.data, payload)
        self.assertEqual(request.get_header("Content-type"), "application/x-protobuf")
        self.assertIsNone(request.get_header("Authorization"))


if __name__ == "__main__":
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(IsolationTests)
    # unittest failures normally print arbitrary response/argv values; suppress
    # that output by construction rather than attempting post-hoc redaction.
    capture = io.StringIO()
    with redirect_stdout(capture), redirect_stderr(capture):
        result = unittest.TextTestRunner(stream=capture).run(suite)
    print("PASS:" if result.wasSuccessful() else "FAIL:", "advanced-isolation-unit",
          "tests=" + str(result.testsRun), "failures=" + str(len(result.failures)),
          "errors=" + str(len(result.errors)), flush=True)
    raise SystemExit(not result.wasSuccessful())