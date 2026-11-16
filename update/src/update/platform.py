"""Discovery of host-managed K3s and repository-managed Flux releases."""
import json
import re
from urllib.parse import quote
from urllib.request import Request, urlopen

from .actions import latest_release
from .errors import describe_source_error
from .cache import cached
from .model import Component
from .versions import stable_version
from .policies import allows, no_candidate

_K3S = re.compile(r'^v(\d+)\.(\d+)\.(\d+)\+k3s(\d+)$')


def components(root):
    result = []
    for cluster in ('home', 'cloud'):
        directory = root / 'clusters' / f'calcifer-{cluster}'
        if not directory.is_dir():
            continue
        result.append(Component('k3s', 'k3s', 'host', clusters=[cluster], status='unknown',
                                detail='Host-managed; use --live with an explicit context to observe versions'))
        generated = directory / 'flux-system' / 'gotk-components.yaml'
        if generated.is_file():
            try:
                text = generated.read_text(encoding='utf-8')
                versions = set(re.findall(r'^\s*app\.kubernetes\.io/version:\s*([\w.\-+]+)\s*$', text, re.M))
                version = versions.pop() if len(versions) == 1 else None
                result.append(Component('flux', 'flux', str(generated.relative_to(root)), declared=version,
                                        clusters=[cluster], status='unknown',
                                        detail='Generated Flux manifests; regenerate with Flux tooling'))
            except (OSError, UnicodeError):
                result.append(Component('flux', 'flux', str(generated.relative_to(root)), clusters=[cluster],
                                        status='unavailable', detail='Cannot read generated Flux manifest'))
    return result


def _k3s_version(value):
    match = _K3S.fullmatch(value or '')
    return tuple(map(int, match.groups())) if match else None


def check_platform(item, cache, fetch=latest_release):
    owner, repo = ('k3s-io', 'k3s') if item.kind == 'k3s' else ('fluxcd', 'flux2')
    key = (owner, repo)
    node_state = item.metadata.get('node_state') if item.kind == 'k3s' else None
    if node_state in ('mixed', 'incomplete', 'unavailable'):
        item.candidate = None
        item.status = {'mixed': 'divergent', 'incomplete': 'unknown', 'unavailable': 'unavailable'}[node_state]
        item.detail = 'Host-managed; node observations are ' + node_state + '; inspect every node before maintenance'
        return
    try:
        candidate = cached(cache, key, lambda: fetch(owner, repo))
        item.candidate = candidate
        if item.kind == 'k3s':
            current = _k3s_version(item.observed)
            latest = _k3s_version(candidate)
            if not allows(item, latest, current):
                no_candidate(item)
                return
            if not current or not latest:
                item.status = 'unknown'
                item.detail = 'Host version or stable K3s release is unavailable; host upgrade requires an operator procedure'
            else:
                item.status = 'update-available' if latest > current else 'current' if latest == current else 'ahead'
                item.detail = 'Host-managed; review backup and K3s upgrade procedure before maintenance'
        else:
            current, latest = stable_version(item.declared or ''), stable_version(candidate)
            if not allows(item, latest, current):
                no_candidate(item)
                return
            if current is None or latest is None:
                item.status = 'unknown'
                item.detail = 'Generated Flux or release version cannot be compared safely'
            else:
                item.status = 'update-available' if latest > current else 'current' if latest == current else 'ahead'
                item.detail = 'Regenerate Flux manifests with supported Flux tooling and review changes'
    except (OSError, ValueError, TypeError, KeyError) as exc:
        item.status = 'unavailable'
        item.detail = 'Release lookup failed: ' + describe_source_error(exc)


def bundled_versions(release):
    """Read the component table from an exact, stable official K3s release."""
    if not _k3s_version(release):
        raise ValueError('Invalid K3s release')
    request = Request('https://api.github.com/repos/k3s-io/k3s/releases/tags/' + quote(release, safe=''),
                      headers={'User-Agent': 'calcifer-update', 'Accept': 'application/vnd.github+json'})
    with urlopen(request, timeout=15) as response:
        raw = response.read(2 * 1024 * 1024 + 1)
    if len(raw) > 2 * 1024 * 1024:
        raise ValueError('Release metadata too large')
    data = json.loads(raw)
    if (not isinstance(data, dict) or data.get('tag_name') != release or data.get('draft')
            or data.get('prerelease') or not isinstance(data.get('body'), str)):
        raise ValueError('Unverified K3s release metadata')
    rows = re.findall(r'^\|\s*Traefik\s*\|\s*\[?(v?[0-9]+\.[0-9]+\.[0-9]+)'
                      r'(?:\]\([^|\r\n]*\))?\s*\|\s*$', data['body'], re.M | re.I)
    return {'traefik': rows[0]} if len(rows) == 1 and stable_version(rows[0]) else {}


def check_bundled(item, hosts=(), cache=None, fetch=bundled_versions):
    item.metadata['update_via'] = 'k3s'
    item.metadata['comparison_basis'] = 'exact K3s release defaults, not observed Traefik runtime'
    item.candidate = None
    matches = [host for host in hosts if host.clusters == item.clusters and len(host.clusters) == 1]
    if item.name != 'traefik' or len(matches) != 1:
        item.status = 'unknown'
        item.detail = 'K3s-bundled component; exact release scope is unknown; no independent upgrade'
        return
    host = matches[0]
    if host.metadata.get('node_state') in ('mixed', 'incomplete', 'unavailable'):
        item.status = 'divergent' if host.metadata['node_state'] == 'mixed' else 'unknown'
        item.detail = 'K3s node versions are not uniform and fully observed; no independent upgrade'
        return
    cache = {} if cache is None else cache
    try:
        if not _k3s_version(host.candidate):
            item.status = 'unknown'
            item.detail = 'K3s candidate release is not verified; no independent component upgrade'
            return
        candidate = cached(cache, ('bundled', host.candidate), lambda: fetch(host.candidate)).get(item.name)
        if not stable_version(candidate or ''):
            item.status = 'unknown'
            item.detail = 'Exact K3s candidate release has no bundled version mapping'
            return
        item.candidate = candidate
        item.metadata['candidate_k3s_release'] = host.candidate
        item.metadata['candidate_source'] = 'https://github.com/k3s-io/k3s/releases/tag/' + quote(host.candidate, safe='')
        if not _k3s_version(host.observed):
            item.status = 'unknown'
            item.detail = 'K3s installed version not observed; bundled candidate only; no independent upgrade'
            return
        current = cached(cache, ('bundled', host.observed), lambda: fetch(host.observed)).get(item.name)
        parsed_current, parsed_candidate = stable_version(current or ''), stable_version(candidate)
        if parsed_current is None:
            item.status = 'unknown'
            item.detail = 'Exact observed K3s release has no bundled version mapping'
            return
        item.metadata['bundled_current_version'] = current
        item.metadata['current_k3s_release'] = host.observed
        item.status = ('update-available' if parsed_candidate > parsed_current else
                       'current' if parsed_candidate == parsed_current else 'ahead')
        item.detail = ('K3s release defaults compared; not proof of installed Traefik runtime. '
                       'Upgrade only through the K3s host procedure; inspect HelmChart overrides')
    except (OSError, ValueError, TypeError, AttributeError) as exc:
        item.status = 'unavailable'
        item.detail = 'K3s bundled-version lookup failed: ' + describe_source_error(exc)
