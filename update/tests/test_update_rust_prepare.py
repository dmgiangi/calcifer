import tempfile
import unittest
from pathlib import Path
from update.rust_prepare import prepare_rust
from update.tools import ToolResult


class RustPrepareTest(unittest.TestCase):
    def test_lockfile_preparation_is_isolated_and_previewed(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            project = root / 'therment'
            project.mkdir()
            (project / 'Cargo.toml').write_text('[package]\nname="example"\nversion="1.0.0"\n')
            original = 'version = 4\n[[package]]\nname="log"\nversion="0.4.0"\n'
            (project / 'Cargo.lock').write_text(original)
            def runner(argv, *, cwd, **_):
                if '--locked' in argv:
                    return ToolResult('failed', 1)
                (cwd / 'Cargo.lock').write_text(original.replace('0.4.0', '0.4.1'))
                return ToolResult('ok', 0)
            options = dict(fetch=lambda _: '0.4.1', runner=runner, require_clean=lambda *a: None)
            diff, token = prepare_rust(root, 'therment', 'log', '0.4.1', **options)
            self.assertIn('+version="0.4.1"', diff)
            self.assertEqual((project / 'Cargo.lock').read_text(), original)
            prepare_rust(root, 'therment', 'log', '0.4.1', write=True, expected=token, **options)
            self.assertIn('0.4.1', (project / 'Cargo.lock').read_text())


class RustUnexpectedOutputTest(unittest.TestCase):
    def test_unexpected_file_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            project = root / 'therment'
            project.mkdir()
            (project / 'Cargo.toml').write_text('[package]\nname="example"\nversion="1.0.0"\n')
            original = 'version=4\n[[package]]\nname="log"\nversion="0.4.0"\n'
            (project / 'Cargo.lock').write_text(original)
            def runner(argv, *, cwd, **_):
                (cwd / 'Cargo.lock').write_text(original.replace('0.4.0', '0.4.1'))
                (cwd / 'unexpected').write_text('unexpected')
                return ToolResult('ok', 0)
            with self.assertRaisesRegex(ValueError, 'unexpected'):
                prepare_rust(root, 'therment', 'log', '0.4.1', fetch=lambda _: '0.4.1', runner=runner, require_clean=lambda *a: None)
            self.assertEqual((project / 'Cargo.lock').read_text(), original)
            self.assertFalse((project / 'unexpected').exists())
