"""PyPI discovery for explicit Python requirement declarations."""
import json
from urllib.error import URLError
from urllib.request import Request, urlopen

from packaging.requirements import InvalidRequirement, Requirement
from packaging.version import InvalidVersion, Version

from .model import Component
from .policies import allows, no_candidate


def releases(name: str) -> list[str]:
    request = Request(f"https://pypi.org/pypi/{name}/json", headers={"User-Agent": "calcifer-update"})
    with urlopen(request, timeout=15) as response:
        data = json.loads(response.read(4 * 1024 * 1024 + 1))
    return [version for version, files in data["releases"].items()
            if files and any(not f.get("yanked", False) for f in files)]


def check_python(component: Component, cache: dict, fetch=releases) -> None:
    try:
        requirement = Requirement(component.declared or "")
    except InvalidRequirement:
        component.detail = "Unrecognized Python requirement"
        return
    if requirement.url:
        component.detail = "Direct URL requirement cannot be compared with PyPI releases"
        return
    name = requirement.name
    if component.metadata.get("source_kind") == "other":
        component.status = "unknown"
        component.detail = "Dependency uses a non-PyPI source; public lookup skipped"
        return
    try:
        locked = component.metadata.get("locked_version")
        pins = [locked] if locked else [s.version for s in requirement.specifier if s.operator in ("==", "===") and "*" not in s.version]
        try:
            current = Version(pins[0]) if len(pins) == 1 else None
        except InvalidVersion:
            current = None
        if name not in cache:
            cache[name] = fetch(name)
        versions = []
        for release in cache[name]:
            try:
                parsed = Version(release)
                if not parsed.is_prerelease and not parsed.is_devrelease and allows(component, parsed, current):
                    versions.append(parsed)
            except InvalidVersion:
                continue
        if not versions:
            no_candidate(component)
            return
        latest = max(versions)
        component.candidate = str(latest)
        if current is not None:
            component.status = "update-available" if latest > current else "current" if latest == current else "ahead"
            component.detail = "Source: PyPI; locked version needs review before updating" if locked else "Source: PyPI; pin needs review before updating"
            return
        component.status = "unknown"
        component.detail = "Requirement is a range; installed version is unknown"
    except (URLError, TimeoutError, OSError, ValueError, KeyError, TypeError):
        component.status = "unavailable"
        component.detail = "PyPI lookup failed"
