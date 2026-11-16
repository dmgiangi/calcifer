import json
import unittest
from pathlib import Path
from update.live import collect
from update.tools import ToolResult


class LiveTest(unittest.TestCase):
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


class FluxDivergenceTest(unittest.TestCase):
    def test_revision_comparison_uses_only_local_head(self):
        def runner(argv, **kwargs):
            if 'nodes' in argv:
                return ToolResult('ok', 0, '{"items": []}')
            return ToolResult('ok', 0, json.dumps({'items': [{'metadata': {'name': 'apps'}, 'status': {'lastAppliedRevision': 'master@sha1:' + 'a' * 40, 'conditions': [{'type': 'Ready', 'status': 'True'}]}}]}))
        items = collect('home', 'ctx', Path('/tmp/config'), runner, desired_revision='b' * 40)
        self.assertEqual(items[0].status, 'divergent')
        self.assertIn('uncommitted', items[0].detail)
