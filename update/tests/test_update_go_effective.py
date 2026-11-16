import tempfile
import unittest
from pathlib import Path
from update.go_effective import selected_modules
from update.tools import ToolResult


class GoEffectiveTest(unittest.TestCase):
    def test_selected_graph_is_parsed_in_copy(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'go.mod').write_text('module example.test/project\n')
            def runner(argv, *, cwd, **_):
                self.assertNotEqual(cwd, root)
                return ToolResult('ok', 0, '{"Path":"example.test/dep","Version":"v1.2.3"}\n{"Path":"example.test/replaced","Version":"v1.0.0","Replace":{"Path":"../local"}}')
            graph, error = selected_modules(root, 'go.mod', runner)
            self.assertIsNone(error)
            self.assertEqual(graph['example.test/dep'], ('v1.2.3', False))
            self.assertTrue(graph['example.test/replaced'][1])
