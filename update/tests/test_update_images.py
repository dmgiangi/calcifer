import tempfile
import unittest
from pathlib import Path
from update.image_prepare import _GROUPS, prepare_image


class ImagePreparationTest(unittest.TestCase):
    def setup_group(self, root, group):
        old = _GROUPS[group]['image'] + (':2026.9.2' if _GROUPS[group]['tagged'] else '') + '@sha256:' + 'a' * 64
        for name in _GROUPS[group]['paths']:
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('containers:\n  - image: ' + old + '\n')

    def test_home_assistant_all_files_preview_and_write(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.setup_group(root, 'home-assistant')
            opts = {'verify': lambda *a: None, 'validator': lambda *a: None, 'require_clean': lambda *a: None}
            target = '2026.10.0@sha256:' + 'b' * 64
            diff, fingerprint, clusters = prepare_image(root, 'home-assistant', target, 'home', 'linux/amd64', **opts)
            self.assertIn(target, diff)
            self.assertEqual(clusters, ['home'])
            prepare_image(root, 'home-assistant', target, 'home', 'linux/amd64', write=True, expected=fingerprint, **opts)
            self.assertTrue(all(target in (root / path).read_text() for path in _GROUPS['home-assistant']['paths']))

    def test_auth_is_symmetric_and_validator_failure_does_not_write(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.setup_group(root, 'authorization-server')
            target = 'sha256:' + 'b' * 64
            opts = {'verify': lambda *a: None, 'validator': lambda *a: None, 'require_clean': lambda *a: None}
            with self.assertRaises(ValueError):
                prepare_image(root, 'authorization-server', target, 'cloud', 'linux/amd64', **opts)
            seen = []
            def fail(*args):
                seen.append(args[1])
                if len(seen) == 2:
                    raise ValueError('invalid')
            opts['validator'] = fail
            with self.assertRaises(ValueError):
                prepare_image(root, 'authorization-server', target, 'all', 'linux/amd64', **opts)
            self.assertEqual(len(seen), 2)
            self.assertTrue(all('a' * 64 in (root / path).read_text() for path in _GROUPS['authorization-server']['paths']))


class RegistryVerificationTest(unittest.TestCase):
    def test_digest_and_platform_are_verified(self):
        import hashlib
        import json
        from update.image_prepare import verify_image
        from update.tools import ToolResult
        raw = json.dumps({'schemaVersion': 2, 'manifests': [{'digest': 'sha256:' + 'c' * 64, 'platform': {'os': 'linux', 'architecture': 'amd64'}}]})
        digest = 'sha256:' + hashlib.sha256(raw.encode()).hexdigest()
        runner = lambda *a, **kw: ToolResult('ok', 0, raw)
        verify_image('example/image', '1.0.0', digest, 'linux/amd64', runner)
        with self.assertRaises(ValueError):
            verify_image('example/image', '1.0.0', digest, 'linux/arm64', runner)
        with self.assertRaises(ValueError):
            verify_image('example/image', '1.0.0', 'sha256:' + 'b' * 64, 'linux/amd64', runner)


class CoverageTest(unittest.TestCase):
    def test_unmapped_image_reference_blocks_preparation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            ImagePreparationTest().setup_group(root, 'home-assistant')
            extra = root / 'clusters/calcifer-home/extra.yaml'
            extra.write_text('containers:\n  - image: ghcr.io/home-assistant/home-assistant:2026.9.2@sha256:' + 'a' * 64 + '\n')
            with self.assertRaisesRegex(ValueError, 'Unmapped'):
                prepare_image(root, 'home-assistant', '2026.10.0@sha256:' + 'b' * 64,
                              'home', 'linux/amd64', verify=lambda *a: None,
                              validator=lambda *a: None, require_clean=lambda *a: None)
