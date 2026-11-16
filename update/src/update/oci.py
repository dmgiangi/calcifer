"""Conservative OCI registry discovery through skopeo."""
import hashlib
import json
from pathlib import Path
import re

from .tools import run_readonly
from . import registry
from .versions import stable_version
from .policies import allows, no_candidate

_VALID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]*$")


def split_reference(reference: str):
    name_tag, _, digest = reference.partition('@')
    slash = name_tag.rfind('/')
    colon = name_tag.rfind(':')
    tag = name_tag[colon + 1:] if colon > slash else None
    name = name_tag[:colon] if colon > slash else name_tag
    if not _VALID.fullmatch(name) or (digest and not re.fullmatch(r'sha256:[0-9a-f]{64}', digest)):
        return None
    return name, tag, digest or None


_VERSIONED_TAG = re.compile(r'^(?P<prefix>(?:[A-Za-z_][A-Za-z0-9_.-]*?-)?v?)(?P<version>[0-9]+(?:\.[0-9]+){0,2})(?P<suffix>[-_][A-Za-z][A-Za-z0-9_.-]*)?$')


def _tag_parts(tag):
    match = _VERSIONED_TAG.fullmatch(tag or '')
    if not match:
        return None
    prefix, version, suffix = match.group('prefix') or '', match.group('version'), match.group('suffix') or ''
    if re.search(r'(?:^|[-_.])(alpha|beta|rc|preview|nightly|dev)(?:[0-9._-]|$)', suffix, re.I):
        return None
    numbers = version.split('.')
    # A leading v is a common spelling of the same version, not a flavor.
    if prefix == 'v' or prefix.endswith('-v'):
        prefix = prefix[:-1]
    return (prefix, suffix, len(numbers)), tuple(int(part) for part in numbers)


def check_image(item, cache, runner=run_readonly):
    ref = split_reference(item.declared or '')
    if not ref:
        item.status = 'unknown'
        item.detail = 'Image reference is dynamic or invalid; manual review required'
        return
    name, tag, digest = ref
    parsed_current = _tag_parts(tag) if tag else None
    if tag and parsed_current is None:
        item.status = 'unknown'
        item.detail = 'Tag has no comparable stable version; manual review required'
        return
    if name not in cache:
        result = runner(['skopeo', 'list-tags', f'docker://{name}'], cwd=Path.cwd())
        if result.status == 'missing':
            result = registry.list_tags(name)
        if result.status != 'ok':
            cache[name] = (None, result.status)
        else:
            try:
                data = json.loads(result.stdout)
                tags = data['Tags']
                if not isinstance(tags, list) or any(not isinstance(tag, str) for tag in tags):
                    raise ValueError('invalid tags')
                cache[name] = (tags, None)
            except (ValueError, TypeError, KeyError):
                cache[name] = (None, 'invalid response')
    tags, error = cache[name]
    if error:
        item.status = 'unavailable'
        item.detail = f'OCI tag lookup: {error}'
        return
    parsed = []
    for candidate in tags:
        parts = _tag_parts(candidate)
        if parts and allows(item, parts[1], parsed_current[1] if parsed_current else None) and ((parsed_current and parts[0] == parsed_current[0]) or
                      (not tag and parts[0][1] == '' and parts[0][2] == 3 and parts[0][0] in ('', 'v'))):
            parsed.append((parts[1], candidate))
    if not parsed:
        no_candidate(item)
        return
    version, candidate = max(parsed)
    item.candidate = candidate
    current = parsed_current[1] if parsed_current else None
    item.status = ('update-available' if version > current else 'current' if version == current else 'ahead') if current else 'unknown'
    manifest_key = ('manifest', name, candidate)
    if manifest_key not in cache:
        inspected = runner(['skopeo', 'inspect', '--raw', f'docker://{name}:{candidate}'], cwd=Path.cwd())
        if inspected.status == 'missing':
            inspected = registry.inspect_manifest(name, candidate)
        cache[manifest_key] = inspected
    inspected = cache[manifest_key]
    if inspected.status != 'ok':
        item.detail = f'Tag comparison only; digest and platform unavailable: {inspected.status}'
        return
    raw = inspected.stdout.encode('utf-8')
    candidate_digest = 'sha256:' + hashlib.sha256(raw).hexdigest()
    try:
        manifest = json.loads(raw)
        if not isinstance(manifest, dict) or manifest.get('schemaVersion') != 2 or not any(key in manifest for key in ('config', 'manifests')):
            raise ValueError('Invalid OCI manifest')
        item.metadata['candidate_digest'] = candidate_digest
        entries = manifest.get('manifests')
        platform = item.metadata.get('platform')
        if platform and isinstance(entries, list):
            os_name, architecture = platform.split('/', 1)
            supported = any(isinstance(entry, dict) and entry.get('platform', {}).get('os') == os_name and
                            entry.get('platform', {}).get('architecture') == architecture for entry in entries)
            item.metadata['platform_status'] = 'supported' if supported else 'unsupported'
            if not supported:
                item.status = 'incompatible'
            item.detail = 'Candidate digest and requested platform checked against manifest index'
        else:
            item.metadata['platform_status'] = 'unknown'
            item.detail = 'Candidate digest checked; platform requires explicit verification'
        if digest and not tag:
            if digest == candidate_digest:
                item.status = 'current'
                item.detail += '; deployed digest matches latest stable tag'
            else:
                item.detail += '; current digest has no verified version; update ordering is unknown'
        if digest and candidate == tag and digest != candidate_digest and item.status == 'current':
            item.status = 'update-available'
            item.detail += '; tag now resolves to a different digest'
    except (ValueError, TypeError, AttributeError):
        item.status = 'unknown'
        item.detail = 'Registry returned an invalid image manifest'
