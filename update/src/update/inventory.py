"""Conservative repository inventory; uncovered entries are never called current."""
import json
import os
from pathlib import Path
import re
import tomllib
import xml.etree.ElementTree as ET
from urllib.parse import urlsplit

from packaging.requirements import InvalidRequirement, Requirement
from packaging.utils import canonicalize_name

import yaml

from .model import Component

ROOT = Path(__file__).resolve().parents[3]
FROM = re.compile(r"^\s*FROM\s+(?:--platform=\S+\s+)?(\S+)", re.I)
ACTION = re.compile(r"^\s*-?\s*uses:\s*([^\s#]+)")
class ManifestLoader(getattr(yaml, "CSafeLoader", yaml.SafeLoader)):
    """Ignore application-specific YAML tags such as Home Assistant secrets."""


def _unknown_tag(loader, tag, node):
    # Tagged application values do not declare Kubernetes resources; discard
    # their contents so secret references never enter reports.
    return None


ManifestLoader.add_multi_constructor("!", _unknown_tag)

EXCLUDE = {".git", ".venv", "tmp", "target", "node_modules", "__pycache__", ".idea", ".codex", ".agents", ".aws", ".embuild"}


def _files(root: Path):
    for directory, directories, names in os.walk(root, followlinks=False):
        directories[:] = sorted(name for name in directories if name not in EXCLUDE and not name.startswith(".cache"))
        for name in sorted(names):
            path = Path(directory, name)
            if not path.is_symlink():
                yield path


def _clusters(path: Path) -> list[str]:
    if "calcifer-home" in path.parts:
        return ["home"]
    if "calcifer-cloud" in path.parts:
        return ["cloud"]
    return []


