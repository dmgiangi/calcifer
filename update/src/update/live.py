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
            collected = []
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
                    if not version:
                        chart = (item.get('spec') or {}).get('chart', '')
                        match = re.search(r'/traefik-([0-9][0-9A-Za-z.+-]*)\.tgz$', chart) if isinstance(chart, str) else None
                        version = match[1] if match else None
                    conditions = status.get("conditions", [])
                if version is not None and not isinstance(version, str):
                    raise ValueError('invalid observed version')
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
                collected.append(Component(name, kind, "kubectl", clusters=[cluster], observed=version,
                                           status=state, detail=detail, metadata=metadata))
            observations.extend(collected)
        except (ValueError, TypeError, AttributeError):
            observations.append(Component(kind, "cluster-observation", "kubectl", clusters=[cluster], status="unavailable", detail="Invalid Kubernetes response"))
    return observations


def apply_observations(components, cluster, observations):
    """Retain per-node data; aggregate only complete and uniform observations."""
    nodes = [item for item in observations if item.kind == 'nodes']
    versions = {item.name: item.observed for item in nodes}
    unavailable = any(item.kind == 'cluster-observation' and item.name == 'nodes'
                      and item.status == 'unavailable' for item in observations)
    state = ('unavailable' if unavailable else 'incomplete' if not nodes or
             len(versions) != len(nodes) or not all(versions.values()) else
             'uniform' if len(set(versions.values())) == 1 else 'mixed')
    platforms = {item.metadata.get('platform') for item in nodes}
    for item in components:
        if item.clusters != [cluster]:
            continue
        if item.kind == 'k3s':
            item.metadata['node_versions'] = json.dumps(versions, sort_keys=True)
            item.metadata['node_state'] = state
            item.observed = next(iter(versions.values())) if state == 'uniform' else None
            if state == 'mixed':
                item.status = 'divergent'
                item.detail = 'Host-managed; nodes report different K3s versions'
            elif state == 'unavailable':
                item.status = 'unavailable'
                item.detail = 'Node observations unavailable; K3s version is not established'
            elif state == 'incomplete':
                item.status = 'unknown'
                item.detail = 'Incomplete node observations; K3s version is not established'
        elif item.kind in ('image', 'docker-base', 'helm-chart') and len(platforms) == 1 and None not in platforms:
            item.metadata['platform'] = next(iter(platforms))
        elif item.kind == 'k3s-component':
            charts = [entry for entry in observations if entry.kind == 'k3s-helmcharts' and entry.name == item.name]
            if len(charts) == 1:
                item.observed = charts[0].observed
                item.metadata['observed_basis'] = 'HelmChart version, not Traefik application version'
