"""Crates.io candidate discovery without assuming Cargo requirement semantics."""
import json
import re
from urllib.error import URLError
from urllib.request import Request, urlopen

from .errors import describe_source_error
from .model import Component
from .versions import stable_version

_CRATE = re.compile(r"^[A-Za-z0-9_-]+$")


def latest_crate(name: str) -> str:
    if not _CRATE.fullmatch(name):
        raise ValueError("Invalid crate name")
    request = Request(f"https://crates.io/api/v1/crates/{name}", headers={"User-Agent": "calcifer-update (operator tooling)", "Accept": "application/json"})
    try:
        with urlopen(request, timeout=15) as response:
            data = json.loads(response.read(2 * 1024 * 1024 + 1))
        return data["crate"]["max_stable_version"]
    except (URLError, OSError, ValueError, KeyError, TypeError):
        # The sparse index is a separate public endpoint and includes yanked
        # status; use it if the crates.io metadata API is unavailable.
        crate = name.lower()
        if len(crate) == 1:
            path = '1/' + crate
        elif len(crate) == 2:
            path = '2/' + crate
        elif len(crate) == 3:
            path = '3/' + crate[0] + '/' + crate
        else:
            path = crate[:2] + '/' + crate[2:4] + '/' + crate
        request = Request('https://index.crates.io/' + path, headers={'User-Agent': 'calcifer-update'})
        with urlopen(request, timeout=15) as response:
            content = response.read(8 * 1024 * 1024 + 1)
        if len(content) > 8 * 1024 * 1024:
            raise ValueError('crate index response too large')
        entries = [json.loads(line) for line in content.splitlines() if line.strip()]
        versions = [(stable_version(entry.get('vers', '')), entry.get('vers')) for entry in entries
                    if isinstance(entry, dict) and not entry.get('yanked', False)]
        versions = [(version, tag) for version, tag in versions if version is not None]
        if not versions:
            raise ValueError('No stable crate release')
        return max(versions)[1]


def check_rust(component: Component, cache: dict, fetch=latest_crate) -> None:
    if component.metadata.get("source_kind", "registry") != "registry":
        component.status = "unknown"
        component.detail = "Non-registry locked dependency; source must be checked separately"
        return
    try:
        if component.name not in cache:
            cache[component.name] = fetch(component.name)
        value = cache[component.name]
        latest = stable_version(value)
        if latest is None:
            component.status = "unknown"
            component.detail = "Crate has no comparable stable release"
            return
        component.candidate = value
        if component.kind == "rust-locked":
            if component.metadata.get("source_kind") != "registry":
                component.status = "unknown"
                component.detail = "Non-registry locked dependency; source must be checked separately"
                return
            current = stable_version(component.declared or "")
            if current is None:
                component.status = "unknown"
                component.detail = "Locked version is not a comparable stable release"
                return
            component.status = "update-available" if latest > current else "current" if latest == current else "ahead"
            component.detail = "Source: crates.io; dependency graph compatibility remains unverified"
            return
        locked = stable_version(component.metadata.get("locked_version", ""))
        if locked is not None:
            component.status = "update-available" if latest > locked else "current" if latest == locked else "ahead"
            component.detail = "Source: crates.io; locked version compared, compatibility remains unverified"
            return
        # Cargo declarations are ranges by default; never equate a range to a pin.
        component.status = "unknown"
        component.detail = "Source: crates.io; compare against Cargo.lock and Cargo compatibility before updating"
    except (URLError, OSError, ValueError, TypeError, KeyError) as exc:
        component.status = "unavailable"
        component.detail = "Crates.io lookup failed: " + describe_source_error(exc)
