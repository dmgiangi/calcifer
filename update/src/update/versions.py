"""Conservative version parsing used by individual update strategies."""
from dataclasses import dataclass
import re


@dataclass(frozen=True, order=True)
class StableVersion:
    major: int
    minor: int
    patch: int


_PATTERN = re.compile(r"^v?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")


def stable_version(value: str) -> StableVersion | None:
    match = _PATTERN.fullmatch(value)
    return StableVersion(*(int(part) for part in match.groups())) if match else None
