import unittest
from update.go_modules import check_go
from update.model import Component


class GoTest(unittest.TestCase):
    def test_stable_versions_and_pseudo_versions(self):
        item = Component('example.com/m', 'go-dependency', 'go.mod', declared='v1.0.0')
        check_go(item, {}, lambda _: ['v1.0.0', 'v1.2.0', 'v1.3.0-rc1'])
        self.assertEqual((item.status, item.candidate), ('update-available', 'v1.2.0'))
        item.declared = 'v0.0.0-20200101000000-abcdefabcdef'
        check_go(item, {}, lambda _: self.fail('must not compare pseudo-version'))
        self.assertEqual(item.status, 'unknown')


class GoPrivacyTest(unittest.TestCase):
    def test_private_module_is_never_sent_to_public_proxy(self):
        from unittest.mock import patch
        item = Component('private.example/team/module', 'go-dependency', 'go.mod', declared='v1.0.0')
        with patch.dict('os.environ', {'GOPRIVATE': 'private.example/*'}):
            check_go(item, {}, lambda _: self.fail('private module leaked'))
        self.assertEqual(item.status, 'unknown')
        self.assertIn('Private', item.detail)