def discover(root: Path = ROOT) -> tuple[list[Component], list[str]]:
    components: list[Component] = []
    errors: list[str] = []

    def add(name, kind, path, declared=None, metadata=None):
        relative = path.relative_to(root)
        components.append(Component(name=str(name), kind=kind, source=str(relative),
                                    declared=str(declared) if declared is not None else None,
                                    clusters=_clusters(relative), status="unsupported",
                                    detail="Strategy and ownership not yet resolved", metadata=metadata or {}))

    def images(value, path):
        if isinstance(value, dict):
            # Secret contents are never inspected or emitted.
            if value.get("kind") in ("Secret", "SealedSecret"):
                return
            for key, item in value.items():
                if key == "image" and isinstance(item, str):
                    add(item, "image", path, item)
                elif key == "image" and isinstance(item, dict):
                    repository = item.get("repository") or item.get("name")
                    if isinstance(repository, str):
                        registry = item.get("registry")
                        image = f"{registry}/{repository}" if isinstance(registry, str) and registry else repository
                        tag = item.get("tag")
                        digest = item.get("digest")
                        reference = image + (f":{tag}" if isinstance(tag, (str, int)) and tag else "")
                        reference += f"@{digest}" if isinstance(digest, str) and digest else ""
                        add(reference, "image", path, reference)
                    else:
                        add("image settings", "unresolved-image", path)
                elif key == "images" and isinstance(item, list):
                    for image in item:
                        if isinstance(image, dict) and image.get("name"):
                            name = image.get("newName") or image["name"]
                            tag = image.get("newTag")
                            digest = image.get("digest")
                            reference = str(name) + (f":{tag}" if tag else "") + (f"@{digest}" if digest else "")
                            add(reference, "image", path, reference)
                        else:
                            images(image, path)
                elif isinstance(item, (dict, list)):
                    images(item, path)
        elif isinstance(value, list):
            for item in value:
                images(item, path)

    for path in _files(root):
        rel = path.relative_to(root)
        parts = rel.parts
        try:
            if parts[0] == "clusters" and path.suffix in (".yaml", ".yml") and not path.name.endswith(".sops.yaml"):
                for doc in yaml.load_all(path.read_text(encoding="utf-8"), Loader=ManifestLoader):
                    if not isinstance(doc, dict):
                        continue
                    kind = doc.get("kind")
                    if kind in ("Secret", "SealedSecret"):
                        continue
                    metadata = doc.get("metadata") or {}
                    spec = doc.get("spec") or {}
                    if kind == "HelmRelease" and isinstance(spec, dict):
                        chart = ((spec.get("chart") or {}).get("spec") or {})
                        if isinstance(chart, dict) and chart.get("chart"):
                            source = chart.get("sourceRef") or {}
                            add(chart["chart"], "helm-chart", path, chart.get("version"),
                                {"repository": str(source.get("name", "")),
                                 "namespace": str(source.get("namespace") or metadata.get("namespace", "default"))})
                    elif kind == "HelmRepository" and isinstance(spec, dict):
                        url = spec.get("url")
                        if isinstance(url, str):
                            parsed = urlsplit(url)
                            if parsed.username or parsed.password or parsed.query or parsed.fragment:
                                url = "[redacted repository URL]"
                        add(metadata.get("name", "unknown"), "helm-repository", path, url, {"namespace": str(metadata.get("namespace", "default"))})
                    elif kind == "HelmChartConfig":
                        add(metadata.get("name", "unknown"), "k3s-component", path)
                    images(doc, path)
            elif parts[:2] == (".github", "workflows") and path.suffix in (".yaml", ".yml"):
                for line in path.read_text(encoding="utf-8").splitlines():
                    match = ACTION.match(line)
                    if match:
                        add(match.group(1), "action", path, match.group(1))
            elif path.name.startswith("Dockerfile"):
                for line in path.read_text(encoding="utf-8").splitlines():
                    match = FROM.match(line)
                    if match:
                        add(match.group(1), "docker-base", path, match.group(1))
            elif path.name == "Cargo.lock":
                lock = tomllib.loads(path.read_text(encoding="utf-8"))
                for package in lock.get("package", []):
                    if isinstance(package, dict) and package.get("name") and package.get("version"):
                        add(package["name"], "rust-locked", path, package["version"],
                            {"source_kind": "registry" if str(package.get("source", "")) in
                             ("registry+https://github.com/rust-lang/crates.io-index", "sparse+https://index.crates.io/") else "other"})
            elif path.name == "rust-toolchain.toml":
                toolchain = tomllib.loads(path.read_text(encoding="utf-8"))
                add("rust-toolchain", "rust-toolchain", path, toolchain.get("toolchain", {}).get("channel"))
            elif path.name in ("uv.lock", "poetry.lock"):
                lock = tomllib.loads(path.read_text(encoding="utf-8"))
                for package in lock.get("package", []):
                    if isinstance(package, dict) and package.get("name") and package.get("version"):
                        name = str(package["name"])
                        version = str(package["version"])
                        source = package.get("source") or {}
                        registry = source.get("registry") if isinstance(source, dict) else None
                        source_kind = "registry" if not source or registry in ("https://pypi.org/simple", "https://pypi.org/simple/") else "other"
                        add(name, "python-locked", path, f"{name}=={version}", {"source_kind": source_kind})
            elif path.name == "Pipfile":
                pipfile = tomllib.loads(path.read_text(encoding="utf-8"))
                for section in ("packages", "dev-packages"):
                    for name, definition in pipfile.get(section, {}).items():
                        if isinstance(definition, str):
                            requirement = name if definition == "*" else name + definition
                            add(requirement, "python-dependency", path, requirement)
                        elif isinstance(definition, dict) and any(key in definition for key in ("git", "path", "file", "index")):
                            add(name, "python-dependency", path, name, {"source_kind": "other"})
                        elif isinstance(definition, dict):
                            version = definition.get("version", "*")
                            requirement = name if version == "*" else name + str(version)
                            add(requirement, "python-dependency", path, requirement)
                        else:
                            add(name, "dependency-lock-or-requirements", path)
            elif path.name == "Pipfile.lock":
                lock = json.loads(path.read_text(encoding="utf-8"))
                for section in ("default", "develop"):
                    for name, entry in lock.get(section, {}).items():
                        version = entry.get("version") if isinstance(entry, dict) else None
                        if isinstance(version, str) and version.startswith("=="):
                            source_kind = "other" if entry.get("index") or any(key in entry for key in ("git", "path", "file")) else "registry"
                            add(name, "python-locked", path, f"{name}{version}", {"source_kind": source_kind})
                        else:
                            add(name, "python-locked", path, None, {"source_kind": "other"})
            elif re.fullmatch(r"requirements[^/]*\.txt", path.name):
                for line in path.read_text(encoding="utf-8").splitlines():
                    requirement = line.split(" #", 1)[0].strip()
                    if not requirement or requirement.startswith("#"):
                        continue
                    if requirement.startswith("-") or "://" in requirement:
                        add("external requirement or include", "dependency-lock-or-requirements", path)
                    else:
                        add(requirement, "python-dependency", path, requirement)
            elif path.name == "go.sum":
                for line in path.read_text(encoding="utf-8").splitlines():
                    fields = line.split()
                    if len(fields) == 3:
                        version = fields[1].removesuffix("/go.mod")
                        add(fields[0], "go-locked", path, version)
                    elif fields:
                        add("invalid Go checksum", "dependency-lock-or-requirements", path)
            elif path.name in ("pom.xml", "pyproject.toml", "Cargo.toml", "go.mod"):
                kind = {"pom.xml": "maven", "pyproject.toml": "python", "Cargo.toml": "rust", "go.mod": "go"}[path.name]
                add(path.parent.name, kind, path)
                if path.name == "go.mod":
                    in_require = False
                    for line in path.read_text(encoding="utf-8").splitlines():
                        clean = line.split("//", 1)[0].strip()
                        if clean == "require (":
                            in_require = True
                            continue
                        if clean == ")":
                            in_require = False
                            continue
                        fields = clean.split()
                        if fields and fields[0] in ("replace", "exclude", "retract"):
                            add(fields[0], "go-directive", path)
                        elif fields and fields[0] in ("go", "toolchain") and len(fields) == 2:
                            add(fields[0], "go-toolchain", path, fields[1])
                        elif fields and fields[0] == "require" and len(fields) >= 3 and not re.search(r"//\s*indirect\b", line):
                            add(fields[1], "go-dependency", path, fields[2])
                        elif in_require and len(fields) >= 2 and not re.search(r"//\s*indirect\b", line):
                            add(fields[0], "go-dependency", path, fields[1])
                elif path.name == "pom.xml":
                    pom = ET.parse(path).getroot()
                    ns = {"m": pom.tag[1:].split("}")[0]} if pom.tag.startswith("{") else {}
                    prefix = "m:" if ns else ""
                    properties = pom.find(f"{prefix}properties", ns)
                    if properties is not None:
                        for property_name in ("java.version", "maven.compiler.release", "maven.compiler.source", "maven.compiler.target"):
                            value = properties.findtext(f"{prefix}{property_name}", default="", namespaces=ns)
                            if value:
                                add(property_name, "java-toolchain", path, value)
                    parent = pom.find(f"{prefix}parent", ns)
                    if parent is not None:
                        group = parent.findtext(f"{prefix}groupId", default="", namespaces=ns)
                        artifact = parent.findtext(f"{prefix}artifactId", default="", namespaces=ns)
                        version = parent.findtext(f"{prefix}version", default="", namespaces=ns)
                        add(f"{group}:{artifact}", "maven-parent", path, version or None)
                    for section, category in (("dependencies", "maven-dependency"), ("build/plugins", "maven-plugin"), ("build/pluginManagement/plugins", "maven-plugin"), ("dependencyManagement/dependencies", "maven-managed")):
                        section_path = "/".join(prefix + part for part in section.split("/"))
                        tag = "plugin" if category == "maven-plugin" else "dependency"
                        for entry in pom.findall(f"{section_path}/{prefix}{tag}", ns):
                            group = entry.findtext(f"{prefix}groupId", default="", namespaces=ns)
                            artifact = entry.findtext(f"{prefix}artifactId", default="", namespaces=ns)
                            version = entry.findtext(f"{prefix}version", default="", namespaces=ns)
                            if artifact:
                                add(f"{group}:{artifact}", category, path, version or None)
                elif path.name == "Cargo.toml":
                    cargo = tomllib.loads(path.read_text(encoding="utf-8"))
                    sections = [cargo]
                    sections.extend(value for value in cargo.get("target", {}).values() if isinstance(value, dict))
                    for section in sections:
                        for group in ("dependencies", "build-dependencies", "dev-dependencies"):
                            for name, value in section.get(group, {}).items():
                                version = value if isinstance(value, str) else value.get("version") if isinstance(value, dict) else None
                                package = value.get("package", name) if isinstance(value, dict) else name
                                source_kind = "other" if isinstance(value, dict) and any(k in value for k in ("git", "path", "registry", "workspace")) else "registry"
                                add(package, "rust-dependency", path, version, {"source_kind": source_kind})
                elif path.name == "pyproject.toml":
                    project = tomllib.loads(path.read_text(encoding="utf-8"))
                    metadata = project.get("project", {})
                    sources = project.get("tool", {}).get("uv", {}).get("sources", {})
                    local_sources = {canonicalize_name(name) for name, source in sources.items()
                                     if isinstance(source, dict) and
                                     (source.get("workspace") is True or "path" in source)}
                    groups = [metadata.get("dependencies", []), project.get("build-system", {}).get("requires", [])]
                    groups.extend(metadata.get("optional-dependencies", {}).values())
                    groups.extend(project.get("dependency-groups", {}).values())
                    for group in groups:
                        for requirement in group:
                            if isinstance(requirement, str) and "://" not in requirement:
                                try:
                                    local = canonicalize_name(Requirement(requirement).name) in local_sources
                                except InvalidRequirement:
                                    local = False
                                if local:
                                    add(requirement, "python-local-project", path)
                                else:
                                    add(requirement, "python-dependency", path, requirement)
                            elif isinstance(requirement, str):
                                add("external Python requirement", "dependency-lock-or-requirements", path)
                            else:
                                add("dynamic dependency group", "python-dependency", path)
        except (OSError, UnicodeError, yaml.YAMLError, ET.ParseError, ValueError, TypeError, AttributeError) as exc:
            errors.append(f"Cannot parse {rel}: {type(exc).__name__}")
    # Lockfiles describe the entire graph. Use their records only as metadata
    # for matching direct declarations, never as independent dependencies.
    lock_kinds = {"rust-locked", "python-locked", "go-locked"}
    locks = [item for item in components if item.kind in lock_kinds]
    for item in components:
        if item.kind not in ("rust-dependency", "python-dependency"):
            continue
        try:
            name = item.name if item.kind == "rust-dependency" else Requirement(item.declared or "").name
        except InvalidRequirement:
            continue
        project_dir = (root / item.source).parent
        matches = []
        for locked in locks:
            if locked.kind != ("rust-locked" if item.kind == "rust-dependency" else "python-locked"):
                continue
            lock_dir = (root / locked.source).parent
            if lock_dir not in (project_dir, *project_dir.parents):
                continue
            if item.kind == "python-dependency" and lock_dir != project_dir:
                try:
                    lock_data = tomllib.loads((root / locked.source).read_text(encoding="utf-8"))
                    project_data = tomllib.loads((root / item.source).read_text(encoding="utf-8"))
                    if project_data.get("project", {}).get("name") not in lock_data.get("manifest", {}).get("members", []):
                        continue
                except (OSError, ValueError, TypeError):
                    continue
            if (locked.name == name if item.kind == "rust-dependency" else canonicalize_name(locked.name) == canonicalize_name(name)):
                matches.append(locked)
        if matches:
            nearest = max(len((root / entry.source).parent.parts) for entry in matches)
            matches = [entry for entry in matches if len((root / entry.source).parent.parts) == nearest]
            versions = {entry.declared if item.kind == "rust-dependency" else (entry.declared or "").split("==", 1)[-1] for entry in matches}
            if len(versions) == 1 and next(iter(versions)):
                item.metadata["locked_version"] = next(iter(versions))
                item.metadata["lockfile"] = matches[0].source
                if matches[0].metadata.get("source_kind") != "registry":
                    item.metadata["source_kind"] = "other"
    components = [item for item in components if item.kind not in lock_kinds]
    return components, errors
