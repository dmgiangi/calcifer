"""Explicitly distinguish standard and vendor-managed toolchains."""
from .actions import latest_release
from .versions import stable_version
from .policies import allows, no_candidate
from .platform import check_bundled
from .java import check_java_toolchain


def check_rust_toolchain(item, cache, fetch=latest_release):
    current = stable_version(item.declared or '')
    if current is None:
        item.status = 'unknown'
        pins = item.metadata
        if item.declared == 'esp' and all(pins.get(key) for key in
                                          ('espup_version', 'xtensa_rust_version', 'esp_idf_version')):
            item.detail = (f"Espressif toolchain pins: espup {pins['espup_version']}, Xtensa Rust "
                           f"{pins['xtensa_rust_version']}, ESP-IDF {pins['esp_idf_version']}; "
                           f"ldproxy {pins.get('ldproxy_version', 'unrecorded')}, "
                           f"espflash {pins.get('espflash_version', 'unrecorded')}; "
                           "rebuild to validate compatibility")
        else:
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
        if not allows(item, latest, current):
            no_candidate(item)
            return
        item.status = 'update-available' if latest > current else 'current' if latest == current else 'ahead'
        item.detail = 'Source: rust-lang/rust releases; validate target toolchain compatibility'
    except (OSError, ValueError, TypeError):
        item.status = 'unavailable'
        item.detail = 'Rust toolchain release lookup failed'


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
        if item.metadata.get('version_policy', 'latest-stable') != 'latest-stable':
            no_candidate(item)
            return
        item.status = 'unknown'
        item.detail = 'Source: go.dev; go.mod directive may be a minimum, not the installed toolchain'
    except (OSError, ValueError, TypeError, KeyError):
        item.status = 'unavailable'
        item.detail = 'Go toolchain release lookup failed'
