"""Prepare selected Java, Python and Go dependency updates in temporary copies."""
import hashlib
from pathlib import Path
import re
import tempfile
import tomllib
import xml.etree.ElementTree as ET

from packaging.requirements import Requirement

from .go_modules import check_go
from .maven import check_maven
from .model import Component
from .prepare import _clean_target
from .python_packages import check_python
from .rust_prepare import _snapshot
from .tools import isolated_workspace, run_readonly
from .transaction import commit, contained_file, digest, preview
from .versions import stable_version

_PROJECT = re.compile(r'^(?:\.|[A-Za-z0-9_-]+)$')
_MAVEN = re.compile(r'^[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+$')
_GO = re.compile(r'^[A-Za-z0-9][A-Za-z0-9._~+/-]*$')
_PYTHON = re.compile(r'^[A-Za-z0-9][A-Za-z0-9._-]*$')


def prepare_dependency(root, kind, project, dependency, target, *, expected=None, write=False,
                       runner=run_readonly, require_clean=_clean_target, fetch=None):
    if not _PROJECT.fullmatch(project or '') or not target or not re.fullmatch(r'[A-Za-z0-9.+_-]+', target):
        raise ValueError('Invalid project or target version')
    prefix = Path() if project == '.' else Path(project)
    if kind == 'maven':
        if project not in ('authorization-server', 'rage-quit') or not _MAVEN.fullmatch(dependency):
            raise ValueError('Maven project or coordinate has no update policy')
        paths = [prefix / 'pom.xml']
    elif kind == 'python':
        if not _PYTHON.fullmatch(dependency):
            raise ValueError('Invalid Python package name')
        paths = [prefix / 'pyproject.toml', prefix / 'uv.lock']
    elif kind == 'go':
        if not _GO.fullmatch(dependency) or '..' in dependency or stable_version(target) is None:
            raise ValueError('Invalid Go module or version')
        paths = [prefix / 'go.mod', prefix / 'go.sum']
    else:
        raise ValueError('Unsupported dependency ecosystem')
    inputs = {}
    for relative in paths:
        name = str(relative)
        require_clean(root, name)
        inputs[name] = contained_file(root, relative).read_bytes()
    source = inputs[str(paths[0])]
    if kind == 'maven':
        doc = ET.fromstring(source)
        ns = {'m': doc.tag[1:].split('}')[0]} if doc.tag.startswith('{') else {}
        p = 'm:' if ns else ''
        sections = ('dependencies', 'dependencyManagement/dependencies')
        entries = []
        for section in sections:
            query = '/'.join(p + part for part in section.split('/')) + '/' + p + 'dependency'
            for entry in doc.findall(query, ns):
                name = entry.findtext(p + 'groupId', default='', namespaces=ns) + ':' + entry.findtext(p + 'artifactId', default='', namespaces=ns)
                if name == dependency:
                    entries.append(entry.findtext(p + 'version', default='', namespaces=ns))
        if len(entries) != 1 or not entries[0] or '${' in entries[0]:
            raise ValueError('Only one directly versioned Maven dependency can be prepared')
        candidate = Component(dependency, 'maven-dependency', str(paths[0]), declared=entries[0])
        check_maven(candidate, {}, **({'fetch': fetch} if fetch else {}))
        if candidate.status != 'update-available' or candidate.candidate != target:
            raise ValueError('Target is not the verified latest comparable Maven release')
    elif kind == 'python':
        manifest = tomllib.loads(source.decode('utf-8'))
        reqs = manifest.get('project', {}).get('dependencies', [])
        selected = [Requirement(req) for req in reqs if isinstance(req, str) and Requirement(req).name.lower().replace('_', '-') == dependency.lower().replace('_', '-')]
        if len(selected) != 1 or selected[0].url:
            raise ValueError('Only one direct Python project dependency can be prepared')
        candidate = Component(dependency, 'python-dependency', str(paths[0]), declared=str(selected[0]))
        check_python(candidate, {}, **({'fetch': fetch} if fetch else {}))
        if candidate.candidate != target:
            raise ValueError('Target is not the verified latest stable Python release')
    else:
        lines = source.decode('utf-8').splitlines()
        entries = []
        for line in lines:
            fields = line.split('//', 1)[0].split()
            if fields and fields[0] == 'require':
                fields = fields[1:]
            if len(fields) == 2 and fields[0] == dependency:
                entries.append(fields[1])
        if len(entries) != 1:
            raise ValueError('Only one explicitly required Go dependency can be prepared')
        candidate = Component(dependency, 'go-dependency', str(paths[0]), declared=entries[0])
        check_go(candidate, {}, **({'fetch': fetch} if fetch else {}))
        if candidate.status != 'update-available' or candidate.candidate != target:
            raise ValueError('Target is not the verified latest stable Go release')
    with isolated_workspace(root, paths) as work, tempfile.TemporaryDirectory(prefix='calcifer-dep-cache-') as cache:
        before = _snapshot(work)
        cwd = work / prefix
        if kind == 'maven':
            argv = ['mvn', '-B', f'-Dmaven.repo.local={cache}', 'org.codehaus.mojo:versions-maven-plugin:2.18.0:use-dep-version',
                    f'-Dincludes={dependency}', f'-DdepVersion={target}', '-DgenerateBackupPoms=false']
            validate = ['mvn', '-B', f'-Dmaven.repo.local={cache}', 'validate']
        elif kind == 'python':
            argv = ['uv', 'add', '--upgrade-package', dependency, f'{dependency}=={target}']
            validate = ['uv', 'lock', '--check']
        else:
            argv = ['go', 'get', f'{dependency}@{target}']
            validate = ['go', 'list', '-mod=readonly', '-m', 'all']
        result = runner(argv, cwd=cwd, timeout=120)
        if result.status != 'ok':
            raise ValueError(f'Native dependency update failed: {result.status}')
        after = _snapshot(work)
        changed = {name for name in set(before) | set(after) if before.get(name) != after.get(name)}
        allowed = {str(path) for path in paths}
        if not changed or changed - allowed:
            raise ValueError('Native tool changed unexpected files or made no changes')
        result = runner(validate, cwd=cwd, timeout=120)
        if result.status != 'ok':
            raise ValueError(f'Dependency validation failed: {result.status}')
        final = _snapshot(work)
        if final != after:
            raise ValueError('Dependency validation unexpectedly modified files')
        replacements = {name: (work / name).read_bytes() for name in changed}
        manifest_bytes = (work / paths[0]).read_bytes()
        if kind == 'maven':
            updated = ET.fromstring(manifest_bytes)
            ns = {'m': updated.tag[1:].split('}')[0]} if updated.tag.startswith('{') else {}
            p = 'm:' if ns else ''
            found = []
            for section in ('dependencies', 'dependencyManagement/dependencies'):
                query = '/'.join(p + part for part in section.split('/')) + '/' + p + 'dependency'
                for entry in updated.findall(query, ns):
                    name = entry.findtext(p + 'groupId', default='', namespaces=ns) + ':' + entry.findtext(p + 'artifactId', default='', namespaces=ns)
                    if name == dependency:
                        found.append(entry.findtext(p + 'version', default='', namespaces=ns))
            if found != [target]:
                raise ValueError('Maven did not select the requested direct dependency version')
        elif kind == 'python':
            updated = tomllib.loads(manifest_bytes.decode('utf-8'))
            requirements = updated.get('project', {}).get('dependencies', [])
            found = [Requirement(req) for req in requirements if isinstance(req, str) and Requirement(req).name.lower().replace('_', '-') == dependency.lower().replace('_', '-')]
            if len(found) != 1 or str(found[0].specifier) != '==' + target:
                raise ValueError('Python manifest does not contain the requested exact dependency')
            lock_data = tomllib.loads((work / paths[1]).read_text(encoding='utf-8'))
            if not any(str(x.get('name', '')).lower().replace('_', '-') == dependency.lower().replace('_', '-') and str(x.get('version')) == target for x in lock_data.get('package', []) if isinstance(x, dict)):
                raise ValueError('Python lockfile does not select the requested dependency')
        else:
            lines = manifest_bytes.decode('utf-8').splitlines()
            selected = []
            for line in lines:
                fields = line.split('//', 1)[0].split()
                if fields and fields[0] == 'require':
                    fields = fields[1:]
                if len(fields) == 2 and fields[0] == dependency:
                    selected.append(fields[1])
            if selected != [target]:
                raise ValueError('Go module file does not select the requested version')
    for name, content in inputs.items():
        if contained_file(root, Path(name)).read_bytes() != content:
            raise ValueError('Dependency input changed during preparation')
    changes, diff = preview(root, replacements, {str(path) for path in paths})
    identity = '\0'.join([kind, project, dependency, target] + [name + ':' + digest(content) for name, content in sorted(inputs.items())] + [change.path + ':' + digest(change.replacement) for change in changes]).encode()
    fingerprint = hashlib.sha256(identity).hexdigest()
    if write:
        if fingerprint != expected:
            raise ValueError('Preview fingerprint missing or stale')
        for name, content in inputs.items():
            require_clean(root, name)
            if contained_file(root, Path(name)).read_bytes() != content:
                raise ValueError('Dependency input changed since preview')
        commit(root, changes, {str(path) for path in paths})
    return diff, fingerprint
