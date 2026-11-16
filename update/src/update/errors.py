"""Safe diagnostics for failed public-source requests."""
import socket
from urllib.error import HTTPError, URLError


def describe_source_error(error):
    if isinstance(error, HTTPError):
        if error.code == 429:
            return 'HTTP 429: rate limit reached'
        if error.code in (401, 403):
            return f'HTTP {error.code}: access denied or rate limited'
        return f'HTTP {error.code}'
    if isinstance(error, (TimeoutError, socket.timeout)):
        return 'connection timed out'
    if isinstance(error, URLError):
        if isinstance(error.reason, (TimeoutError, socket.timeout)):
            return 'connection timed out'
        if isinstance(error.reason, socket.gaierror):
            return 'DNS lookup failed'
        return 'network connection failed'
    if isinstance(error, OSError):
        return 'network or operating system error'
    return 'invalid or unsupported response'
