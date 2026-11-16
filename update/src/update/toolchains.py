"""Explicitly distinguish standard and vendor-managed toolchains."""
from .actions import latest_release
from .versions import stable_version


def check_rust_toolchain(item, cache, fetch=latest_release):
    current = stable_version(item.declared or '')
    if current is None:
        item.status = 'unknown'
        item.detail = 'Named or vendor-managed Rust toolchain: check its provider and compatibility manually'
        return
    try:
        if 'rust' not in cache:
            cache['rust'] = fetch('rust-lang', 'rust')
        candidate = cache['rust']
        latest = stable_version(candidate)
        if latest is None:
            raise ValueError('unrecognized release')
        item.candidate = candidate
        item.status = 'update-available' if latest > current else 'current' if latest == current else 'ahead'
        item.detail = 'Source: rust-lang/rust releases; validate target toolchain compatibility'
    except (OSError, ValueError, TypeError):
        item.status = 'unavailable'
        item.detail = 'Rust toolchain release lookup failed'


def check_bundled(item):
    item.status = 'unknown'
    item.detail = ('K3s-bundled component; exact available version depends on K3s. '
                   'Inspect the live HelmChart and K3s release notes before upgrading the host')


def check_java_toolchain(item, cache, fetch=latest_release):
    import re
    major = item.declared or ''
    if not re.fullmatch(r'[0-9]{1,2}', major) or not 8 <= int(major) <= 99:
        item.status = 'unknown'
        item.detail = 'Java toolchain is property-based or nonstandard; resolve it manually'
        return
    try:
        if major not in cache:
            cache[major] = fetch('adoptium', f'temurin{major}-binaries')
        release = cache[major]
        if not release.startswith('jdk-' + major):
            raise ValueError('unrecognized Java release')
        item.candidate = release
        item.status = 'unknown'
        item.detail = 'Source: Eclipse Temurin; installed patch level and distribution are not known'
    except (OSError, ValueError, TypeError):
        item.status = 'unavailable'
        item.detail = 'Java toolchain release lookup failed'


def latest_go():
    import json
    from urllib.request import Request, urlopen
    request = Request('https://go.dev/dl/?mode=json', headers={'User-Agent': 'calcifer-update'})
    with urlopen(request, timeout=15) as response:
        data = json.loads(response.read(1024 * 1024 + 1))
    versions = [entry.get('version', '') for entry in data if entry.get('stable')]
    parsed = [(stable_version(version.removeprefix('go')), version) for version in versions]
    parsed = [(version, tag) for version, tag in parsed if version is not None]
    if not parsed:
        raise ValueError('No comparable Go release')
    return max(parsed)[1]


def check_go_toolchain(item, cache, fetch=latest_go):
    try:
        if 'go' not in cache:
            cache['go'] = fetch()
        latest = cache['go']
        if not stable_version(latest.removeprefix('go')):
            raise ValueError('Invalid Go release')
        item.candidate = latest
        item.status = 'unknown'
        item.detail = 'Source: go.dev; go.mod directive may be a minimum, not the installed toolchain'
    except (OSError, ValueError, TypeError, KeyError):
        item.status = 'unavailable'
        item.detail = 'Go toolchain release lookup failed'
