"""Explicit candidate policies and non-secret provenance shared by strategies."""
from urllib.parse import urlsplit

_SOURCES = {
    'k3s': ('https://github.com/k3s-io/k3s/releases', 'K3s release including vendor revision'),
    'flux': ('https://github.com/fluxcd/flux2/releases', 'stable SemVer'),
    'action': ('GitHub releases of the declared action repository', 'release tag or verified commit'),
    'helm-chart': ('configured Helm repository', 'stable chart SemVer'),
    'image': ('declared OCI registry', 'same tag flavor and precision; verified digest'),
    'docker-base': ('declared OCI registry', 'same tag flavor and precision; verified digest'),
    'k3s-component': ('exact K3s release metadata', 'bundled version; no independent upgrade'),
    'java-toolchain': ('distribution not yet established', 'project target is not an installed JDK'),
    'rust-toolchain': ('https://github.com/rust-lang/rust/releases', 'stable SemVer; vendor toolchains excluded'),
    'go-toolchain': ('https://go.dev/dl/?mode=json', 'directive is not an installed toolchain'),
}
for _kind in ('maven-parent', 'maven-dependency', 'maven-managed', 'maven-plugin'):
    _SOURCES[_kind] = ('https://repo.maven.apache.org/maven2/', 'numeric stable Maven version')
for _kind in ('python-dependency', 'python-locked'):
    _SOURCES[_kind] = ('https://pypi.org', 'stable PEP 440 version')
for _kind in ('rust-dependency', 'rust-locked'):
    _SOURCES[_kind] = ('https://crates.io', 'stable SemVer; build metadata ignored for precedence')
for _kind in ('go-dependency', 'go-locked'):
    _SOURCES[_kind] = ('https://proxy.golang.org', 'stable Go module SemVer')


def record_provenance(item, repositories=()):
    if item.kind not in _SOURCES:
        return
    source, comparison = _SOURCES[item.kind]
    if item.kind in ('image', 'docker-base'):
        from .oci import split_reference
        reference = split_reference(item.declared or '')
        if reference:
            source = 'oci://' + reference[0]
    elif item.kind == 'helm-chart':
        matches = [repo for repo in repositories if repo.name == item.metadata.get('repository')
                   and repo.metadata.get('namespace') == item.metadata.get('namespace')
                   and repo.clusters == item.clusters]
        if len(matches) == 1:
            url = matches[0].declared or ''
            parsed = urlsplit(url)
            if parsed.scheme in ('https', 'oci') and parsed.hostname and not any(
                    (parsed.username, parsed.password, parsed.query, parsed.fragment)):
                source = url
    elif item.kind == 'action':
        from .actions import _ACTION
        match = _ACTION.fullmatch(item.declared or '')
        if match:
            source = f'https://github.com/{match[1]}/{match[2]}/releases'
    elif item.kind == 'rust-toolchain' and item.declared == 'esp':
        source = 'https://github.com/esp-rs/rust-build/releases'
    elif item.kind == 'java-toolchain':
        source = {'temurin': 'https://github.com/adoptium',
                  'graalvm-community': 'https://github.com/graalvm/graalvm-ce-builds/releases'}.get(
                      item.metadata.get('distribution'), 'Java distribution not established')
    item.metadata['candidate_source'] = source
    item.metadata['comparison_basis'] = comparison
    item.metadata.setdefault('version_policy', 'latest-stable')
    item.metadata['compatibility'] = 'requires validation; candidate selection is not deployment approval'
    item.metadata['current_basis'] = (
        'observed' if item.observed else 'resolved' if any(item.metadata.get(key)
        for key in ('effective_version', 'locked_version')) else 'declared')
    if item.metadata.get('source_kind') == 'other' or item.metadata.get('replaced') == 'true':
        item.metadata['candidate_source'] = 'non-default dependency source; public lookup not authoritative'


def allows(item, candidate, current):
    """Filter before selecting a maximum, preserving each ecosystem's parser.

    latest-stable is discovery only, not a compatibility assertion. More
    restrictive policies require an established current version and fail closed.
    """
    policy = item.metadata.get('version_policy', 'latest-stable')
    if candidate is None:
        return False
    if policy == 'latest-stable':
        return True
    width = {'same-major': 1, 'same-minor': 2}.get(policy)
    if width is None or current is None:
        return False

    def parts(version):
        return ((version.major, version.minor) if hasattr(version, 'major')
                else tuple(version[:2]))

    return parts(candidate)[:width] == parts(current)[:width]


def no_candidate(item):
    item.candidate = None
    item.status = 'unknown'
    item.detail = ('No comparable stable candidate allowed by version policy: '
                   + item.metadata.get('version_policy', 'latest-stable'))