import tempfile
import unittest
from pathlib import Path
from update.dependency_prepare import prepare_dependency
from update.tools import ToolResult


class DependencyPreparationTest(unittest.TestCase):
    def test_maven_preview_and_write_with_native_tool(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            project = root / 'authorization-server'
            project.mkdir()
            pom = project / 'pom.xml'
            old = '<project><dependencies><dependency><groupId>x</groupId><artifactId>y</artifactId><version>1.0.0</version></dependency></dependencies></project>'
            pom.write_text(old)
            def runner(argv, *, cwd, **_):
                if any('use-dep-version' in arg for arg in argv):
                    file = cwd / 'pom.xml'
                    file.write_text(file.read_text().replace('1.0.0', '1.1.0'))
                return ToolResult('ok', 0)
            options = dict(fetch=lambda *_: ['1.0.0', '1.1.0'], runner=runner, require_clean=lambda *a: None)
            diff, token = prepare_dependency(root, 'maven', 'authorization-server', 'x:y', '1.1.0', **options)
            self.assertIn('1.1.0', diff)
            self.assertEqual(pom.read_text(), old)
            prepare_dependency(root, 'maven', 'authorization-server', 'x:y', '1.1.0', write=True, expected=token, **options)
            self.assertIn('1.1.0', pom.read_text())

    def test_missing_tool_or_unexpected_file_cannot_write(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / 'go.mod').write_text('module example.test/main\nrequire example.test/lib v1.0.0\n')
            (root / 'go.sum').write_text('example.test/lib v1.0.0 h1:example\n')
            kwargs = dict(fetch=lambda *_: ['v1.0.0', 'v1.1.0'], require_clean=lambda *a: None)
            with self.assertRaisesRegex(ValueError, 'missing'):
                prepare_dependency(root, 'go', '.', 'example.test/lib', 'v1.1.0', runner=lambda *a, **k: ToolResult('missing'), **kwargs)
            self.assertIn('v1.0.0', (root / 'go.mod').read_text())
            def unexpected(argv, *, cwd, **_):
                (cwd / 'go.mod').write_text('module example.test/main\nrequire example.test/lib v1.1.0\n')
                (cwd / 'unexpected').write_text('x')
                return ToolResult('ok', 0)
            with self.assertRaisesRegex(ValueError, 'unexpected'):
                prepare_dependency(root, 'go', '.', 'example.test/lib', 'v1.1.0', runner=unexpected, **kwargs)
            self.assertFalse((root / 'unexpected').exists())


class PythonPreparationTest(unittest.TestCase):
    def test_uv_updates_both_files_and_validates_lock(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'pyproject.toml').write_text('[project]\nname="sample"\ndependencies=["demo==1.0.0"]\n')
            (root / 'uv.lock').write_text('[[package]]\nname="demo"\nversion="1.0.0"\n')
            def runner(argv, *, cwd, **_):
                if argv[1] == 'add':
                    for name in ('pyproject.toml', 'uv.lock'):
                        path = cwd / name
                        path.write_text(path.read_text().replace('1.0.0', '1.1.0'))
                return ToolResult('ok', 0)
            diff, token = prepare_dependency(root, 'python', '.', 'demo', '1.1.0',
                                             fetch=lambda _: ['1.0.0', '1.1.0'], runner=runner,
                                             require_clean=lambda *a: None)
            self.assertIn('1.1.0', diff)
            self.assertEqual(len(token), 64)
            self.assertIn('1.0.0', (root / 'uv.lock').read_text())


class GoPreparationTest(unittest.TestCase):
    def test_go_graph_updates_are_previewed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            mod = root / 'go.mod'
            mod.write_text('module example.test/main\nrequire example.test/lib v1.0.0\n')
            (root / 'go.sum').write_text('example.test/lib v1.0.0 h1:example\n')
            def runner(argv, *, cwd, **_):
                if argv[1] == 'get':
                    for name in ('go.mod', 'go.sum'):
                        path = cwd / name
                        path.write_text(path.read_text().replace('v1.0.0', 'v1.1.0'))
                return ToolResult('ok', 0)
            options = dict(fetch=lambda *_: ['v1.0.0', 'v1.1.0'], runner=runner, require_clean=lambda *a: None)
            diff, fingerprint = prepare_dependency(root, 'go', '.', 'example.test/lib', 'v1.1.0', **options)
            self.assertIn('v1.1.0', diff)
            self.assertIn('v1.0.0', mod.read_text())
            prepare_dependency(root, 'go', '.', 'example.test/lib', 'v1.1.0', write=True, expected=fingerprint, **options)
            self.assertIn('v1.1.0', mod.read_text())
