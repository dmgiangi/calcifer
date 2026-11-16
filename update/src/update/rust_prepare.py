"""Prepare a Rust lockfile update in a disposable project copy."""
import hashlib
import os
from pathlib import Path
import re
import shutil
import tempfile
import tomllib

from .prepare import _clean_target
from .rust_packages import latest_crate
from .tools import run_readonly
from .transaction import commit, contained_file, digest, preview
from .versions import stable_version

_NAME = re.compile(r'^[A-Za-z0-9_-]+$')
_PROJECT = re.compile(r'^[A-Za-z0-9_-]+$')


def _snapshot(root):
    result = {}
    for file in root.rglob('*'):
        if file.is_symlink():
            raise ValueError('Project contains a symbolic link')
        if file.is_file():
            result[str(file.relative_to(root))] = digest(file.read_bytes())
    return result


def prepare_rust(root, project, crate, target, *, expected=None, write=False,
                 fetch=latest_crate, runner=run_readonly, require_clean=_clean_target):
    if not _PROJECT.fullmatch(project or '') or not _NAME.fullmatch(crate or '') or stable_version(target or '') is None:
        raise ValueError('Invalid project, crate or stable version')
    # This initial policy admits only the inventoried repository project.
    if project != 'therment':
        raise ValueError('Rust project has no explicit update policy')
    manifest_name = f'{project}/Cargo.toml'
    lock_name = f'{project}/Cargo.lock'
    manifest = contained_file(root, Path(manifest_name))
    lock = contained_file(root, Path(lock_name))
    require_clean(root, manifest_name)
    require_clean(root, lock_name)
    source_hash = digest(manifest.read_bytes())
    source_lock_hash = digest(lock.read_bytes())
    try:
        latest = fetch(crate)
    except (OSError, ValueError, KeyError, TypeError) as exc:
        raise ValueError('Cannot verify crate release') from exc
    if latest != target:
        raise ValueError('Target is not the verified latest stable crate release')
    with tempfile.TemporaryDirectory(prefix='calcifer-cargo-update-') as directory:
        work = Path(directory) / project
        source = root / project
        # Do not follow symlinks into the host or include build artifacts.
        ignored = {'target', '.git', '.embuild', '__pycache__', '.wifi-credentials.toml', '.mqtt-credentials.toml'}
        for directory, directories, files in os.walk(source, followlinks=False):
            directories[:] = [name for name in directories if name not in ignored]
            for name in directories + [name for name in files if name not in ignored]:
                if (Path(directory) / name).is_symlink():
                    raise ValueError('Project contains a symbolic link')
        shutil.copytree(source, work, ignore=shutil.ignore_patterns(*ignored))
        before = _snapshot(work)
        result = runner(['cargo', 'update', '-p', crate, '--precise', target], cwd=work, timeout=120)
        if result.status != 'ok':
            raise ValueError(f'Cargo update failed: {result.status}')
        after = _snapshot(work)
        changed = {name for name in set(before) | set(after) if before.get(name) != after.get(name)}
        if changed != {'Cargo.lock'}:
            raise ValueError('Cargo changed unexpected files or did not change Cargo.lock')
        replacement = (work / 'Cargo.lock').read_bytes()
        try:
            parsed = tomllib.loads(replacement.decode('utf-8'))
            matches = [entry for entry in parsed.get('package', []) if entry.get('name') == crate and entry.get('version') == target]
        except (ValueError, UnicodeError, AttributeError, TypeError) as exc:
            raise ValueError('Cargo generated an invalid lockfile') from exc
        if not matches:
            raise ValueError('Cargo did not select the requested crate version')
    if digest(manifest.read_bytes()) != source_hash or digest(lock.read_bytes()) != source_lock_hash:
        raise ValueError('Source files changed during Cargo preparation')
    changes, diff = preview(root, {lock_name: replacement}, {lock_name})
    fingerprint = hashlib.sha256((project + '\0' + crate + '\0' + target + '\0' + source_hash + '\0' + changes[0].original_hash).encode() + replacement).hexdigest()
    if write:
        if expected != fingerprint:
            raise ValueError('Preview fingerprint missing or stale')
        require_clean(root, manifest_name)
        require_clean(root, lock_name)
        if digest(manifest.read_bytes()) != source_hash:
            raise ValueError('Manifest changed since preview')
        commit(root, changes, {lock_name})
    return diff, fingerprint
