"""Release checks for GitHub-hosted workflow actions."""
import json
import os
import re
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from .errors import describe_source_error
from .cache import cached
from .model import Component
from .versions import stable_version

_ACTION = re.compile(r"^([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(?:/[^@\s]+)?@([^@\s]+)$")


def latest_release(owner: str, repo: str) -> str:
    url = f"https://api.github.com/repos/{owner}/{repo}/releases/latest"
    request = Request(url, headers={"Accept": "application/vnd.github+json", "User-Agent": "calcifer-update"})
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token:
        request.add_unredirected_header("Authorization", f"Bearer {token}")
    with urlopen(request, timeout=15) as response:
        data = json.loads(response.read(1024 * 1024 + 1))
    if not isinstance(data, dict) or data.get("draft") or data.get("prerelease") or not isinstance(data.get("tag_name"), str):
        raise ValueError("No stable release metadata")
    return data["tag_name"]


def resolve_ref(owner: str, repo: str, ref: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", ref):
        raise ValueError("Invalid action ref")
    request = Request(f"https://api.github.com/repos/{owner}/{repo}/commits/{ref}",
                      headers={"Accept": "application/vnd.github+json", "User-Agent": "calcifer-update"})
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token:
        request.add_unredirected_header("Authorization", f"Bearer {token}")
    with urlopen(request, timeout=15) as response:
        data = json.loads(response.read(1024 * 1024 + 1))
    sha = data.get("sha")
    if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Invalid GitHub commit response")
    return sha


def check_action(component: Component, cache: dict, fetch=latest_release, resolve=None) -> None:
    match = _ACTION.fullmatch(component.declared or "")
    if not match:
        component.status = "unknown"
        component.detail = "Local, docker or unrecognized action reference; manual review required"
        return
    owner, repo, ref = match.groups()
    current = stable_version(ref)
    major_match = re.fullmatch(r"v?(0|[1-9][0-9]*)", ref)
    if current is None and major_match is None:
        component.status = "unknown"
        component.detail = "Mutable or digest-pinned reference: cannot compare a precise release safely"
        return
    key = (owner.lower(), repo.lower())
    def lookup():
        try:
            return fetch(owner, repo), None
        except (HTTPError, URLError, TimeoutError, OSError, ValueError, UnicodeError) as exc:
            return None, "GitHub release lookup failed: " + describe_source_error(exc)
    tag, error = cached(cache, key, lookup)
    if error:
        component.status = "unavailable"
        component.detail = error
        return
    candidate = stable_version(tag)
    if candidate is None:
        component.status = "unknown"
        component.detail = "Latest release tag is not a stable three-part version"
        return
    component.candidate = tag
    if major_match:
        if candidate.major > int(major_match.group(1)):
            component.status = "update-available"
        else:
            if resolve is not None and candidate.major == int(major_match.group(1)):
                try:
                    major_key, release_key = ("ref", owner, repo, ref), ("ref", owner, repo, tag)
                    major_sha = cached(cache, major_key, lambda: resolve(owner, repo, ref))
                    release_sha = cached(cache, release_key, lambda: resolve(owner, repo, tag))
                    if major_sha == release_sha:
                        component.status = "current"
                        component.metadata["resolved_release"] = tag
                        component.detail = "Mutable major tag currently resolves to the latest release commit"
                        return
                except (OSError, ValueError, TypeError):
                    pass
            component.status = "unknown"
            component.detail = "Mutable major tag: exact release cannot be confirmed"
            return
    else:
        component.status = "update-available" if candidate > current else "current" if candidate == current else "ahead"
    component.detail = f"Source: github.com/{owner}/{repo}/releases/latest"
