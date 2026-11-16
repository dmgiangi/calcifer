import unittest
from update.model import Component
from update.rust_packages import check_rust


class RustCheckTest(unittest.TestCase):
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
