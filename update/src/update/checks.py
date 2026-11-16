"""Bounded, deduplicated update checks over independent components."""
from concurrent.futures import ThreadPoolExecutor, as_completed

from .actions import check_action, resolve_ref
from .cache import SingleFlightCache
from .go_modules import check_go
from .helm import check_chart
from .maven import check_maven
from .oci import check_image
from .platform import check_platform
from .python_packages import check_python
from .rust_packages import check_rust
from .toolchains import check_bundled, check_rust_toolchain, check_java_toolchain, check_go_toolchain


def check_all(components, workers=8, progress=None):
    repos = [item for item in components if item.kind == 'helm-repository']
    caches = {key: SingleFlightCache() for key in ('action', 'go', 'maven', 'oci', 'helm', 'rust', 'python', 'platform', 'toolchain')}
    groups = {}
    supported = {'k3s', 'flux', 'action', 'go-dependency', 'go-locked', 'maven-dependency', 'maven-parent',
                 'maven-plugin', 'maven-managed', 'python-dependency', 'python-locked', 'image', 'docker-base',
                 'helm-chart', 'rust-dependency', 'rust-locked', 'k3s-component', 'rust-toolchain', 'java-toolchain', 'go-toolchain'}
    for item in components:
        if item.kind in supported and item.status != "unsupported":
            key = (item.kind, item.name, item.declared, item.observed, tuple(item.clusters), tuple(sorted(item.metadata.items())))
            groups.setdefault(key, []).append(item)

    def check(group):
        item = group[0]
        try:
            if item.kind in ('k3s', 'flux'):
                check_platform(item, caches['platform'])
            elif item.kind == 'k3s-component':
                check_bundled(item)
            elif item.kind == 'rust-toolchain':
                check_rust_toolchain(item, caches['toolchain'])
            elif item.kind == 'java-toolchain':
                check_java_toolchain(item, caches['toolchain'])
            elif item.kind == 'go-toolchain':
                check_go_toolchain(item, caches['toolchain'])
            elif item.kind == 'action':
                check_action(item, caches['action'], resolve=resolve_ref)
            elif item.kind in ('go-dependency', 'go-locked'):
                check_go(item, caches['go'])
            elif item.kind.startswith('maven-'):
                check_maven(item, caches['maven'])
            elif item.kind in ('python-dependency', 'python-locked'):
                check_python(item, caches['python'])
            elif item.kind in ('image', 'docker-base'):
                check_image(item, caches['oci'])
            elif item.kind == 'helm-chart':
                check_chart(item, repos, caches['helm'])
            elif item.kind in ('rust-dependency', 'rust-locked'):
                check_rust(item, caches['rust'])
        except Exception:
            # Do not expose unexpected library exceptions: they may contain
            # credentials or request headers. Continue other checks.
            item.status = 'unavailable'
            item.detail = 'Update check failed unexpectedly'
        for duplicate in group[1:]:
            duplicate.status = item.status
            duplicate.detail = item.detail
            duplicate.candidate = item.candidate
            for key in ('candidate_digest', 'platform_status', 'resolved_release'):
                if key in item.metadata:
                    duplicate.metadata[key] = item.metadata[key]

    total = len(groups)
    if progress:
        progress(f"0/{total} controlli completati")
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(check, group): group for group in groups.values()}
        for completed, future in enumerate(as_completed(futures), start=1):
            future.result()
            if progress:
                item = futures[future][0]
                progress(f"{completed}/{total} controlli completati ({item.kind}: {item.status})")
