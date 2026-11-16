"""Discovery of host-managed K3s and repository-managed Flux releases."""
import re

from .actions import latest_release
from .errors import describe_source_error
from .cache import cached
from .model import Component
from .versions import stable_version

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
    try:
        candidate = cached(cache, key, lambda: fetch(owner, repo))
        item.candidate = candidate
        if item.kind == 'k3s':
            current = _k3s_version(item.observed)
            latest = _k3s_version(candidate)
            if not current or not latest:
                item.status = 'unknown'
                item.detail = 'Host version or stable K3s release is unavailable; host upgrade requires an operator procedure'
            else:
                item.status = 'update-available' if latest > current else 'current' if latest == current else 'ahead'
                item.detail = 'Host-managed; review backup and K3s upgrade procedure before maintenance'
        else:
            current, latest = stable_version(item.declared or ''), stable_version(candidate)
            if current is None or latest is None:
                item.status = 'unknown'
                item.detail = 'Generated Flux or release version cannot be compared safely'
            else:
                item.status = 'update-available' if latest > current else 'current' if latest == current else 'ahead'
                item.detail = 'Regenerate Flux manifests with supported Flux tooling and review changes'
    except (OSError, ValueError, TypeError, KeyError) as exc:
        item.status = 'unavailable'
        item.detail = 'Release lookup failed: ' + describe_source_error(exc)
