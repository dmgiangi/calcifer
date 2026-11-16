"""Maven Central release discovery for explicit, comparable coordinates."""
from urllib.error import URLError
from urllib.request import Request, urlopen
import re
import xml.etree.ElementTree as ET
from .errors import describe_source_error
from .cache import cached


_PART = re.compile(r'^[A-Za-z0-9_.-]+$')
_NUMERIC_VERSION = re.compile(r'^[0-9]+(?:\.[0-9]+){1,5}$')


def numeric_version(value):
    if not _NUMERIC_VERSION.fullmatch(value):
        return None
    parts = [int(part) for part in value.split('.')]
    while len(parts) > 2 and parts[-1] == 0:
        parts.pop()
    return tuple(parts)


def metadata(group, artifact):
    if not _PART.fullmatch(group) or not _PART.fullmatch(artifact) or '..' in group:
        raise ValueError('Invalid Maven coordinates')
    path = group.replace('.', '/') + '/' + artifact + '/maven-metadata.xml'
    request = Request('https://repo.maven.apache.org/maven2/' + path, headers={'User-Agent': 'calcifer-update'})
    with urlopen(request, timeout=15) as response:
        data = response.read(2 * 1024 * 1024 + 1)
    if len(data) > 2 * 1024 * 1024:
        raise ValueError('Metadata too large')
    root = ET.fromstring(data)
    return [node.text for node in root.findall('./versioning/versions/version') if node.text]


def check_maven(item, cache, fetch=metadata):
    current = numeric_version(item.metadata.get('effective_version') or item.declared or '')
    parts = item.name.split(':')
    if len(parts) != 2 or not all(_PART.fullmatch(part) for part in parts):
        item.status = 'unknown'
        item.detail = 'Unresolved Maven coordinates'
        return
    if current is None:
        item.status = 'unknown'
        item.detail = item.metadata.get('resolution_error') or 'Managed, property-based or non-comparable Maven version; resolve the effective POM'
        return
    key = tuple(parts)
    try:
        versions = [(numeric_version(v), v) for v in cached(cache, key, lambda: fetch(*key))]
        versions = [(v, text) for v, text in versions if v is not None]
        if not versions:
            item.status = 'unknown'
            item.detail = 'No comparable stable Maven Central versions'
            return
        latest, tag = max(versions)
        item.candidate = tag
        item.status = 'update-available' if latest > current else 'current' if latest == current else 'ahead'
        item.detail = 'Maven Central only; parent and BOM compatibility require review'
        if item.metadata.get('effective_version'):
            item.detail += '; current version resolved from the effective POM'
    except (URLError, OSError, ValueError, ET.ParseError) as exc:
        item.status = 'unavailable'
        item.detail = 'Maven Central lookup failed: ' + describe_source_error(exc)
