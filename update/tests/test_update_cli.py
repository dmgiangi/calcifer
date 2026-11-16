import io
import json
import subprocess
import sys
import unittest
from pathlib import Path
from rich.console import Console

from update.model import Component, Report
from update.output import render_report

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


class RichReportTest(unittest.TestCase):
    def test_actions_are_grouped_by_dependency_kind_and_quiet_components_summarized(self):
        report = Report('check', components=[
            Component('requests', 'python-dependency', 'app/pyproject.toml', declared='2.0',
                      candidate='2.1', status='update-available', detail='Aggiornamento disponibile'),
            Component('runtime', 'docker-base', 'service/Dockerfile', declared='stable',
                      status='unknown', detail='Tag non confrontabile'),
            Component('stable-action', 'action', '.github/workflows/ci.yml', declared='v4', status='current'),
            Component('service', 'python', 'app/pyproject.toml', status='configured'),
            Component('spring-managed', 'maven-dependency', 'pom.xml', status='parent-managed'),
        ])
        console = Console(file=io.StringIO(), record=True, force_terminal=False, color_system=None, width=100)
        render_report(report, console=console, error_console=console)
        output = console.export_text()
        self.assertIn('Azioni da prendere', output)
        self.assertIn('Dipendenze Python', output)
        self.assertIn('Immagini base Docker', output)
        self.assertIn('Gestito dal parent', output)
        self.assertLess(output.index('Dipendenze Python'), output.index('Immagini base Docker'))
        self.assertIn('requests', output)
        self.assertIn('runtime', output)
        self.assertIn('Nessuna azione immediata', output)
        self.assertIn('Configurato: 1', output)
        self.assertNotIn('stable-action', output)

    def test_empty_action_section_is_explicit(self):
        report = Report('status', components=[Component('known', 'image', 'workload.yaml', status='current')])
        console = Console(file=io.StringIO(), record=True, force_terminal=False, color_system=None)
        render_report(report, console=console, error_console=console)
        self.assertIn('Nessuna azione richiesta', console.export_text())
