"""Optional read-only Kubernetes observations using an explicit context."""
import json
import re
from pathlib import Path

from .model import Component
from .tools import run_readonly


def collect(cluster: str, context: str, kubeconfig: Path, runner=run_readonly, desired_revision=None) -> list[Component]:
    observations = []
    queries = (
        ("nodes", ["get", "nodes", "-o", "json"]),
        ("flux-kustomizations", ["-n", "flux-system", "get", "kustomizations.kustomize.toolkit.fluxcd.io", "-o", "json"]),
        ("k3s-helmcharts", ["-n", "kube-system", "get", "helmcharts.helm.cattle.io", "-o", "json"]),
    )
    for kind, query in queries:
        result = runner(["kubectl", "--kubeconfig", str(kubeconfig), "--context", context, *query], cwd=Path.cwd())
        if result.status != "ok":
            observations.append(Component(kind, "cluster-observation", "kubectl", clusters=[cluster], status="unavailable", detail=f"{kind} query: {result.status}"))
            continue
        try:
            data = json.loads(result.stdout)
            if not isinstance(data, dict) or not isinstance(data.get("items"), list):
                raise ValueError("invalid list")
            for item in data["items"]:
                if not isinstance(item, dict):
                    raise ValueError("invalid item")
                name = item.get("metadata", {}).get("name")
                if not isinstance(name, str):
                    raise ValueError("invalid name")
                status = item.get("status", {})
                if kind == "nodes":
                    version = status.get("nodeInfo", {}).get("kubeletVersion")
                    conditions = status.get("conditions", [])
                elif kind == "flux-kustomizations":
                    version = status.get("lastAppliedRevision")
                    conditions = status.get("conditions", [])
                else:
                    version = (item.get("spec") or {}).get("version")
                    conditions = status.get("conditions", [])
                ready = next((c.get("status") for c in conditions if c.get("type") == "Ready"), None)
                state = "healthy" if ready == "True" else "not-ready" if ready == "False" else "unknown"
                detail = "Live observation; comparison with declared state unavailable"
                if kind == "flux-kustomizations" and version and desired_revision:
                    match = re.search(r"@sha1:([0-9a-f]{40})$", version)
                    if match and re.fullmatch(r"[0-9a-f]{40}", desired_revision):
                        if state == "healthy" and match.group(1) != desired_revision:
                            state = "divergent"
                        detail = "Compared with local Git HEAD; uncommitted changes are not included"
                metadata = {}
                if kind == "nodes":
                    node_info = status.get("nodeInfo", {})
                    os_name, architecture = node_info.get("operatingSystem"), node_info.get("architecture")
                    if os_name in ("linux", "windows") and architecture in ("amd64", "arm64"):
                        metadata["platform"] = f"{os_name}/{architecture}"
                observations.append(Component(name, kind, "kubectl", clusters=[cluster], observed=version,
                                              status=state, detail=detail, metadata=metadata))
        except (ValueError, TypeError, AttributeError):
            observations.append(Component(kind, "cluster-observation", "kubectl", clusters=[cluster], status="unavailable", detail="Invalid Kubernetes response"))
    return observations
