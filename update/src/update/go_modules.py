"""Version candidates for explicit Go module declarations."""
import re
import fnmatch
import os
from urllib.error import URLError
from urllib.request import Request, urlopen

from .versions import stable_version
from .policies import allows, no_candidate

_MODULE = re.compile(r'^[A-Za-z0-9][A-Za-z0-9._~+/-]*$')


def proxy_versions(module):
    if not _MODULE.fullmatch(module) or '..' in module or '//' in module:
        raise ValueError('Invalid module path')
    # Go module proxy uses !lowercase escapes for uppercase path letters.
    escaped = ''.join('!' + char.lower() if char.isupper() else char for char in module)
    request = Request(f'https://proxy.golang.org/{escaped}/@v/list', headers={'User-Agent': 'calcifer-update'})
    with urlopen(request, timeout=15) as response:
        data = response.read(2 * 1024 * 1024 + 1)
    if len(data) > 2 * 1024 * 1024:
        raise ValueError('Response too large')
    return data.decode('utf-8').splitlines()


def check_go(item, cache, fetch=proxy_versions):
    private_patterns = (os.environ.get('GOPRIVATE', '') + ',' + os.environ.get('GONOPROXY', '')).split(',')
    prefixes = ["/".join(item.name.split("/")[:index]) for index in range(1, len(item.name.split("/")) + 1)]
    if any(pattern and any(fnmatch.fnmatchcase(prefix, pattern) for prefix in prefixes) for pattern in private_patterns):
        item.status = 'unknown'
        item.detail = 'Private Go module: public proxy lookup skipped'
        return
    if item.kind == 'go-locked':
        item.status = 'unknown'
        item.detail = 'go.sum may contain unselected historical modules; resolve the module graph'
        return
    if item.metadata.get('graph_checked') == 'true' and not item.metadata.get('effective_version'):
        item.status = 'unknown'
        item.detail = item.metadata.get('resolution_error') or 'Module is absent from the resolved graph'
        return
    if item.metadata.get('replaced') == 'true':
        item.status = 'unknown'
        item.detail = 'Module has a replace directive; inspect the replacement source'
        return
    current = stable_version(item.metadata.get('effective_version') or item.declared or '')
    if current is None:
        item.status = 'unknown'
        item.detail = 'Go pseudo-version or non-stable version requires native resolution'
        return
    try:
        if item.name not in cache:
            cache[item.name] = fetch(item.name)
        versions = [(stable_version(v), v) for v in cache[item.name]]
        versions = [(parsed, text) for parsed, text in versions if allows(item, parsed, current)]
        if not versions:
            no_candidate(item)
            return
        latest, tag = max(versions)
        item.candidate = tag
        item.status = 'update-available' if latest > current else 'current' if latest == current else 'ahead'
        item.detail = 'Source: Go module proxy; resolve the complete module graph before changing files'
    except (URLError, OSError, ValueError, UnicodeError):
        item.status = 'unavailable'
        item.detail = 'Go module proxy lookup failed'
