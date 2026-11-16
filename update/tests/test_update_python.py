import unittest
from update.model import Component
from update.python_packages import check_python


class PythonCheckTest(unittest.TestCase):
    def test_stable_releases_and_exact_pin(self):
        item = Component("sample", "python-dependency", "pyproject.toml", declared="sample==1.0")
        check_python(item, {}, lambda _: ["1.0", "2.0rc1", "1.2", "bad"])
        self.assertEqual((item.status, item.candidate), ("update-available", "1.2"))

    def test_range_does_not_invent_installed_version(self):
        item = Component("sample", "python-dependency", "pyproject.toml", declared="sample>=1")
        check_python(item, {}, lambda _: ["1.0", "2.0"])
        self.assertEqual(item.status, "unknown")

    def test_failure_hides_exception(self):
        item = Component("sample", "python-dependency", "pyproject.toml", declared="sample==1")
        def fail(_):
            raise OSError("secret-token")
        check_python(item, {}, fail)
        self.assertEqual(item.status, "unavailable")
        self.assertNotIn("secret-token", repr(item))


class PythonMetadataTest(unittest.TestCase):
    def test_missing_metadata_is_unknown_or_unavailable(self):
        item = Component('sample', 'python-dependency', 'pyproject.toml', declared='sample==1.0')
        check_python(item, {}, lambda _: [])
        self.assertEqual(item.status, 'unknown')
        self.assertIsNone(item.candidate)
