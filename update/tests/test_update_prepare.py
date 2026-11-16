import tempfile
import unittest
from pathlib import Path
from update.model import Component
from update.prepare import prepare_chart


class PrepareChartTest(unittest.TestCase):
    def test_preview_write_and_stale_protection(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            target = root / 'chart.yaml'
            target.write_text('apiVersion: helm.toolkit.fluxcd.io/v2\nkind: HelmRelease\nspec:\n  chart:\n    spec:\n      chart: demo\n      version: "1.0.0"\n')
            def make():
                return [Component('demo', 'helm-chart', 'chart.yaml', declared='1.0.0', clusters=['home'], metadata={'repository': 'repo', 'namespace': 'apps'}), Component('repo', 'helm-repository', 'repo.yaml', declared='https://example.test', clusters=['home'], metadata={'namespace': 'apps'})]
            kwargs = dict(fetch=lambda _: {'demo': [{'version': '1.1.0'}]}, validator=lambda *a: None, require_clean=lambda *a: None)
            diff, token = prepare_chart(root, 'demo', '1.1.0', 'home', make(), **kwargs)
            self.assertIn('+      version: "1.1.0"', diff)
            self.assertIn('1.0.0', target.read_text())
            with self.assertRaises(ValueError):
                prepare_chart(root, 'demo', '1.1.0', 'home', make(), write=True, expected='wrong', **kwargs)
            self.assertIn('1.0.0', target.read_text())
            original = target.read_text()
            target.write_text(original + '# changed after review\n')
            with self.assertRaises(ValueError):
                prepare_chart(root, 'demo', '1.1.0', 'home', make(), write=True, expected=token, **kwargs)
            target.write_text(original)
            prepare_chart(root, 'demo', '1.1.0', 'home', make(), write=True, expected=token, **kwargs)
            self.assertIn('1.1.0', target.read_text())

    def test_validator_failure_does_not_write(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            target = root / 'chart.yaml'
            original = 'kind: HelmRelease\nspec:\n  chart:\n    spec:\n      chart: demo\n      version: 1.0.0\n'
            target.write_text(original)
            items = [Component('demo', 'helm-chart', 'chart.yaml', declared='1.0.0', clusters=['home'], metadata={'repository': 'r', 'namespace': 'n'}), Component('r', 'helm-repository', 'repo', declared='https://test', clusters=['home'], metadata={'namespace': 'n'})]
            def fail(*args):
                raise ValueError('invalid')
            with self.assertRaises(ValueError):
                prepare_chart(root, 'demo', '1.1.0', 'home', items, fetch=lambda _: {'demo': [{'version':'1.1.0'}]}, validator=fail, require_clean=lambda *a: None)
            self.assertEqual(target.read_text(), original)
