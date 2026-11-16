import contextlib
import importlib.util
import io
import json
from unittest.mock import patch
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("ota_status", Path(__file__).parents[1] / "scripts" / "ota-status.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class StatusTest(unittest.TestCase):
    def test_success_requires_matching_installed_distribution(self):
        action = {"status": "finished", "_links": {"distributionset": {"href": "https://ota.example/rest/v1/distributionsets/12"}}}
        self.assertEqual(module.classify(action, {"id": 12}), "success")
        self.assertEqual(module.classify(action, {"id": 13}), "unknown")
        self.assertEqual(module.classify(action, {}), "unknown")

    def test_failed_and_pending_are_distinct(self):
        self.assertEqual(module.classify({"status": "error"}, {}), "failure")
        self.assertEqual(module.classify({"status": "running"}, {}), "pending")
        self.assertEqual(module.classify({"status": "retrieved"}, {}), "pending")
        self.assertEqual(module.classify({"status": "new-unknown-state"}, {}), "unknown")

    def test_command_reports_unknown_for_unmatched_installed_release(self):
        action = {"id": 7, "status": "finished", "_links": {"distributionset": {"href": "https://ota.example/rest/v1/distributionsets/12"}}}
        output = io.StringIO()
        with patch.object(module.sys, "argv", ["ota-status.py", "7"]), patch.object(module, "read_credentials", return_value=("user", "password")), patch.object(module.Hawkbit, "get", side_effect=[action, {"id": 13}]), contextlib.redirect_stdout(output):
            self.assertEqual(module.main(), 2)
        self.assertEqual(json.loads(output.getvalue())["outcome"], "unknown")
        self.assertNotIn("password", output.getvalue())

    def test_rejects_untrusted_server_origins_and_path_injection(self):
        for value in ("http://ota.example", "https://user:pass@ota.example", "https://ota.example/path", "https://example.org", "https://ota.calcifer.tech:8443"):
            with self.subTest(value=value), self.assertRaises(module.StatusError):
                module.Hawkbit(value, "user", "password")
        module.Hawkbit("https://ota.calcifer.tech", "user", "password")
        with self.assertRaises(module.StatusError):
            module.safe_segment("../secret")


if __name__ == "__main__":
    unittest.main()
