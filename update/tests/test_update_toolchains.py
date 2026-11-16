import unittest
from update.model import Component
from update.toolchains import check_bundled, check_rust_toolchain


class ToolchainTest(unittest.TestCase):
    def test_vendor_and_bundled_versions_are_not_guessed(self):
        item = Component('rust-toolchain', 'rust-toolchain', 'rust-toolchain.toml', declared='esp')
        check_rust_toolchain(item, {}, lambda *_: self.fail('vendor toolchain is not rust stable'))
        self.assertEqual(item.status, 'unknown')
        item = Component('traefik', 'k3s-component', 'file')
        check_bundled(item)
        self.assertEqual(item.status, 'unknown')
        self.assertIn('K3s', item.detail)

    def test_standard_toolchain_release(self):
        item = Component('rust-toolchain', 'rust-toolchain', 'file', declared='1.80.0')
        check_rust_toolchain(item, {}, lambda *_: '1.81.0')
        self.assertEqual(item.status, 'update-available')


class JavaToolchainTest(unittest.TestCase):
    def test_major_does_not_claim_installed_patch(self):
        from update.toolchains import check_java_toolchain
        item = Component('java.version', 'java-toolchain', 'pom.xml', declared='25')
        check_java_toolchain(item, {}, lambda *_: 'jdk-25.0.1+8')
        self.assertEqual(item.status, 'unknown')
        self.assertEqual(item.candidate, 'jdk-25.0.1+8')


class GoToolchainTest(unittest.TestCase):
    def test_directive_is_not_treated_as_installed_version(self):
        from update.toolchains import check_go_toolchain
        item = Component('go', 'go-toolchain', 'go.mod', declared='1.24')
        check_go_toolchain(item, {}, lambda: 'go1.25.1')
        self.assertEqual((item.status, item.candidate), ('unknown', 'go1.25.1'))
