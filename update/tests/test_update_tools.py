import sys
import unittest
from pathlib import Path
from update.tools import run_readonly


class ToolsTest(unittest.TestCase):
    def test_failures_do_not_disclose_output(self):
        result = run_readonly([sys.executable, "-c", "import sys; print('token'); sys.exit(2)"], cwd=Path.cwd())
        self.assertEqual(result.status, "failed")
        self.assertNotIn("token", repr(result))

    def test_missing_timeout_and_output_limit(self):
        self.assertEqual(run_readonly(["nonexistent-calcifer-test-command"], cwd=Path.cwd()).status, "missing")
        self.assertEqual(run_readonly([sys.executable, "-c", "import time; time.sleep(2)"], cwd=Path.cwd(), timeout=.05).status, "timeout")
        self.assertEqual(run_readonly([sys.executable, "-c", "print('hello')"], cwd=Path.cwd(), output_limit=2).status, "output-too-large")

    def test_known_environment_credential_is_redacted(self):
        import os
        from unittest.mock import patch
        with patch.dict(os.environ, {"CALCIFER_TEST_TOKEN": "example-secret-value"}):
            result = run_readonly([sys.executable, "-c", "import os; print(os.environ['CALCIFER_TEST_TOKEN'])"], cwd=Path.cwd())
        self.assertEqual(result.stdout, "[REDACTED]\n")

    def test_success_output(self):
        result = run_readonly([sys.executable, "-c", "print('ok')"], cwd=Path.cwd())
        self.assertEqual((result.status, result.stdout), ("ok", "ok\n"))


class WorkspaceTest(unittest.TestCase):
    def test_isolation_cleanup_and_path_validation(self):
        import tempfile
        from update.tools import isolated_workspace
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "manifest.yaml").write_text("original")
            with isolated_workspace(root, [Path("manifest.yaml")]) as work:
                (work / "manifest.yaml").write_text("changed")
                self.assertEqual((root / "manifest.yaml").read_text(), "original")
                saved = work
            self.assertFalse(saved.exists())
            with self.assertRaises(ValueError):
                with isolated_workspace(root, [Path("../outside")]):
                    pass
            (root / "link").symlink_to(root / "manifest.yaml")
            with self.assertRaises(ValueError):
                with isolated_workspace(root, [Path("link")]):
                    pass
