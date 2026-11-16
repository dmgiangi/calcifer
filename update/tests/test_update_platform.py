import unittest
from update.model import Component
from update.platform import check_platform, components


class PlatformTest(unittest.TestCase):
    def test_repo_discovers_both_clusters_and_generated_flux(self):
        from update.inventory import ROOT
        entries = components(ROOT)
        self.assertEqual(len([e for e in entries if e.kind == 'k3s']), 2)
        self.assertTrue(all(e.declared for e in entries if e.kind == 'flux'))

    def test_k3s_host_and_unknown_version(self):
        item = Component('k3s', 'k3s', 'host', observed='v1.31.2+k3s1')
        check_platform(item, {}, lambda *_: 'v1.31.3+k3s2')
        self.assertEqual(item.status, 'update-available')
        self.assertIn('Host-managed', item.detail)
        item.observed = None
        check_platform(item, {}, lambda *_: 'v1.31.3+k3s2')
        self.assertEqual(item.status, 'unknown')
