import socket
import unittest
from urllib.error import HTTPError, URLError
from update.errors import describe_source_error


class ErrorsTest(unittest.TestCase):
    def test_safe_diagnostics(self):
        self.assertEqual(describe_source_error(HTTPError('https://example/?token=secret', 429, 'secret', {}, None)), 'HTTP 429: rate limit reached')
        self.assertEqual(describe_source_error(URLError(socket.gaierror('secret'))), 'DNS lookup failed')
        self.assertNotIn('secret', describe_source_error(OSError('secret')))
