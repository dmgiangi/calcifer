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

    def test_espressif_toolchain_reports_separate_pins_without_using_stable_rust_releases(self):
        item = Component('rust-toolchain', 'rust-toolchain', 'rust-toolchain.toml', declared='esp', metadata={
            'espup_version': '0.19.0', 'xtensa_rust_version': '1.97.0.0', 'esp_idf_version': 'v5.4.4',
            'ldproxy_version': '0.3.5', 'espflash_version': '4.7.0'})
        check_rust_toolchain(item, {}, lambda *_: self.fail('vendor toolchain is not rust stable'))
        self.assertEqual(item.status, 'unknown')
        self.assertIn('espup 0.19.0', item.detail)
        self.assertIn('Xtensa Rust 1.97.0.0', item.detail)
        self.assertIn('ESP-IDF v5.4.4', item.detail)
        self.assertIn('ldproxy 0.3.5', item.detail)
        self.assertIn('espflash 4.7.0', item.detail)

    def test_standard_toolchain_release(self):
        item = Component('rust-toolchain', 'rust-toolchain', 'file', declared='1.80.0')
        check_rust_toolchain(item, {}, lambda *_: '1.81.0')
        self.assertEqual(item.status, 'update-available')


class JavaToolchainTest(unittest.TestCase):
    def test_major_does_not_claim_installed_patch(self):
        from update.toolchains import check_java_toolchain
        item = Component('java.version', 'java-toolchain', 'pom.xml', declared='25')
        check_java_toolchain(item, {}, lambda *_: self.fail('POM target does not establish a distribution'))
        self.assertEqual(item.status, 'unknown')
        self.assertIsNone(item.candidate)


class GoToolchainTest(unittest.TestCase):
    def test_directive_is_not_treated_as_installed_version(self):
        from update.toolchains import check_go_toolchain
        item = Component('go', 'go-toolchain', 'go.mod', declared='1.24')
        check_go_toolchain(item, {}, lambda: 'go1.25.1')
        self.assertEqual((item.status, item.candidate), ('unknown', 'go1.25.1'))
