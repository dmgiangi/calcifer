"""Command line interface for the update module."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import sys
import yaml
from pathlib import Path

from .model import Component, Report
from .inventory import ROOT, discover
from .live import collect
from .platform import components as platform_components
from .scope import cluster_scopes
from .prepare import prepare_chart
from .image_prepare import prepare_image, _GROUPS
from .tools import run_readonly
from .action_prepare import prepare_action
from .rust_prepare import prepare_rust
from .dependency_prepare import prepare_dependency
from .checks import check_all
from .catalog import annotate
from .helm import render_chart_images
from .maven_effective import effective_versions
from .go_effective import selected_modules
from .progress import ProgressDisplay


def parser() -> argparse.ArgumentParser:
    cli = argparse.ArgumentParser(prog="calcifer-update", description="Inspect and prepare repository updates")
    sub = cli.add_subparsers(dest="command", required=True)
    for name in ("status", "check"):
        command = sub.add_parser(name, help=f"{name.title()} components")
        command.add_argument("--cluster", choices=("home", "cloud", "all"), default="all")
        command.add_argument("--json", action="store_true", help="Output machine-readable JSON")
        command.add_argument("--live", action="store_true", help="Read live state using explicitly selected contexts")
        command.add_argument("--kubeconfig", type=Path, help="Explicit kubeconfig path for live checks")
        command.add_argument("--context-home", help="Kubernetes context for home")
        command.add_argument("--context-cloud", help="Kubernetes context for cloud")
        if name == "check":
            command.add_argument("--offline", action="store_true", help="Do not query external sources")
    apply = sub.add_parser("apply", help="Preview or prepare a selected update")
    apply.add_argument("component")
    apply.add_argument("--version", required=True)
    apply.add_argument("--cluster", choices=("home", "cloud", "all"), default="all")
    apply.add_argument("--write", action="store_true", help="Write validated changes after preview")
    apply.add_argument("--expect", help="Fingerprint from the previously reviewed preview")
    apply.add_argument("--platform", help="Image platform, e.g. linux/amd64")
    apply.add_argument("--json", action="store_true", help="Output machine-readable JSON")
    return cli


def _parallel_phase(label, items, worker, workers, progress):
    """Return results in input order while reporting actual completion order."""
    if progress:
        progress(f"{label}: 0/{len(items)}")
    results = [None] * len(items)
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(worker, item): index for index, item in enumerate(items)}
        for completed, future in enumerate(as_completed(futures), start=1):
            results[futures[future]] = future.result()
            if progress:
                progress(f"{label}: {completed}/{len(items)}")
    return results


def run(args: argparse.Namespace, progress=None) -> Report:
    if args.command in ("status", "check"):
        if args.command == "check" and args.offline and args.live:
            return Report(args.command, errors=["--offline cannot be combined with --live."])
        if progress:
            progress("Lettura dell'inventario e delle configurazioni dei cluster")
        components, errors = discover()
        scopes, scope_errors = cluster_scopes(ROOT)
        errors.extend(scope_errors)
        for item in components:
            if item.source.startswith("clusters/"):
                item.clusters = scopes.get(item.source, [])
                if not item.clusters:
                    item.detail += "; cluster reachability unresolved"
        annotate(components)
        components.extend(platform_components(ROOT))
        if args.cluster != "all":
            # Include unresolved scope: excluding it would falsely imply coverage.
            components = [item for item in components if not item.clusters or args.cluster in item.clusters]
        if args.live:
            if progress:
                progress("Lettura dello stato dei cluster selezionati")
            selected = ("home", "cloud") if args.cluster == "all" else (args.cluster,)
            if not args.kubeconfig or any(not getattr(args, f"context_{cluster}") for cluster in selected):
                errors.append("Live checks require --kubeconfig and an explicit context for every selected cluster.")
            else:
                root = ROOT
                revision = run_readonly(["git", "rev-parse", "HEAD"], cwd=root)
                desired = revision.stdout.strip() if revision.status == "ok" else None
                for cluster in selected:
                    observations = collect(cluster, getattr(args, f"context_{cluster}"), args.kubeconfig,
                                           desired_revision=desired)
                    components.extend(observations)
                    nodes = [o for o in observations if o.kind == "nodes"]
                    charts = [o for o in observations if o.kind == "k3s-helmcharts"]
                    if len(nodes) == 1 and nodes[0].metadata.get("platform"):
                        for image_item in components:
                            if image_item.kind in ("image", "docker-base", "helm-chart") and image_item.clusters == [cluster]:
                                image_item.metadata["platform"] = nodes[0].metadata["platform"]
                    for platform_item in components:
                        if platform_item.kind == "k3s-component" and cluster in platform_item.clusters:
                            match = next((o for o in charts if o.name == platform_item.name), None)
                            if match:
                                platform_item.observed = match.observed
                                platform_item.detail = "Bundled component version observed from K3s HelmChart; review the K3s release"

                        if platform_item.kind == "k3s" and platform_item.clusters == [cluster] and len(nodes) == 1:
                            platform_item.observed = nodes[0].observed
        charts = [item for item in components if item.kind == "helm-chart"]
        if args.command == "check" and not args.offline:
            root = ROOT
            maven_items = [item for item in components if item.kind in ("maven-dependency", "maven-plugin", "maven-managed")
                           and (not item.declared or "${" in item.declared)]
            pom_paths = sorted({item.source for item in maven_items})
            resolved = _parallel_phase("Risoluzione Maven", pom_paths,
                                       lambda path: effective_versions(root, path), 4, progress)
            effective = dict(zip(pom_paths, resolved))
            for item in maven_items:
                versions, resolution_error = effective[item.source]
                if item.name in versions:
                    item.metadata["effective_version"] = versions[item.name]
                elif resolution_error:
                    item.metadata["resolution_error"] = resolution_error
            go_items = [item for item in components if item.kind == "go-dependency"]
            go_paths = sorted({item.source for item in go_items})
            go_results = _parallel_phase("Risoluzione Go", go_paths,
                                         lambda path: selected_modules(root, path), 4, progress)
            go_graphs = dict(zip(go_paths, go_results))
            for item in go_items:
                item.metadata["graph_checked"] = "true"
                versions, graph_error = go_graphs[item.source]
                version, replaced = versions.get(item.name, (None, False))
                if version:
                    item.metadata["effective_version"] = str(version)
                if replaced:
                    item.metadata["replaced"] = "true"
                if graph_error:
                    item.metadata["resolution_error"] = graph_error
            if progress:
                progress("Verifica delle versioni dichiarate")
            check_all(components, progress=progress)
            repos = [item for item in components if item.kind == "helm-repository"]
            root = ROOT
            rendered = _parallel_phase("Rendering dei chart Helm", charts,
                                       lambda chart: render_chart_images(chart, repos, root), 8, progress)
            derived = []
            render_failures = {}
            for chart, (images, render_error) in zip(charts, rendered):
                if render_error:
                    chart.detail += "; " + render_error
                    render_failures[render_error] = render_failures.get(render_error, 0) + 1
                else:
                    for image in images:
                        derived.append(Component(image, "image", chart.source + "#rendered:" + chart.name,
                                                 declared=image, clusters=list(chart.clusters), status="unknown",
                                                 detail="Image rendered from Helm chart",
                                                 metadata={"owner": chart.metadata.get("owner", ""), "strategy": "oci",
                                                           **({"platform": chart.metadata["platform"]} if chart.metadata.get("platform") else {})}))
            for reason, count in render_failures.items():
                errors.append(f"Unresolved content in {count} chart(s): {reason}")
            if progress:
                progress("Verifica delle immagini trovate nei chart")
            check_all(derived, progress=progress)
            components.extend(derived)
        elif charts:
            errors.append("Coverage incomplete: chart-rendered image dependencies require an online check.")
        if args.command == "check" and args.offline:
            if progress:
                progress("Modalità offline: controlli remoti saltati")
            errors.append("Offline mode: remote versions and native dependency resolution were not checked.")
        unsupported = sum(item.status == "unsupported" for item in components)
        if unsupported:
            errors.append(f"Coverage incomplete: {unsupported} declarations are unsupported.")
        if args.command == "check" and any(item.status == "unavailable" for item in components):
            errors.append("Some update sources or tools were unavailable.")
        if progress:
            progress(f"Verifica terminata: {len(components)} componenti, {len(errors)} segnalazioni")
        return Report(args.command, components, errors)
    if args.cluster == "all" and args.component not in _GROUPS and "@" not in args.component and not args.component.startswith(("rust:", "maven:", "python:", "go:")):
        return Report(args.command, errors=["Specify one cluster explicitly; no changes were made."])
    root = ROOT
    components, errors = discover()
    scopes, scope_errors = cluster_scopes(root)
    if errors or scope_errors:
        return Report(args.command, errors=["Inventory or cluster scope is incomplete; no changes were made."])
    for item in components:
        if item.source.startswith("clusters/"):
            item.clusters = scopes.get(item.source, [])
    try:
        if args.component == "k3s":
            raise ValueError("K3s is managed on each host; verify backups and follow the host upgrade procedure")
        if args.component == "flux":
            raise ValueError("Flux manifests are generated; use Flux tooling and review the generated changes")
        if args.component.startswith(("maven:", "python:", "go:")):
            if args.cluster != "all":
                raise ValueError("Source dependencies are repository-wide; use --cluster all")
            fields = args.component.split(":", 2)
            if len(fields) != 3:
                raise ValueError("Use <ecosystem>:<project>:<dependency>")
            diff, fingerprint = prepare_dependency(root, fields[0], fields[1], fields[2], args.version,
                                                   expected=args.expect, write=args.write)
            affected = []
        elif args.component.startswith("rust:"):
            if args.cluster != "all":
                raise ValueError("Firmware dependencies are not cluster scoped; use --cluster all")
            fields = args.component.split(":")
            if len(fields) != 3:
                raise ValueError("Use rust:<project>:<crate>")
            diff, fingerprint = prepare_rust(root, fields[1], fields[2], args.version,
                                             expected=args.expect, write=args.write)
            affected = []
        elif any(item.kind == "action" and item.declared == args.component for item in components):
            if args.cluster != "all":
                raise ValueError("Workflow actions are repository-wide; use --cluster all")
            diff, fingerprint = prepare_action(root, args.component, args.version, components,
                                               expected=args.expect, write=args.write)
            affected = []
        elif args.component in _GROUPS:
            diff, fingerprint, affected = prepare_image(root, args.component, args.version, args.cluster,
                                                       args.platform, expected=args.expect, write=args.write)
        else:
            diff, fingerprint = prepare_chart(root, args.component, args.version, args.cluster,
                                             components, expected=args.expect, write=args.write)
            affected = [args.cluster]
        return Report(args.command, diff=diff, fingerprint=fingerprint,
                      affected_clusters=affected, written=args.write)
    except RuntimeError as exc:
        return Report(args.command, errors=[str(exc)])
    except yaml.YAMLError:
        return Report(args.command, errors=["Invalid YAML; no changes were made."])
    except (OSError, ValueError) as exc:
        return Report(args.command, errors=[f"{exc}; no changes were made."])


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    progress = ProgressDisplay(sys.stderr) if args.command == "check" else None
    report = run(args, progress=progress)
    if args.json:
        print(json.dumps(report.to_dict(), indent=2))
    else:
        for component in report.components:
            fields = [component.kind, component.name, component.status, component.source,
                      f"clusters={','.join(component.clusters) or 'unresolved'}"]
            for label in ("declared", "observed", "candidate", "detail"):
                value = getattr(component, label)
                if value:
                    fields.append(f"{label}={value}")
            if component.metadata.get("locked_version"):
                fields.append(f"locked={component.metadata['locked_version']}")
            print("\t".join(fields))
        if report.diff:
            print(report.diff, end="")
            print(f"Affected clusters: {', '.join(report.affected_clusters) or 'repository-wide'}")
            print(f"Preview fingerprint: {report.fingerprint}")
            print("Local changes written." if report.written else "Preview only; no changes written.")
        for error in report.errors:
            print(f"Error: {error}", file=sys.stderr)
    return 1 if report.errors else 0


if __name__ == "__main__":
    sys.exit(main())
