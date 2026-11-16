"""Map discovered declarations to available read-only strategies and owners."""
from pathlib import PurePosixPath

from .image_prepare import _GROUPS
from .policies import record_provenance

CHECKED_KINDS = {
    'image': 'oci', 'docker-base': 'oci', 'helm-chart': 'helm', 'action': 'github-releases',
    'maven-dependency': 'maven-central', 'maven-parent': 'maven-central',
    'maven-plugin': 'maven-central', 'maven-managed': 'maven-central',
    'python-dependency': 'pypi', 'python-locked': 'pypi', 'go-dependency': 'go-proxy', 'go-locked': 'go-proxy',
    'rust-dependency': 'crates-io', 'rust-locked': 'crates-io',
    'k3s-component': 'k3s-bundled', 'rust-toolchain': 'rust-toolchain', 'java-toolchain': 'java-distribution', 'go-toolchain': 'go-releases',
}
INVENTORY_KINDS = {'helm-repository', 'maven', 'python', 'rust', 'go', 'python-local-project'}


def annotate(components):
    for item in components:
        record_provenance(item, components)
        parts = PurePosixPath(item.source).parts
        if parts[:2] == ('clusters', 'apps') and len(parts) > 2:
            owner = parts[2]
        elif len(parts) > 2 and parts[0] == 'clusters':
            owner = '/'.join(parts[2:-1]) or parts[1]
        elif parts[:2] == ('.github', 'workflows'):
            owner = 'ci'
        else:
            owner = parts[0] if len(parts) > 1 else 'repository'
        item.metadata['owner'] = owner
        group = next((name for name, policy in _GROUPS.items()
                      if item.kind == 'image' and item.declared and
                      item.declared.startswith((policy['image'] + '@', policy['image'] + ':'))
                      and item.source in policy['paths']), None)
        if group:
            item.metadata['update_group'] = group
            item.metadata['validators'] = 'registry-digest,platform,kustomize'
        if item.kind in CHECKED_KINDS:
            item.metadata['strategy'] = CHECKED_KINDS[item.kind]
            item.metadata.setdefault('validators', 'read-only; preparation requires an explicit policy')
            if item.source.startswith('clusters/') and not item.clusters:
                item.status = 'unsupported'
                item.detail = 'Cluster reachability unresolved; cannot determine impact safely'
            else:
                item.status = 'unknown'
                item.detail = 'Not yet checked'
        elif item.kind in INVENTORY_KINDS:
            item.status = 'configured'
            item.detail = 'Inventory source; versioned dependencies are reported separately'
        else:
            item.status = 'unsupported'
            item.detail = 'No complete strategy is configured for this declaration'
    return components
