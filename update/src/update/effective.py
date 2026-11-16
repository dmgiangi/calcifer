"""Render local Kustomize targets without exposing resource or secret contents."""
import json
from pathlib import Path

import yaml

from .inventory import ManifestLoader
from .model import Component
from .scope import cluster_scopes
from .tools import isolated_workspace, run_readonly


def _references(value):
    """Extract images only; never return other rendered resource content."""
    if isinstance(value, dict):
        if value.get('kind') in ('Secret', 'SealedSecret'):
            return
        for key, item in value.items():
            if key == 'image' and isinstance(item, str):
                yield item
            elif key == 'image' and isinstance(item, dict):
                name = item.get('repository') or item.get('name')
                if isinstance(name, str):
                    registry, tag, digest = (item.get(k) for k in ('registry', 'tag', 'digest'))
                    yield ((f'{registry}/' if registry else '') + name + (f':{tag}' if tag else '')
                           + (f'@{digest}' if digest else ''))
            elif isinstance(item, (dict, list)):
                yield from _references(item)
    elif isinstance(value, list):
        for item in value:
            yield from _references(item)


def effective_images(root, components, scopes, *, runner=run_readonly):
    """Retain declarations for provenance, check rendered images per cluster.

    Only replace declaration checks when *every* build consuming that file
    succeeds. A failed render leaves the original check and an explicit gap.
    The disposable snapshot contains only locally resolved inputs; generators
    needing other files fail visibly, never read arbitrary files from the host.
    """
    root = root.resolve()
    targets = set()
    errors = []
    selected = {cluster for item in components for cluster in item.clusters}
    for cluster in selected:
        targets.add((cluster, f'clusters/calcifer-{cluster}'))
    for path, clusters in scopes.items():
        if path.endswith('.sops.yaml') or not path.endswith(('.yaml', '.yml')):
            continue
        try:
            for doc in yaml.load_all((root / path).read_text(encoding='utf-8'), Loader=ManifestLoader):
                if not isinstance(doc, dict) or doc.get('kind') != 'Kustomization':
                    continue
                if not str(doc.get('apiVersion', '')).startswith('kustomize.toolkit.fluxcd.io/'):
                    continue
                spec = doc.get('spec') or {}
                source = spec.get('sourceRef') or {}
                directory = spec.get('path')
                if isinstance(directory, str) and source.get('kind') == 'GitRepository' and source.get('name') == 'flux-system':
                    targets.update((cluster, directory.removeprefix('./')) for cluster in clusters if cluster in selected)
        except (OSError, ValueError, TypeError, AttributeError, yaml.YAMLError):
            errors.append(f'Cannot resolve Kustomize target declarations in {path}')
    expected, completed, derived = {}, set(), []
    for cluster, directory in sorted(targets):
        if Path(directory).is_absolute() or '..' in Path(directory).parts:
            errors.append(f'{cluster}: Kustomize target must be local and repository-relative')
            continue
        inputs, input_errors = cluster_scopes(root, {cluster: [Path(directory)]}, follow_flux=False)
        target = (cluster, directory)
        for path in inputs:
            expected.setdefault((path, cluster), set()).add(target)
        if input_errors:
            errors.append(f'{cluster}: unresolved Kustomize inputs in {directory}')
            continue
        try:
            with isolated_workspace(root, [Path(path) for path in inputs]) as work:
                result = runner(['kubectl', 'kustomize', str(work / directory)], cwd=work,
                                timeout=60, output_limit=20 * 1024 * 1024)
            if result.status != 'ok':
                errors.append(f'{cluster}: Kustomize rendering unavailable in {directory}: {result.status}')
                continue
            images = set()
            for doc in yaml.load_all(result.stdout, Loader=ManifestLoader):
                if not isinstance(doc, dict):
                    raise ValueError('Invalid rendered manifest')
                images.update(_references(doc))
            for image in sorted(images):
                derived.append(Component(image, 'image', directory + f'#rendered:{cluster}',
                                         declared=image, clusters=[cluster], status='unknown',
                                         metadata={'rendered_from': directory,
                                                   'source_files': json.dumps(sorted(inputs))}))
            completed.add(target)
        except (OSError, ValueError, TypeError, AttributeError, yaml.YAMLError):
            errors.append(f'{cluster}: invalid or unavailable Kustomize render in {directory}')
    for item in components:
        if item.kind != 'image' or not item.clusters:
            continue
        builds = [expected.get((item.source, cluster), set()) for cluster in item.clusters]
        if all(builds) and all(build <= completed for build in builds):
            item.metadata['effective_state'] = 'declaration-only'
            item.status = 'configured'
            item.detail = 'Source declaration only; effective images are checked in the per-cluster Kustomize render'
    return derived, errors