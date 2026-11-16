"""Prepare a narrowly scoped, locally reviewable Helm chart update."""
import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile

import yaml

from .helm import check_chart
from .tools import run_readonly
from .transaction import commit, preview


def _mapping(node):
    if not isinstance(node, yaml.MappingNode):
        return {}
    return {key.value: value for key, value in node.value if isinstance(key, yaml.ScalarNode)}


def _scalar(node):
    return node.value if isinstance(node, yaml.ScalarNode) else None


def _replace_chart(text: str, chart: str, current: str, target: str) -> str:
    matches = []
    for doc in yaml.compose_all(text, Loader=yaml.SafeLoader):
        top = _mapping(doc)
        if _scalar(top.get('kind')) != 'HelmRelease':
            continue
        spec = _mapping(top.get('spec'))
        chart_spec = _mapping(_mapping(spec.get('chart')).get('spec'))
        if _scalar(chart_spec.get('chart')) == chart and _scalar(chart_spec.get('version')) == current:
            node = chart_spec.get('version')
            if not isinstance(node, yaml.ScalarNode):
                raise ValueError('Chart version is not a scalar')
            matches.append(node)
    if len(matches) != 1:
        raise ValueError('Chart reference is missing or ambiguous')
    node = matches[0]
    # YAML quoted values keep their existing quote style.
    value = f'{node.style}{target}{node.style}' if node.style in ("'", '"') else target
    return text[:node.start_mark.index] + value + text[node.end_mark.index:]


def _clean_target(root: Path, path: str) -> None:
    try:
        tracked = subprocess.run(['git', 'ls-files', '--error-unmatch', '--', path], cwd=root,
                                 stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                 stderr=subprocess.DEVNULL, timeout=10, check=False)
        changed = subprocess.run(['git', 'status', '--porcelain=v1', '--', path], cwd=root,
                                 stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                 stderr=subprocess.DEVNULL, timeout=10, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise ValueError('Cannot verify Git working tree') from exc
    if tracked.returncode or changed.returncode or changed.stdout:
        raise ValueError('Target file is untracked, changed or cannot be checked')


def _validate(root: Path, relative: Path, replacement: bytes):
    """Render in an isolated temporary copy; never access a live cluster."""
    with tempfile.TemporaryDirectory(prefix='calcifer-update-render-') as directory:
        work = Path(directory)
        # Copy only cluster config; reject links to avoid copying arbitrary host files.
        source = root / 'clusters'
        for path in source.rglob('*'):
            if path.is_symlink():
                raise ValueError('Cluster tree contains a symlink')
        shutil.copytree(source, work / 'clusters')
        (work / relative).write_bytes(replacement)
        parent = work / relative.parent
        if not (parent / 'kustomization.yaml').is_file():
            raise ValueError('No local Kustomization available for chart validation')
        result = run_readonly(['kubectl', 'kustomize', str(parent)], cwd=work, timeout=60, output_limit=20 * 1024 * 1024)
        if result.status != 'ok':
            raise ValueError(f'Kustomize validation failed: {result.status}')


def prepare_chart(root, component, target, cluster, components, *, expected=None, write=False,
                  fetch=None, validator=_validate, require_clean=_clean_target):
    """Return a diff and review fingerprint; write only on matching re-preview."""
    choices = [c for c in components if c.kind == 'helm-chart' and c.name == component
               and cluster in c.clusters]
    if len(choices) != 1:
        raise ValueError('Chart is missing or ambiguous for the selected cluster')
    item = choices[0]
    if not item.clusters or len(item.clusters) != 1:
        raise ValueError('Shared or unresolved chart scope requires manual review')
    check_chart(item, [c for c in components if c.kind == 'helm-repository'], {}, **({'fetch': fetch} if fetch else {}))
    if item.status != 'update-available' or item.candidate != target:
        raise ValueError('Target is not the verified latest stable chart update')
    relative = Path(item.source)
    path = root / relative
    require_clean(root, item.source)
    original = path.read_text(encoding='utf-8')
    replacement = _replace_chart(original, item.name, item.declared, target).encode('utf-8')
    changes, diff = preview(root, {item.source: replacement}, {item.source})
    if not changes:
        raise ValueError('No change to prepare')
    validator(root, relative, replacement)
    fingerprint = hashlib.sha256((cluster + '\0' + item.source + '\0' + changes[0].original_hash + '\0').encode() + replacement).hexdigest()
    if write:
        if not expected or expected != fingerprint:
            raise ValueError('Preview fingerprint missing or stale')
        # Check again after rendering, just before transaction verification.
        require_clean(root, item.source)
        commit(root, changes, {item.source})
    return diff, fingerprint
