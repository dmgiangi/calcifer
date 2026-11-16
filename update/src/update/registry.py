"""Anonymous read-only access to common OCI registry APIs."""
import json
import re
import time
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit, parse_qsl, urljoin
from urllib.request import Request, urlopen

from .errors import describe_source_error
from .tools import ToolResult

_HOST = re.compile(r'^[A-Za-z0-9.-]+(?::[0-9]+)?$')
_NAME = re.compile(r'^[a-z0-9]+(?:[._-][a-z0-9]+)*(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*$')
_CHALLENGE = re.compile(r'(\w+)="([^"]*)"')
_ACCEPT = ', '.join(['application/vnd.oci.image.index.v1+json', 'application/vnd.oci.image.manifest.v1+json',
                    'application/vnd.docker.distribution.manifest.list.v2+json', 'application/vnd.docker.distribution.manifest.v2+json'])
# Large repositories such as Envoy contain tens of thousands of development
# tags. Avoid spending the pagination deadline on hundreds of small pages.
_TAG_PAGE_SIZE = 100000
_TAG_RESPONSE_LIMIT = 16 * 1024 * 1024


def _coordinates(image):
    parts = image.split('/')
    if len(parts) == 1 or not ('.' in parts[0] or ':' in parts[0] or parts[0] == 'localhost'):
        host = 'registry-1.docker.io'
        repo = ('library/' if len(parts) == 1 else '') + image
    else:
        host, repo = parts[0], '/'.join(parts[1:])
        if host in ('docker.io', 'index.docker.io'):
            host = 'registry-1.docker.io'
            if '/' not in repo:
                repo = 'library/' + repo
    if not _HOST.fullmatch(host) or not _NAME.fullmatch(repo):
        raise ValueError('invalid registry reference')
    return host, repo


def _allowed_auth(host, realm):
    parsed = urlsplit(realm)
    allowed = {host}
    if host == 'registry-1.docker.io':
        allowed.add('auth.docker.io')
    return (parsed.scheme == 'https' and parsed.netloc in allowed and not parsed.username and
            not parsed.password and not parsed.fragment)


def _request(url, host, repo, opener=urlopen, accept='application/json', timeout=8):
    req = Request(url, headers={'Accept': accept, 'User-Agent': 'calcifer-update'})
    try:
        return opener(req, timeout=timeout)
    except HTTPError as error:
        if error.code != 401:
            raise
        challenge = error.headers.get('WWW-Authenticate', '')
        if not challenge.lower().startswith('bearer '):
            raise
        fields = dict(_CHALLENGE.findall(challenge))
        realm = fields.get('realm', '')
        if not _allowed_auth(host, realm):
            raise ValueError('untrusted registry authentication endpoint')
        parameters = dict(parse_qsl(urlsplit(realm).query, keep_blank_values=True))
        if fields.get('service'):
            parameters['service'] = fields['service']
        parameters['scope'] = f'repository:{repo}:pull'
        token_url = realm.split('?', 1)[0] + '?' + urlencode(parameters)
        with opener(Request(token_url, headers={'User-Agent': 'calcifer-update'}), timeout=timeout) as response:
            body = json.loads(response.read(1024 * 1024 + 1))
        token = body.get('token') or body.get('access_token')
        if not isinstance(token, str) or not token or '\n' in token or '\r' in token:
            raise ValueError('invalid registry token')
        retry = Request(url, headers={'Accept': accept, 'User-Agent': 'calcifer-update'})
        retry.add_unredirected_header('Authorization', 'Bearer ' + token)
        return opener(retry, timeout=timeout)


def list_tags(image, opener=urlopen):
    try:
        host, repo = _coordinates(image)
        next_url = f'https://{host}/v2/{repo}/tags/list?n={_TAG_PAGE_SIZE}'
        tags = []
        deadline = time.monotonic() + 25
        for _ in range(20):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError('registry pagination timeout')
            with _request(next_url, host, repo, opener, timeout=min(8, remaining)) as response:
                raw = response.read(_TAG_RESPONSE_LIMIT + 1)
                if len(raw) > _TAG_RESPONSE_LIMIT:
                    raise ValueError('registry tag page too large')
                data = json.loads(raw)
                items = data.get('tags') or []
                if not isinstance(items, list) or not all(isinstance(tag, str) for tag in items):
                    raise ValueError('invalid registry tags')
                tags.extend(items)
                link = response.headers.get('Link', '')
            match = re.search(r'<([^>]+)>\s*;\s*rel="?next"?', link, re.I)
            if not match:
                return ToolResult('ok', 0, json.dumps({'Tags': tags}))
            next_url = urljoin(next_url, match.group(1))
            parsed = urlsplit(next_url)
            if parsed.scheme != 'https' or parsed.netloc != host or parsed.path != f'/v2/{repo}/tags/list':
                raise ValueError('unsafe registry pagination')
        raise ValueError('too many registry pages')
    except (OSError, ValueError, TypeError, KeyError) as exc:
        return ToolResult('unavailable: ' + describe_source_error(exc))


def inspect_manifest(image, tag, opener=urlopen):
    try:
        host, repo = _coordinates(image)
        if not (re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}', tag) or re.fullmatch(r'sha256:[0-9a-f]{64}', tag)):
            raise ValueError('invalid image tag')
        with _request(f'https://{host}/v2/{repo}/manifests/{tag}', host, repo, opener, _ACCEPT) as response:
            data = response.read(4 * 1024 * 1024 + 1)
        if len(data) > 4 * 1024 * 1024:
            raise ValueError('manifest too large')
        return ToolResult('ok', 0, data.decode('utf-8'))
    except (OSError, ValueError, TypeError, UnicodeError) as exc:
        return ToolResult('unavailable: ' + describe_source_error(exc))


def inspect_blob(image, digest, opener=urlopen):
    try:
        host, repo = _coordinates(image)
        if not re.fullmatch(r'sha256:[0-9a-f]{64}', digest):
            raise ValueError('invalid blob digest')
        with _request(f'https://{host}/v2/{repo}/blobs/{digest}', host, repo, opener, 'application/octet-stream') as response:
            data = response.read(4 * 1024 * 1024 + 1)
        if len(data) > 4 * 1024 * 1024:
            raise ValueError('blob too large')
        import hashlib
        if 'sha256:' + hashlib.sha256(data).hexdigest() != digest:
            raise ValueError('blob digest mismatch')
        return ToolResult('ok', 0, data.decode('utf-8'))
    except (OSError, ValueError, TypeError, UnicodeError) as exc:
        return ToolResult('unavailable: ' + describe_source_error(exc))
