"""Resolve cluster ownership through local Flux and Kustomize references."""
from pathlib import Path
import yaml

from .inventory import ManifestLoader


def cluster_scopes(root: Path, starts=None, *, follow_flux=True):
    root = root.resolve()
    owners: dict[str, set[str]] = {}
    errors: list[str] = []
    if starts is None:
        starts = {cluster: [root / 'clusters' / f'calcifer-{cluster}']
                  for cluster in ('home', 'cloud') if (root / 'clusters' / f'calcifer-{cluster}').is_dir()}
    for cluster, entries in starts.items():
        pending = [root / entry for entry in entries]
        opaque = set()
        visited = set()
        while pending:
            candidate = pending.pop()
            try:
                resolved = candidate.resolve(strict=True)
                resolved.relative_to(root)
                if resolved.is_dir():
                    resolved = resolved / 'kustomization.yaml'
                    if not resolved.exists():
                        alternate = resolved.with_name('kustomization.yml')
                        resolved = alternate if alternate.exists() else resolved
                if resolved in visited:
                    continue
                visited.add(resolved)
                relative = str(resolved.relative_to(root))
                owners.setdefault(relative, set()).add(cluster)
                if resolved in opaque:
                    continue
                if not resolved.is_file() or resolved.name.endswith('.sops.yaml'):
                    continue
                if resolved.suffix not in ('.yaml', '.yml'):
                    continue
                for doc in yaml.load_all(resolved.read_text(encoding='utf-8'), Loader=ManifestLoader):
                    if not isinstance(doc, dict) or doc.get('kind') != 'Kustomization':
                        continue
                    api = str(doc.get('apiVersion', ''))
                    if api.startswith('kustomize.toolkit.fluxcd.io/'):
                        spec = doc.get('spec') or {}
                        path = spec.get('path') if isinstance(spec, dict) else None
                        source = (spec.get('sourceRef') or {}) if isinstance(spec, dict) else {}
                        if follow_flux and path and source.get('kind') == 'GitRepository' and source.get('name') == 'flux-system':
                            pending.append(root / path)
                    elif api.startswith('kustomize.config.k8s.io/'):
                        refs = list(doc.get('resources') or []) + list(doc.get('bases') or [])
                        refs += list(doc.get('components') or []) + list(doc.get('configurations') or [])
                        refs += [x.get('path') for x in (doc.get('patches') or []) if isinstance(x, dict)]
                        refs += [x for x in (doc.get('patchesStrategicMerge') or []) if isinstance(x, str)]
                        refs += [x.get('path') for x in (doc.get('patchesJson6902') or []) if isinstance(x, dict)]
                        if not follow_flux and doc.get('secretGenerator'):
                            errors.append(f'{cluster}: secret generator inputs require operator handling in {relative}')
                        for generator in doc.get('configMapGenerator') or []:
                            for ref in list(generator.get('files') or []) + list(generator.get('envs') or []):
                                if not isinstance(ref, str) or '://' in ref:
                                    errors.append(f'{cluster}: unresolved generator input in {relative}')
                                    continue
                                file_path = resolved.parent / ref.split('=', 1)[-1]
                                opaque.add(file_path.resolve())
                                pending.append(file_path)
                        for ref in refs:
                            if isinstance(ref, str) and not ('://' in ref or ref.startswith('git@')):
                                pending.append(resolved.parent / ref)
                            else:
                                errors.append(f'{cluster}: unresolved non-local reference in {relative}')
            except (OSError, ValueError, yaml.YAMLError, TypeError, AttributeError):
                try:
                    name = str(candidate.relative_to(root))
                except ValueError:
                    name = '[outside repository]'
                errors.append(f'{cluster}: cannot resolve {name}')
    return {path: sorted(clusters) for path, clusters in owners.items()}, errors
