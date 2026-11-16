"""Resolve inherited Maven versions in a disposable workspace."""
from pathlib import Path
import re
import tempfile
import xml.etree.ElementTree as ET

from .tools import isolated_workspace, run_readonly


def effective_model(root, pom_path, runner=run_readonly):
    """Return unique effective versions and their declaring Maven coordinates."""
    relative = Path(pom_path)
    try:
        with isolated_workspace(root, [relative]) as workspace:
            output = workspace / 'effective-pom.xml'
            local_repo = workspace / 'maven-cache'
            pom = workspace / relative
            command = ['mvn', '-B', '-f', str(pom), f'-Dmaven.repo.local={local_repo}',
                       'help:effective-pom', '-Dverbose', f'-Doutput={output}']
            result = runner(command, cwd=workspace, timeout=120)
            if result.status != 'ok' or not output.is_file():
                return {}, {}, f'Maven effective POM unavailable: {result.status}'
            parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
            doc = ET.parse(output, parser=parser).getroot()
            ns = {'m': doc.tag[1:].split('}')[0]} if doc.tag.startswith('{') else {}
            p = 'm:' if ns else ''
            values, sources = {}, {}
            for section in ('dependencies', 'dependencyManagement/dependencies', 'build/plugins', 'build/pluginManagement/plugins'):
                section_path = '/'.join(p + part for part in section.split('/'))
                child = 'plugin' if 'plugins' in section else 'dependency'
                for entry in doc.findall(f'{section_path}/{p}{child}', ns):
                    group = entry.findtext(f'{p}groupId', default='', namespaces=ns)
                    artifact = entry.findtext(f'{p}artifactId', default='', namespaces=ns)
                    version_element = entry.find(f'{p}version', ns)
                    version = version_element.text if version_element is not None else ''
                    if group and artifact and version:
                        coordinate = f'{group}:{artifact}'
                        values.setdefault(coordinate, set()).add(version)
                        source = _version_source(entry, version_element)
                        if source:
                            sources.setdefault(coordinate, set()).add(source)
            # Conflicting effective versions cannot be assigned safely.
            versions = {key: next(iter(items)) for key, items in values.items() if len(items) == 1}
            origins = {key: next(iter(items)) for key, items in sources.items()
                       if key in versions and len(items) == 1}
            return versions, origins, None
    except (OSError, ValueError, ET.ParseError, TypeError):
        return {}, {}, 'Cannot safely resolve the effective Maven POM'


def _version_source(entry, version_element):
    children = list(entry)
    try:
        index = children.index(version_element)
    except ValueError:
        return None
    for child in children[index + 1:]:
        if child.tag is ET.Comment:
            parts = (child.text or '').strip().split(',', 1)[0].split(':')
            if len(parts) >= 3 and all(re.fullmatch(r'[A-Za-z0-9_.-]+', part) for part in parts[:2]):
                return ':'.join(parts[:2])
            return None
        if isinstance(child.tag, str):
            break
    return None


def effective_versions(root, pom_path, runner=run_readonly):
    """Backward-compatible version-only view of the effective Maven model."""
    versions, _, error = effective_model(root, pom_path, runner)
    return versions, error
