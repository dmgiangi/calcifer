"""Inspect stable chart releases from explicitly configured Helm repositories."""
import re
from urllib.error import URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

import yaml
from .versions import stable_version
from .errors import describe_source_error
from .cache import cached
from . import registry
import json


def fetch_index(url: str):
    parsed = urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("Unsafe or unsupported repository URL")
    request = Request(url.rstrip("/") + "/index.yaml", headers={"User-Agent": "calcifer-update"})
    with urlopen(request, timeout=15) as response:
        data = response.read(32 * 1024 * 1024 + 1)
    if len(data) > 32 * 1024 * 1024:
        raise ValueError("Index too large")
    index = yaml.load(data, Loader=getattr(yaml, "CSafeLoader", yaml.SafeLoader))
    if not isinstance(index, dict) or not isinstance(index.get("entries"), dict):
        raise ValueError("Invalid index")
    return index["entries"]


def check_chart(item, repositories, cache, fetch=fetch_index):
    candidates = [repo for repo in repositories if repo.name == item.metadata.get("repository")
                  and repo.metadata.get("namespace") == item.metadata.get("namespace")
                  and repo.clusters == item.clusters]
    if len(candidates) != 1:
        item.detail = "Helm repository not resolved uniquely"
        return
    url = candidates[0].declared or ""
    if not url.startswith(("https://", "oci://")):
        item.detail = "Chart repository uses an unsupported source"
        return
    try:
        if url.startswith("oci://"):
            reference = url[len("oci://"):].rstrip("/") + "/" + item.name
            key = ("oci", reference)
            result = cached(cache, key, lambda: registry.list_tags(reference))
            if result.status != "ok":
                item.status = "unavailable"
                item.detail = "OCI chart lookup failed: " + result.status
                return
            entries = [{"version": tag.replace("_", "+")} for tag in json.loads(result.stdout).get("Tags", [])]
        else:
            entries = cached(cache, url, lambda: fetch(url)).get(item.name, [])
        stable = [(stable_version(str(entry.get("version", ""))), str(entry.get("version", "")))
                  for entry in entries if isinstance(entry, dict)]
        stable = [(version, text) for version, text in stable if version is not None]
        current = stable_version(item.declared or "")
        if not stable or current is None:
            item.status = "unknown"
            item.detail = "Cannot compare the chart release using stable three-part versions"
            return
        version, tag = max(stable)
        item.candidate = tag
        item.status = "update-available" if version > current else "current" if version == current else "ahead"
        item.detail = "Helm index checked; chart contents and image dependencies remain unresolved"
    except (URLError, OSError, ValueError, TypeError, yaml.YAMLError) as exc:
        item.status = "unavailable"
        item.detail = "Helm repository lookup failed: " + describe_source_error(exc)


def render_chart_images(item, repositories, root, runner=None):
    """Render a chart locally when its values are fully declared in the release."""
    import tempfile
    from pathlib import Path
    from .tools import run_readonly
    runner = runner or run_readonly
    sources = [repo for repo in repositories if repo.name == item.metadata.get('repository')
               and repo.metadata.get('namespace') == item.metadata.get('namespace')
               and repo.clusters == item.clusters]
    if len(sources) != 1 or not (sources[0].declared or '').startswith(('https://', 'oci://')):
        return None, 'Chart repository is not uniquely resolvable for rendering'
    if not item.declared or not stable_version(item.declared):
        return None, 'Chart version is not a fixed stable version'
    try:
        docs = list(yaml.safe_load_all((root / item.source).read_text(encoding='utf-8')))
        matches = []
        for doc in docs:
            if not isinstance(doc, dict) or doc.get('kind') != 'HelmRelease':
                continue
            spec = doc.get('spec') or {}
            chart = ((spec.get('chart') or {}).get('spec') or {})
            if chart.get('chart') == item.name and str(chart.get('version')) == item.declared:
                matches.append(doc)
        if len(matches) != 1:
            return None, 'Chart release definition is missing or ambiguous'
        doc = matches[0]
        spec = doc.get('spec') or {}
        if spec.get('valuesFrom'):
            return None, 'Chart references external values; rendering requires operator-supplied data'
        values = spec.get('values') or {}
        if not isinstance(values, dict):
            return None, 'Chart values are not a mapping'
        metadata = doc.get('metadata') or {}
        release_name = spec.get('releaseName') or metadata.get('name')
        namespace = spec.get('targetNamespace') or metadata.get('namespace', 'default')
        if not isinstance(release_name, str) or not isinstance(namespace, str):
            return None, 'Chart release name or namespace is unknown'
        safe_name = re.compile(r'^[A-Za-z0-9][A-Za-z0-9._-]*$')
        if not all(safe_name.fullmatch(value) for value in (release_name, namespace, item.name)):
            return None, 'Chart release name, namespace or chart name is invalid'
        with tempfile.TemporaryDirectory(prefix='calcifer-helm-values-') as directory:
            path = Path(directory) / 'values.yaml'
            path.write_text(yaml.safe_dump(values), encoding='utf-8')
            repository = sources[0].declared
            chart_arg = repository.rstrip('/') + '/' + item.name if repository.startswith('oci://') else item.name
            repo_args = [] if repository.startswith('oci://') else ['--repo', repository]
            result = runner(['helm', 'template', release_name, chart_arg, *repo_args,
                             '--version', item.declared, '--namespace', namespace, '--values', str(path),
                             '--include-crds'], cwd=Path(directory), timeout=60, output_limit=20 * 1024 * 1024)
        if result.status != 'ok':
            if result.status == 'missing':
                return None, 'Helm executable not found; install Helm to render charts'
            return None, f'Chart rendering unavailable: {result.status}'
        images = set()
        def scan(node):
            if isinstance(node, dict):
                if node.get('kind') in ('Secret', 'SealedSecret'):
                    return
                for key, value in node.items():
                    if key == 'image' and isinstance(value, str):
                        images.add(value)
                    elif isinstance(value, (dict, list)):
                        scan(value)
            elif isinstance(node, list):
                for value in node:
                    scan(value)
        for document in yaml.safe_load_all(result.stdout):
            scan(document)
        from .oci import split_reference
        if any(split_reference(image) is None for image in images):
            return None, 'Rendered chart includes unresolved or invalid image references'
        return sorted(images), None
    except (OSError, UnicodeError, yaml.YAMLError, ValueError, TypeError, AttributeError):
        return None, 'Chart values or rendered manifests could not be parsed'
