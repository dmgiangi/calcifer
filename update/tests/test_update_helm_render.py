import tempfile
import unittest
from pathlib import Path
from update.helm import render_chart_images
from update.model import Component
from update.tools import ToolResult


class HelmRenderTest(unittest.TestCase):
    def test_render_images_without_reading_secrets(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / 'release.yaml').write_text('''kind: HelmRelease
metadata:
  name: demo
  namespace: apps
spec:
  chart:
    spec:
      chart: example
      version: 1.0.0
  values:
    replicaCount: 1
''')
            chart = Component('example', 'helm-chart', 'release.yaml', declared='1.0.0', clusters=['home'], metadata={'repository': 'r', 'namespace': 'apps'})
            repo = Component('r', 'helm-repository', 'repo.yaml', declared='https://charts.example', clusters=['home'], metadata={'namespace': 'apps'})
            output = 'kind: Pod\nspec:\n  containers:\n  - image: example/app:1.0.0\n---\nkind: Secret\ndata:\n  image: must-not-appear\n'
            images, error = render_chart_images(chart, [repo], root, lambda *a, **k: ToolResult('ok', 0, output))
            self.assertIsNone(error)
            self.assertEqual(images, ['example/app:1.0.0'])

    def test_external_values_are_reported_unresolved(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / 'release.yaml').write_text('kind: HelmRelease\nmetadata:\n  name: demo\nspec:\n  chart:\n    spec:\n      chart: example\n      version: 1.0.0\n  valuesFrom:\n  - kind: Secret\n')
            chart = Component('example', 'helm-chart', 'release.yaml', declared='1.0.0', clusters=['home'], metadata={'repository': 'r', 'namespace': 'apps'})
            repo = Component('r', 'helm-repository', 'repo.yaml', declared='https://charts.example', clusters=['home'], metadata={'namespace': 'apps'})
            images, error = render_chart_images(chart, [repo], root)
            self.assertIsNone(images)
            self.assertIn('external values', error)
