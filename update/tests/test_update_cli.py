import json
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class UpdateCLITest(unittest.TestCase):
    def invoke(self, *args):
        return subprocess.run([sys.executable, "-m", "update", *args], cwd=ROOT,
                              text=True, capture_output=True, check=False)

    def test_installed_entry_point(self):
        result = subprocess.run(['calcifer-update', '--help'], cwd=ROOT, text=True,
                                capture_output=True, check=False)
        self.assertEqual(result.returncode, 0)
        self.assertIn('calcifer-update', result.stdout)

    def test_help_lists_commands(self):
        result = self.invoke("--help")
        self.assertEqual(result.returncode, 0)
        for command in ("status", "check", "apply"):
            self.assertIn(command, result.stdout)

    def test_json_reports_unimplemented_failure(self):
        result = self.invoke("status", "--json")
        self.assertNotEqual(result.returncode, 0)
        report = json.loads(result.stdout)
        self.assertEqual(report["command"], "status")
        self.assertTrue(report["errors"])

    def test_human_error_and_apply_fail_closed(self):
        result = self.invoke("apply", "example", "--version", "1", "--write")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("no changes were made", result.stderr)


class OfflineTest(unittest.TestCase):
    def test_offline_check_never_calls_sources(self):
        from unittest.mock import patch
        from update.__main__ import parser, run
        with patch('update.__main__.check_all', side_effect=AssertionError('network check called')):
            report = run(parser().parse_args(['check', '--offline']))
        self.assertTrue(any('Offline mode' in error for error in report.errors))

    def test_offline_live_conflict_is_rejected(self):
        from update.__main__ import parser, run
        report = run(parser().parse_args(['check', '--offline', '--live']))
        self.assertTrue(any('cannot be combined' in error for error in report.errors))


class ProgressOutputTest(unittest.TestCase):
    def test_json_remains_valid_while_progress_goes_to_stderr(self):
        result = subprocess.run([sys.executable, '-m', 'update', 'check', '--offline', '--json'],
                                cwd=ROOT, text=True, capture_output=True, check=False)
        self.assertEqual(json.loads(result.stdout)['command'], 'check')
        self.assertIn('[check]', result.stderr)
        self.assertIn('Modalità offline', result.stderr)
        self.assertIn('Verifica terminata', result.stderr)
