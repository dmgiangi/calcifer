"""Prepare selected grouped image references only after registry verification."""
import hashlib
import json
from pathlib import Path
import re

from .prepare import _clean_target, _validate
from . import registry
from .tools import run_readonly
from .transaction import commit, contained_file, preview

_GROUPS = {
    'home-assistant': {
        'image': 'ghcr.io/home-assistant/home-assistant', 'clusters': ['home'],
        'paths': ['clusters/calcifer-home/apps/home-automation/home-assistant/deployment.yaml',
                  'clusters/calcifer-home/apps/home-automation/home-assistant/backup-cronjob.yaml'],
        'tagged': True,
    },
    'authorization-server': {
        'image': 'ghcr.io/dmgiangi/calcifer-authorization-server', 'clusters': ['cloud', 'home'],
        'paths': ['clusters/apps/authorization-server/overlays/cloud/image-patch.yaml',
                  'clusters/apps/authorization-server/overlays/home/image-patch.yaml'],
        'tagged': False,
        'placeholders': {'clusters/apps/authorization-server/base/deployment.yaml': 'ghcr.io/dmgiangi/calcifer-authorization-server:unreleased'},
    },
}
_DIGEST = re.compile(r'^sha256:[0-9a-f]{64}$')
_TAG = re.compile(r'^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$')


def verify_image(name, tag, digest, platform, runner=run_readonly):
    if not _DIGEST.fullmatch(digest or '') or (tag is not None and not _TAG.fullmatch(tag)):
        raise ValueError('Invalid image tag or digest')
    if not re.fullmatch(r'linux/(amd64|arm64)', platform or ''):
        raise ValueError('An explicit supported platform is required')
    reference = f'{name}:{tag}' if tag else f'{name}@{digest}'
    result = runner(['skopeo', 'inspect', '--raw', f'docker://{reference}'], cwd=Path.cwd(), timeout=60)
    if result.status == 'missing':
        result = registry.inspect_manifest(name, tag or digest)
    if result.status != 'ok':
        raise ValueError(f'Cannot inspect registry image: {result.status}')
    raw = result.stdout.encode('utf-8')
    if 'sha256:' + hashlib.sha256(raw).hexdigest() != digest:
        raise ValueError('Registry manifest digest does not match requested digest')
    try:
        manifest = json.loads(raw)
        if not isinstance(manifest, dict) or manifest.get('schemaVersion') != 2:
            raise ValueError('Invalid manifest schema')
        os_name, architecture = platform.split('/')
        entries = manifest.get('manifests')
        if entries is not None:
            if not isinstance(entries, list) or not any(e.get('platform', {}).get('os') == os_name and
                                                          e.get('platform', {}).get('architecture') == architecture and
                                                          _DIGEST.fullmatch(e.get('digest', ''))
                                                          for e in entries if isinstance(e, dict)):
                raise ValueError('Platform absent from manifest index')
        else:
            # A single-platform image stores platform metadata in its config.
            result = runner(['skopeo', 'inspect', '--config', f'docker://{name}@{digest}'], cwd=Path.cwd(), timeout=60)
            if result.status == 'missing':
                config_digest = (manifest.get('config') or {}).get('digest', '')
                result = registry.inspect_blob(name, config_digest)
            if result.status != 'ok':
                raise ValueError('Cannot retrieve image config')
            config = json.loads(result.stdout)
            if config.get('os') != os_name or config.get('architecture') != architecture:
                raise ValueError('Image platform does not match')
    except (ValueError, KeyError, TypeError, AttributeError) as exc:
        raise ValueError('Cannot verify requested image platform') from exc


def prepare_image(root, component, target, cluster, platform, *, expected=None, write=False,
                  verify=verify_image, validator=_validate, require_clean=_clean_target):
    group = _GROUPS.get(component)
    if not group:
        raise ValueError('Image preparation is not supported for this component')
    requested = ['cloud', 'home'] if cluster == 'all' else [cluster]
    if sorted(requested) != sorted(group['clusters']):
        raise ValueError('Selected cluster scope does not match the complete image group')
    if group['tagged']:
        tag, separator, digest = target.partition('@')
        if not separator:
            raise ValueError('A version tag and digest are both required')
    else:
        tag, digest = None, target
    verify(group['image'], tag, digest, platform)
    # Do not omit a newly added occurrence outside the explicitly mapped group.
    image_line = re.compile(r'(?m)^[ \t]*(?:-[ \t]*)?image:[ \t]*(' + re.escape(group['image']) + r'(?::[^\s#@]+)?(?:@[^\s#]+)?)(?:[ \t]*(?:#.*)?)$')
    for path in (root / 'clusters').rglob('*.yaml'):
        if path.is_symlink():
            raise ValueError('Cluster tree contains a symlink')
        if path.name.endswith('.sops.yaml'):
            continue
        relative = str(path.relative_to(root))
        for match in image_line.finditer(path.read_text(encoding='utf-8')):
            if relative not in group['paths'] and group.get('placeholders', {}).get(relative) != match.group(1):
                raise ValueError('Unmapped image reference in ' + relative)
    replacements = {}
    previous = set()
    image = re.escape(group['image'])
    pattern = re.compile(r'(?m)^([ \t]*(?:-[ \t]*)?image:[ \t]*)(' + image + r'(?:[:][^@\s]+)?@sha256:[0-9a-f]{64})([ \t]*(?:#.*)?)$')
    replacement_ref = f"{group['image']}:{tag}@{digest}" if tag else f"{group['image']}@{digest}"
    for name in group['paths']:
        require_clean(root, name)
        path = contained_file(root, Path(name))
        text = path.read_text(encoding='utf-8')
        matches = list(pattern.finditer(text))
        if not matches:
            raise ValueError('A required image reference was not found')
        previous.update(match.group(2) for match in matches)
        replacements[name] = pattern.sub(lambda match: match.group(1) + replacement_ref + match.group(3), text).encode('utf-8')
    if len(previous) != 1:
        raise ValueError('Existing image references are inconsistent; manual review required')
    changes, diff = preview(root, replacements, set(group['paths']))
    if len(changes) != len(group['paths']):
        raise ValueError('Not every required file would change')
    for change in changes:
        validator(root, Path(change.path), change.replacement)
    identity = '\0'.join([component, target, platform, *sorted(requested)] +
                         [change.path + ':' + change.original_hash for change in changes]).encode()
    fingerprint = hashlib.sha256(identity).hexdigest()
    if write:
        if not expected or expected != fingerprint:
            raise ValueError('Preview fingerprint missing or stale')
        for change in changes:
            require_clean(root, change.path)
        commit(root, changes, set(group['paths']))
    return diff, fingerprint, sorted(requested)
