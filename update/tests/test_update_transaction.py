import tempfile
import unittest
from pathlib import Path
from update.transaction import commit, preview


class TransactionTest(unittest.TestCase):
    def test_preview_stale_and_atomic_rollback(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / 'a').write_text('old a\n')
            (root / 'b').write_text('old b\n')
            changes, diff = preview(root, {'a': b'new a\n', 'b': b'new b\n'}, {'a', 'b'})
            self.assertIn('+new a', diff)
            self.assertEqual((root / 'a').read_text(), 'old a\n')
            (root / 'b').write_text('other\n')
            with self.assertRaises(ValueError):
                commit(root, changes, {'a', 'b'})
            self.assertEqual((root / 'a').read_text(), 'old a\n')
            (root / 'b').write_text('old b\n')
            from update.transaction import _replace
            def fail_second(path, data):
                if path.name == 'b':
                    raise OSError('test failure')
                _replace(path, data)
            with self.assertRaises(OSError):
                commit(root, changes, {'a', 'b'}, write=fail_second)
            self.assertEqual((root / 'a').read_text(), 'old a\n')
            self.assertEqual((root / 'b').read_text(), 'old b\n')
            commit(root, changes, {'a', 'b'})
            self.assertEqual((root / 'a').read_text(), 'new a\n')
            self.assertEqual((root / 'b').read_text(), 'new b\n')

    def test_unexpected_and_symlink_target_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / 'file').write_text('old')
            (root / 'link').symlink_to(root / 'file')
            with self.assertRaises(ValueError):
                preview(root, {'file': b'new'}, set())
            with self.assertRaises(ValueError):
                preview(root, {'link': b'new'}, {'link'})
