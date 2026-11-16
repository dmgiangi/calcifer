import json
import unittest
from update.model import Component
from update.oci import check_image, split_reference
from update.tools import ToolResult


class OciTest(unittest.TestCase):
    def test_registry_ports_and_digest(self):
        self.assertEqual(split_reference('registry:5000/a:v1.2.3@sha256:' + 'a' * 64), ('registry:5000/a', 'v1.2.3', 'sha256:' + 'a' * 64))
        self.assertIsNone(split_reference('$(unsafe)'))

    def test_tag_comparison_and_cache(self):
        calls = []
        def runner(*args, **kwargs):
            calls.append(args)
            if 'list-tags' in args[0]:
                return ToolResult('ok', 0, json.dumps({'Tags': ['v1.0.0', 'v1.3.0', 'v2.0.0-rc1']}))
            return ToolResult('ok', 0, json.dumps({'schemaVersion': 2, 'manifests': [{'platform': {'os': 'linux', 'architecture': 'amd64'}}]}))
        item = Component('example', 'image', 'file', declared='example:v1.0.0')
        cache = {}
        check_image(item, cache, runner)
        check_image(item, cache, runner)
        self.assertEqual((item.status, item.candidate), ('update-available', 'v1.3.0'))
        self.assertEqual(len(calls), 2)
        self.assertEqual(item.metadata['platform_status'], 'unknown')

    def test_digest_only_never_invents_version(self):
        item = Component('x', 'image', 'file', declared='example@sha256:' + 'a' * 64)
        def runner(argv, **kwargs):
            if 'list-tags' in argv:
                return ToolResult('ok', 0, json.dumps({'Tags': ['v1.0.0']}))
            return ToolResult('ok', 0, json.dumps({'schemaVersion': 2, 'manifests': []}))
        check_image(item, {}, runner)
        self.assertEqual(item.status, 'unknown')
        self.assertEqual(item.candidate, 'v1.0.0')
        self.assertIn('ordering is unknown', item.detail)


class PlatformCheckTest(unittest.TestCase):
    def test_candidate_digest_and_platform_mismatch(self):
        def runner(argv, **kwargs):
            if 'list-tags' in argv:
                return ToolResult('ok', 0, json.dumps({'Tags': ['1.0.0', '1.1.0']}))
            return ToolResult('ok', 0, json.dumps({'schemaVersion': 2, 'manifests': [{'platform': {'os': 'linux', 'architecture': 'amd64'}}]}))
        item = Component('example', 'image', 'file', declared='example:1.0.0', metadata={'platform': 'linux/arm64'})
        check_image(item, {}, runner)
        self.assertEqual(item.status, 'incompatible')
        self.assertEqual(item.metadata['platform_status'], 'unsupported')
        self.assertTrue(item.metadata['candidate_digest'].startswith('sha256:'))


class TagFamilyTest(unittest.TestCase):
    def test_only_same_suffix_and_precision_are_compared(self):
        def runner(argv, **kwargs):
            if 'list-tags' in argv:
                return ToolResult('ok', 0, json.dumps({'Tags': ['3.2-alpine', '3.3-alpine', '9.0-debian', '3.4.0-alpine']}))
            return ToolResult('ok', 0, json.dumps({'schemaVersion': 2, 'manifests': []}))
        item = Component('haproxy', 'image', 'file', declared='haproxy:3.2-alpine')
        check_image(item, {}, runner)
        self.assertEqual((item.status, item.candidate), ('update-available', '3.3-alpine'))

    def test_skopeo_missing_uses_registry_fallback(self):
        from unittest.mock import patch
        tags = ToolResult('ok', 0, json.dumps({'Tags': ['1.0.0', '1.1.0']}))
        manifest = ToolResult('ok', 0, json.dumps({'schemaVersion': 2, 'manifests': []}))
        item = Component('x', 'image', 'file', declared='example:1.0.0')
        with patch('update.oci.registry.list_tags', return_value=tags), patch('update.oci.registry.inspect_manifest', return_value=manifest):
            check_image(item, {}, lambda *a, **k: ToolResult('missing'))
        self.assertEqual(item.candidate, '1.1.0')
        self.assertEqual(item.status, 'update-available')


class DigestAndFlavorTest(unittest.TestCase):
    def test_digest_only_matching_latest_is_current(self):
        import hashlib
        manifest = json.dumps({'schemaVersion': 2, 'manifests': []})
        digest = 'sha256:' + hashlib.sha256(manifest.encode()).hexdigest()
        def runner(argv, **kwargs):
            if 'list-tags' in argv:
                return ToolResult('ok', 0, json.dumps({'Tags': ['v1.0.0', 'v1.1.0']}))
            return ToolResult('ok', 0, manifest)
        item = Component('x', 'image', 'file', declared='example@' + digest)
        check_image(item, {}, runner)
        self.assertEqual((item.status, item.candidate), ('current', 'v1.1.0'))

    def test_prefixed_distroless_tag_uses_same_family(self):
        def runner(argv, **kwargs):
            if 'list-tags' in argv:
                return ToolResult('ok', 0, json.dumps({'Tags': ['distroless-v1.33.0', 'distroless-v1.34.0', 'standard-v9.0.0']}))
            return ToolResult('ok', 0, json.dumps({'schemaVersion': 2, 'manifests': []}))
        item = Component('x', 'image', 'file', declared='envoyproxy/envoy:distroless-v1.33.0')
        check_image(item, {}, runner)
        self.assertEqual(item.candidate, 'distroless-v1.34.0')


class VersionPrefixTest(unittest.TestCase):
    def test_leading_v_does_not_hide_same_family_updates(self):
        def runner(argv, **kwargs):
            if 'list-tags' in argv:
                return ToolResult('ok', 0, json.dumps({'Tags': ['1.0.0', 'v1.1.0']}))
            return ToolResult('ok', 0, json.dumps({'schemaVersion': 2, 'manifests': []}))
        item = Component('x', 'image', 'file', declared='example:1.0.0')
        check_image(item, {}, runner)
        self.assertEqual((item.status, item.candidate), ('update-available', 'v1.1.0'))
