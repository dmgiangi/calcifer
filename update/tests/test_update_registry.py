import io
import json
import unittest
from urllib.error import HTTPError
from unittest.mock import patch

from update.registry import list_tags, inspect_manifest
from update.rust_packages import latest_crate


class Response(io.BytesIO):
    def __init__(self, value, headers=None):
        super().__init__(value)
        self.headers = headers or {}


class RegistryTest(unittest.TestCase):
    def test_anonymous_bearer_auth_and_paginated_tags(self):
        calls = []
        def opener(request, **kwargs):
            url = request.full_url
            calls.append(url)
            if 'auth.docker.io' in url:
                return Response(b'{"token":"secret-token"}')
            if 'tags/list?n=100000' in url and not request.get_header('Authorization'):
                raise HTTPError(url, 401, 'unauthorized', {'WWW-Authenticate': 'Bearer realm="https://auth.docker.io/token",service="registry.docker.io"'}, None)
            if 'last=1' in url:
                return Response(b'{"tags":["2.0.0"]}')
            self.assertEqual(request.get_header('Authorization'), 'Bearer secret-token')
            return Response(b'{"tags":["1.0.0"]}', {'Link': '</v2/library/example/tags/list?n=1000&last=1>; rel="next"'})
        result = list_tags('example', opener=opener)
        self.assertEqual(result.status, 'ok')
        self.assertEqual(json.loads(result.stdout)['Tags'], ['1.0.0', '2.0.0'])
        self.assertNotIn('secret-token', repr(result))

    def test_large_repository_fits_in_one_bounded_page(self):
        # Envoy has over 80,000 tags and its complete response exceeds 4 MiB.
        tags = [f'dev-{index:064x}' for index in range(65000)]
        tags += ['distroless-v1.33.0', 'distroless-v1.34.0']
        payload = json.dumps({'tags': tags}).encode()
        self.assertGreater(len(payload), 4 * 1024 * 1024)
        calls = []

        def opener(request, **kwargs):
            calls.append(request.full_url)
            self.assertEqual(request.full_url, 'https://registry-1.docker.io/v2/envoyproxy/envoy/tags/list?n=100000')
            self.assertLessEqual(kwargs['timeout'], 8)
            return Response(payload)

        result = list_tags('envoyproxy/envoy', opener=opener)
        self.assertEqual(result.status, 'ok')
        self.assertEqual(json.loads(result.stdout)['Tags'], tags)
        self.assertEqual(len(calls), 1)

    def test_oversized_tag_page_is_rejected_even_if_valid_json(self):
        # A parseable response must not bypass the byte limit with whitespace.
        payload = b'{"tags":["1.0.0"]}' + b' ' * (16 * 1024 * 1024)
        result = list_tags('example', opener=lambda *a, **k: Response(payload))
        self.assertNotEqual(result.status, 'ok')
        self.assertEqual(result.stdout, '')

    def test_rejects_untrusted_auth_endpoint(self):
        def opener(request, **kwargs):
            raise HTTPError(request.full_url, 401, 'unauthorized', {'WWW-Authenticate': 'Bearer realm="https://evil.test/token"'}, None)
        result = list_tags('example', opener=opener)
        self.assertNotEqual(result.status, 'ok')

    def test_manifest_accepts_digest_reference(self):
        result = inspect_manifest('example', 'sha256:' + 'a' * 64, opener=lambda *a, **k: Response(b'{"schemaVersion":2}'))
        self.assertEqual(result.status, 'ok')


class CrateFallbackTest(unittest.TestCase):
    def test_uses_sparse_index_when_api_unavailable_and_ignores_yanked(self):
        def opener(request, **kwargs):
            if request.full_url.startswith('https://crates.io/'):
                raise HTTPError(request.full_url, 403, 'blocked', {}, None)
            self.assertEqual(request.full_url, 'https://index.crates.io/3/l/log')
            entries = [{'vers': '1.0.0', 'yanked': False}, {'vers': '2.0.0', 'yanked': True}, {'vers': '1.1.0-rc1', 'yanked': False}]
            return Response(('\n'.join(json.dumps(entry) for entry in entries)).encode())
        with patch('update.rust_packages.urlopen', opener):
            self.assertEqual(latest_crate('log'), '1.0.0')
