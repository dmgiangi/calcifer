"""Serializable results shared by update strategies."""
from dataclasses import asdict, dataclass, field


@dataclass
class Component:
    name: str
    kind: str
    source: str
    declared: str | None = None
    clusters: list[str] = field(default_factory=list)
    observed: str | None = None
    candidate: str | None = None
    status: str = "unknown"
    detail: str = ""
    metadata: dict[str, str] = field(default_factory=dict)


@dataclass
class Report:
    command: str
    components: list[Component] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    diff: str = ""
    fingerprint: str = ""
    affected_clusters: list[str] = field(default_factory=list)
    written: bool = False

    def to_dict(self) -> dict:
        return asdict(self)
