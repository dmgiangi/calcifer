import json
import unittest
from pathlib import Path
from update.live import collect, apply_observations
from update.model import Component
from update.platform import check_platform
from update.tools import ToolResult


class LiveTest(unittest.TestCase):
    def test_multi_node_versions_are_retained_and_uniform_nodes_are_compared(self):
        host = Component('k3s', 'k3s', 'host', clusters=['home'])
        nodes = [Component(name, 'nodes', 'kubectl', observed='v1.31.2+k3s1', clusters=['home'])
                 for name in ('one', 'two')]
        apply_observations([host], 'home', nodes)
        self.assertEqual(json.loads(host.metadata['node_versions']), {'one': 'v1.31.2+k3s1', 'two': 'v1.31.2+k3s1'})
        check_platform(host, {}, lambda *_: 'v1.31.3+k3s1')
        self.assertEqual(host.status, 'update-available')

    def test_mixed_and_partial_node_versions_do_not_collapse(self):
        host = Component('k3s', 'k3s', 'host', clusters=['home'])
        nodes = [Component('one', 'nodes', 'kubectl', observed='v1.31.2+k3s1'),
                 Component('two', 'nodes', 'kubectl', observed='v1.32.0+k3s1')]
        apply_observations([host], 'home', nodes)
        check_platform(host, {}, lambda *_: self.fail('source lookup must not mask mixed node versions'))
        self.assertIsNone(host.observed)
        self.assertEqual(host.status, 'divergent')
        nodes[1].observed = None
        apply_observations([host], 'home', nodes)
        check_platform(host, {}, lambda *_: self.fail('incomplete observations do not establish a baseline'))
        self.assertEqual((host.observed, host.status), (None, 'unknown'))

    def test_failed_node_observation_remains_unavailable(self):
        host = Component('k3s', 'k3s', 'host', clusters=['home'], observed='old')
        failure = Component('nodes', 'cluster-observation', 'kubectl', status='unavailable')
        apply_observations([host], 'home', [failure])
        check_platform(host, {}, lambda *_: self.fail('failed observations remain unavailable without a release lookup'))
        self.assertEqual((host.observed, host.status), (None, 'unavailable'))

    def test_chart_filename_is_not_misreported_as_application_version(self):
        def runner(argv, **kwargs):
            values = [] if 'nodes' in argv or 'flux-system' in argv else [
                {'metadata': {'name': 'traefik'}, 'spec': {'chart': 'https://cluster/static/charts/traefik-34.2.1+up34.2.0.tgz'}}]
            return ToolResult('ok', 0, json.dumps({'items': values}))
        observations = collect('home', 'ctx', Path('/tmp/config'), runner)
        item = Component('traefik', 'k3s-component', 'file', clusters=['home'])
        apply_observations([item], 'home', observations)
        self.assertEqual(item.observed, '34.2.1+up34.2.0')
        self.assertIn('not Traefik', item.metadata['observed_basis'])

    def test_explicit_context_and_partial_failures(self):
        commands = []
        def runner(argv, **kwargs):
            commands.append(argv)
            if "nodes" in argv:
                return ToolResult("ok", 0, json.dumps({"items": [{"metadata": {"name": "node1"}, "status": {"nodeInfo": {"kubeletVersion": "v1.2.3+k3s1"}, "conditions": [{"type": "Ready", "status": "True"}]}}]}))
            return ToolResult("failed", 1)
        items = collect("home", "explicit-home", Path("/tmp/config"), runner)
        self.assertEqual(items[0].observed, "v1.2.3+k3s1")
        self.assertEqual(items[0].status, "healthy")
        self.assertEqual(items[1].status, "unavailable")
        self.assertTrue(all("explicit-home" in command for command in commands))
        self.assertTrue(all("get" in command for command in commands))

    def test_invalid_response_is_reported_without_content(self):
        items = collect("cloud", "ctx", Path("/tmp/config"), lambda *a, **k: ToolResult("ok", 0, "secret"))
        self.assertTrue(all(x.status == "unavailable" for x in items))
        self.assertNotIn("secret", repr(items))

    def test_invalid_node_after_valid_node_cannot_create_partial_baseline(self):
        def runner(argv, **kwargs):
            items = [{'metadata': {'name': 'valid'}, 'status': {'nodeInfo': {'kubeletVersion': 'v1.31.2+k3s1'}}},
                     {'metadata': {'name': 'invalid'}, 'status': {'nodeInfo': {'kubeletVersion': 123}}}]
            return ToolResult('ok', 0, json.dumps({'items': items if 'nodes' in argv else []}))
        observations = collect('home', 'ctx', Path('/tmp/config'), runner)
        host = Component('k3s', 'k3s', 'host', clusters=['home'])
        apply_observations([host], 'home', observations)
        self.assertFalse(any(item.kind == 'nodes' for item in observations))
        self.assertEqual((host.observed, host.status), (None, 'unavailable'))


class FluxDivergenceTest(unittest.TestCase):
    def test_revision_comparison_uses_only_local_head(self):
        def runner(argv, **kwargs):
            if 'nodes' in argv:
                return ToolResult('ok', 0, '{"items": []}')
            return ToolResult('ok', 0, json.dumps({'items': [{'metadata': {'name': 'apps'}, 'status': {'lastAppliedRevision': 'master@sha1:' + 'a' * 40, 'conditions': [{'type': 'Ready', 'status': 'True'}]}}]}))
        items = collect('home', 'ctx', Path('/tmp/config'), runner, desired_revision='b' * 40)
        self.assertEqual(items[0].status, 'divergent')
        self.assertIn('uncommitted', items[0].detail)
