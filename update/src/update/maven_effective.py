"""Resolve inherited Maven versions in a disposable workspace."""
from pathlib import Path
import tempfile
import xml.etree.ElementTree as ET

from .tools import isolated_workspace, run_readonly


def effective_versions(root, pom_path, runner=run_readonly):
    """Return uniquely resolved artifact versions without exposing Maven output."""
    relative = Path(pom_path)
    try:
        with isolated_workspace(root, [relative]) as workspace:
            output = workspace / 'effective-pom.xml'
            local_repo = workspace / 'maven-cache'
            pom = workspace / relative
            command = ['mvn', '-B', '-f', str(pom), f'-Dmaven.repo.local={local_repo}',
                       'help:effective-pom', f'-Doutput={output}']
            result = runner(command, cwd=workspace, timeout=120)
            if result.status != 'ok' or not output.is_file():
                return {}, f'Maven effective POM unavailable: {result.status}'
            doc = ET.parse(output).getroot()
            ns = {'m': doc.tag[1:].split('}')[0]} if doc.tag.startswith('{') else {}
            p = 'm:' if ns else ''
            values = {}
            for section in ('dependencies', 'dependencyManagement/dependencies', 'build/plugins', 'build/pluginManagement/plugins'):
                section_path = '/'.join(p + part for part in section.split('/'))
                child = 'plugin' if 'plugins' in section else 'dependency'
                for entry in doc.findall(f'{section_path}/{p}{child}', ns):
                    group = entry.findtext(f'{p}groupId', default='', namespaces=ns)
                    artifact = entry.findtext(f'{p}artifactId', default='', namespaces=ns)
                    version = entry.findtext(f'{p}version', default='', namespaces=ns)
                    if group and artifact and version:
                        values.setdefault(f'{group}:{artifact}', set()).add(version)
            # Conflicting effective versions cannot be assigned safely.
            return {key: next(iter(versions)) for key, versions in values.items() if len(versions) == 1}, None
    except (OSError, ValueError, ET.ParseError, TypeError):
        return {}, 'Cannot safely resolve the effective Maven POM'
