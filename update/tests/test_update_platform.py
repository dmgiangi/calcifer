import unittest
import io
import json
from unittest.mock import patch
from update.model import Component
from update.platform import check_platform, components, check_bundled, bundled_versions


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


class BundledVersionsTest(unittest.TestCase):
    def test_uses_exact_official_release_table(self):
        metadata = {'tag_name': 'v1.31.2+k3s1', 'body': '| Component | Version |\n| Traefik | [v3.2.0](https://github.com/traefik/traefik/releases/tag/v3.2.0) |\n'}
        def response(_, **kwargs):
            return io.BytesIO(json.dumps(metadata).encode())
        with patch('update.platform.urlopen', side_effect=response) as get:
            self.assertEqual(bundled_versions('v1.31.2+k3s1'), {'traefik': 'v3.2.0'})
        self.assertTrue(get.call_args.args[0].full_url.endswith('v1.31.2%2Bk3s1'))
        metadata['tag_name'] = 'v1.31.3+k3s1'
        with patch('update.platform.urlopen', side_effect=response):
            with self.assertRaises(ValueError):
                bundled_versions('v1.31.2+k3s1')

    def test_missing_or_ambiguous_mapping_is_not_guessed(self):
        for body in ('no table', '| Traefik | v3.2.0-rc1 |', '| Traefik | v3.2.0 |\n| Traefik | v3.3.0 |'):
            metadata = {'tag_name': 'v1.31.2+k3s1', 'body': body}
            with patch('update.platform.urlopen', return_value=io.BytesIO(json.dumps(metadata).encode())):
                self.assertEqual(bundled_versions('v1.31.2+k3s1'), {})

    def host(self):
        return Component('k3s', 'k3s', 'host', observed='v1.31.2+k3s1', candidate='v1.32.0+k3s1', clusters=['home'])

    def test_compares_release_defaults_not_observed_chart_version(self):
        item = Component('traefik', 'k3s-component', 'file', observed='34.1.0+up34.0.0', clusters=['home'])
        def fetch(release):
            return {'traefik': 'v3.2.0' if release == 'v1.31.2+k3s1' else 'v3.3.0'}
        check_bundled(item, [self.host()], fetch=fetch)
        self.assertEqual((item.status, item.candidate), ('update-available', 'v3.3.0'))
        self.assertEqual(item.observed, '34.1.0+up34.0.0')
        self.assertEqual(item.metadata['update_via'], 'k3s')
        self.assertEqual(item.metadata['bundled_current_version'], 'v3.2.0')
        self.assertIn('not proof', item.detail)

    def test_no_baseline_or_mapping_stays_unknown(self):
        item = Component('traefik', 'k3s-component', 'file', clusters=['home'])
        host = self.host()
        check_bundled(item, [host], fetch=lambda _: {})
        self.assertEqual((item.status, item.candidate), ('unknown', None))
        host.observed = None
        check_bundled(item, [host], fetch=lambda _: {'traefik': 'v3.3.0'})
        self.assertEqual((item.status, item.candidate), ('unknown', 'v3.3.0'))
        host.metadata['node_state'] = 'mixed'
        check_bundled(item, [host], fetch=lambda _: self.fail('must not map mixed nodes'))
        self.assertEqual((item.status, item.candidate), ('divergent', None))

    def test_source_failure_is_explicit_and_private(self):
        item = Component('traefik', 'k3s-component', 'file', clusters=['home'])
        def fail(_):
            raise OSError('secret-token')
        check_bundled(item, [self.host()], fetch=fail)
        self.assertEqual(item.status, 'unavailable')
        self.assertNotIn('secret-token', repr(item))
