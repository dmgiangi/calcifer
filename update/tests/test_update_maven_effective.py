import tempfile
import unittest
from pathlib import Path
from update.maven_effective import effective_versions
from update.tools import ToolResult


class EffectivePomTest(unittest.TestCase):
    def test_resolves_only_unique_versions_and_uses_copy(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / 'pom.xml').write_text('<project/>')
            def runner(argv, *, cwd, **kwargs):
                self.assertNotEqual(cwd, root)
                output = next(arg.split('=', 1)[1] for arg in argv if arg.startswith('-Doutput='))
                Path(output).write_text('<project><dependencies><dependency><groupId>x</groupId><artifactId>y</artifactId><version>1.2.3</version></dependency></dependencies></project>')
                return ToolResult('ok', 0)
            values, error = effective_versions(root, 'pom.xml', runner)
            self.assertIsNone(error)
            self.assertEqual(values, {'x:y': '1.2.3'})
            self.assertEqual((root / 'pom.xml').read_text(), '<project/>')

    def test_missing_tool_is_explicit(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / 'pom.xml').write_text('<project/>')
            values, error = effective_versions(root, 'pom.xml', lambda *a, **k: ToolResult('missing'))
            self.assertEqual(values, {})
            self.assertIn('missing', error)
