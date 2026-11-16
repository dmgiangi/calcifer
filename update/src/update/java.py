"""Distribution-aware Java build-toolchain checks, separate from POM targets."""
import json
import re
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen

from .actions import latest_release
from .cache import cached
from .policies import allows, no_candidate

_VERSION = re.compile(r'^(?:jdk-)?([0-9]{1,2})\.([0-9]+)\.([0-9]+)(?:\.([0-9]+))?(?:\+([0-9]+(?:\.[0-9]+)*))?$')
_DECLARATION = re.compile(r'^(?:jdk-)?[0-9]{1,2}(?:\.[0-9]+){0,3}(?:\+[0-9]+(?:\.[0-9]+)*)?$')


def _is_lts_major(major):
    return major in (8, 11) or (major >= 17 and (major - 17) % 4 == 0)


def _identity(value):
    match = _VERSION.fullmatch(value or '')
    if not match:
        return None
    major, minor, patch, update, build = match.groups()
    return (int(major), int(minor), int(patch), int(update or 0),
            tuple(map(int, build.split('.'))) if build else ())


def java_source(distribution, major):
    if distribution == 'temurin':
        return f'https://github.com/adoptium/temurin{major}-binaries/releases'
    if distribution == 'graalvm-community':
        return 'https://github.com/graalvm/graalvm-ce-builds/releases'
    return 'Java distribution not established'


def _graalvm_metadata(path):
    request = Request('https://api.github.com/repos/graalvm/graalvm-ce-builds/' + path,
                      headers={'User-Agent': 'calcifer-update', 'Accept': 'application/vnd.github+json'})
    with urlopen(request, timeout=15) as response:
        raw = response.read(1024 * 1024 + 1)
    if len(raw) > 1024 * 1024:
        raise ValueError('Java release metadata too large')
    return json.loads(raw)


def latest_java_release(distribution, major):
    if distribution not in ('temurin', 'graalvm-community'):
        raise ValueError('Unsupported Java distribution')
    if not _is_lts_major(major):
        return None
    if distribution == 'temurin':
        return latest_release('adoptium', f'temurin{major}-binaries')
    # Enumerate compact tag refs for this JDK line, not the enormous release
    # history with every asset. A tag alone is not a verified stable release.
    data = _graalvm_metadata(f'git/matching-refs/tags/jdk-{major}.')
    if not isinstance(data, list) or any(not isinstance(entry, dict) for entry in data):
        raise ValueError('Invalid Java release metadata')
    candidates = []
    for entry in data:
        ref = entry.get('ref', '')
        tag = ref.removeprefix('refs/tags/') if isinstance(ref, str) and ref.startswith('refs/tags/jdk-') else ''
        version = _identity(tag)
        if version and version[0] == major:
            candidates.append((version, tag))
    if len(candidates) > 100:
        raise ValueError('Too many Java release candidates')
    for _, tag in sorted(set(candidates), reverse=True):
        try:
            release = _graalvm_metadata('releases/tags/' + quote(tag, safe=''))
        except HTTPError as exc:
            if exc.code == 404:
                continue
            raise
        if not isinstance(release, dict) or release.get('tag_name') != tag:
            raise ValueError('Unverified Java release metadata')
        if not release.get('draft') and not release.get('prerelease'):
            return tag
    return None


def check_java_toolchain(item, cache, fetch=latest_java_release):
    item.candidate = None
    if item.metadata.get('toolchain_role') != 'build-jdk':
        item.metadata['comparison_basis'] = 'project language target; not an installed JDK'
        item.status = 'unknown'
        item.detail = 'Project Java target only; inspect the separate CI JDK distribution/version declaration'
        return
    item.metadata['comparison_basis'] = 'declared build JDK; not an installed runtime observation'
    distribution = item.metadata.get('distribution')
    if distribution not in ('temurin', 'graalvm-community'):
        item.status = 'unknown'
        item.detail = 'Java build distribution is unsupported or dynamic; no substitute distribution selected'
        return
    declared = item.declared or ''
    if not _DECLARATION.fullmatch(declared):
        item.status = 'unknown'
        item.detail = 'Dynamic or nonstandard Java build version; resolve the CI input manually'
        return
    major = int(declared.removeprefix('jdk-').split('.', 1)[0].split('+', 1)[0])
    if not 8 <= major <= 99:
        item.status = 'unknown'
        item.detail = 'Java build major is not supported by this checker'
        return
    if not _is_lts_major(major):
        item.status = 'unknown'
        item.detail = 'Declared Java build line is not LTS; no non-LTS candidate will be recommended'
        return
    item.metadata['candidate_source'] = java_source(distribution, major)
    current = _identity(declared)
    try:
        release = cached(cache, ('java', distribution, major), lambda: fetch(distribution, major))
        latest = _identity(release) if isinstance(release, str) else None
        if latest is None or latest[0] != major or (distribution == 'temurin' and not latest[4]):
            item.status = 'unknown'
            item.detail = 'No comparable stable release in the requested Java distribution/major'
            return
        if not allows(item, latest, current):
            no_candidate(item)
            return
        item.candidate = release
        # Temurin tags include build identity; a patch without its build is
        # still a selector, not an immutable build declaration.
        if current is None or (distribution == 'temurin' and not current[4]):
            item.status = 'unknown'
            item.detail = f'Source: {distribution}; CI JDK selector is not an exact release/build pin'
            return
        item.status = 'update-available' if latest > current else 'current' if latest == current else 'ahead'
        item.detail = f'Source: {distribution}; declared CI JDK compared, not proof of the installed runtime'
    except (OSError, ValueError, TypeError, KeyError):
        item.status = 'unavailable'
        item.detail = 'Java distribution release lookup failed'