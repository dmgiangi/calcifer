import unittest
from update.helm import check_chart
from update.model import Component


class HelmTest(unittest.TestCase):
    def test_chart_release_and_unresolved_contents(self):
        repo = Component('r', 'helm-repository', 'repo', declared='https://charts.example', clusters=['home'], metadata={'namespace': 'apps'})
        chart = Component('test', 'helm-chart', 'chart', declared='1.0.0', clusters=['home'], metadata={'repository': 'r', 'namespace': 'apps'})
        check_chart(chart, [repo], {}, lambda _: {'test': [{'version': '1.2.0'}, {'version': '2.0.0-rc1'}]})
        self.assertEqual((chart.status, chart.candidate), ('update-available', '1.2.0'))
        self.assertIn('unresolved', chart.detail)

    def test_missing_repo_is_not_current(self):
        chart = Component('test', 'helm-chart', 'chart', declared='1.0.0')
        check_chart(chart, [], {})
        self.assertEqual(chart.status, 'unknown')


class OciChartTest(unittest.TestCase):
    def test_oci_chart_versions_use_registry_tags(self):
        import json
        from unittest.mock import patch
        from update.tools import ToolResult
        repo = Component('r', 'helm-repository', 'repo', declared='oci://ghcr.io/example/charts', clusters=['cloud'], metadata={'namespace': 'apps'})
        chart = Component('demo', 'helm-chart', 'chart', declared='1.0.0', clusters=['cloud'], metadata={'repository': 'r', 'namespace': 'apps'})
        with patch('update.helm.registry.list_tags', return_value=ToolResult('ok', 0, json.dumps({'Tags': ['1.0.0', '1.2.0']}))) as lookup:
            check_chart(chart, [repo], {})
        lookup.assert_called_once_with('ghcr.io/example/charts/demo')
        self.assertEqual((chart.status, chart.candidate), ('update-available', '1.2.0'))
