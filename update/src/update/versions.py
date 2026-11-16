"""Conservative version parsing used by individual update strategies."""
from dataclasses import dataclass
import re


@dataclass(frozen=True, order=True)
class StableVersion:
    major: int
    minor: int
    patch: int


_PATTERN = re.compile(
    r"^v?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$"
)


def stable_version(value: str) -> StableVersion | None:
    """Compare stable SemVer precedence; callers retain the original spelling.

    Build metadata has no precedence. Prereleases and malformed identifiers
    are deliberately rejected rather than coerced into stable versions.
    """
    match = _PATTERN.fullmatch(value)
    return StableVersion(*(int(part) for part in match.groups())) if match else None
