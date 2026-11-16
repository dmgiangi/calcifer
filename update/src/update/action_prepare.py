"""Prepare reviewed updates of GitHub Actions references across workflows."""
import hashlib
from pathlib import Path
import re

import yaml

from .actions import check_action
from .prepare import _clean_target
from .transaction import commit, contained_file, preview


def prepare_action(root, reference, target, items, *, expected=None, write=False,
                   fetch=None, require_clean=_clean_target):
    matched = [item for item in items if item.kind == 'action' and item.declared == reference]
    if not matched or '@' not in reference:
        raise ValueError('Action reference is not found or not supported')
    item = matched[0]
    check_action(item, {}, **({'fetch': fetch} if fetch else {}))
    if item.status != 'update-available' or item.candidate != target:
        raise ValueError('Target is not the verified latest stable action release')
    name = reference.rsplit('@', 1)[0]
    replacement_ref = name + '@' + target
    pattern = re.compile(r'(?m)^([ \t]*-?[ \t]*uses:[ \t]*)' + re.escape(reference) + r'([ \t]*(?:#.*)?)$')
    replacements = {}
    for path in sorted({item.source for item in matched}):
        if not path.startswith('.github/workflows/'):
            raise ValueError('Action path is outside workflow directory')
        require_clean(root, path)
        source = contained_file(root, Path(path))
        text = source.read_text(encoding='utf-8')
        updated, count = pattern.subn(lambda m: m.group(1) + replacement_ref + m.group(2), text)
        if not count:
            raise ValueError('Expected workflow action reference not found')
        yaml.safe_load(updated)
        replacements[path] = updated.encode('utf-8')
    changes, diff = preview(root, replacements, set(replacements))
    fingerprint = hashlib.sha256(('\0'.join([reference, target] + [x.path + ':' + x.original_hash for x in changes])).encode()).hexdigest()
    if not changes:
        raise ValueError('No change to prepare')
    if write:
        if not expected or expected != fingerprint:
            raise ValueError('Preview fingerprint missing or stale')
        for change in changes:
            require_clean(root, change.path)
        commit(root, changes, set(replacements))
    return diff, fingerprint
