import unittest
from update.model import Component
from update.rust_packages import check_rust


class RustCheckTest(unittest.TestCase):
    def test_build_metadata_is_preserved_but_not_ordered(self):
        item = Component('toml', 'rust-dependency', 'Cargo.toml', declared='1.2',
                         metadata={'locked_version': '1.2.1+spec-1.1.0'})
        check_rust(item, {}, lambda _: '1.2.1+spec-1.1.1')
        self.assertEqual((item.status, item.candidate), ('current', '1.2.1+spec-1.1.1'))
        self.assertEqual(item.metadata['locked_version'], '1.2.1+spec-1.1.0')
        check_rust(item, {}, lambda _: '1.2.2+spec-1.1.0')
        self.assertEqual(item.status, 'update-available')

    def test_latest_only_source_does_not_bypass_policy(self):
        item = Component('toml', 'rust-dependency', 'Cargo.toml', declared='1',
                         metadata={'locked_version': '1.2.1', 'version_policy': 'same-major'})
        check_rust(item, {}, lambda _: '2.0.0')
        self.assertEqual((item.status, item.candidate), ('unknown', None))
        self.assertIn('same-major', item.detail)

    def test_cargo_range_does_not_claim_installed_version(self):
        item = Component("sample", "rust-dependency", "Cargo.toml", declared="0.4")
        check_rust(item, {}, lambda _: "0.5.0")
        self.assertEqual((item.status, item.candidate), ("unknown", "0.5.0"))

    def test_errors_do_not_expose_contents(self):
        item = Component("sample", "rust-dependency", "Cargo.toml", declared="0.4")
        def fail(_):
            raise OSError("secret-token")
        check_rust(item, {}, fail)
        self.assertEqual(item.status, "unavailable")
        self.assertNotIn("secret-token", repr(item))
