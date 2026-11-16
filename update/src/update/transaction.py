"""Reviewable, fail-closed file transactions for future preparation strategies."""
from dataclasses import dataclass
import difflib
import hashlib
import os
from pathlib import Path
import tempfile


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def contained_file(root: Path, relative: Path) -> Path:
    if relative.is_absolute() or not relative.parts or ".." in relative.parts:
        raise ValueError("Path must be relative to the repository")
    current = root
    for part in relative.parts:
        current = current / part
        if current.is_symlink():
            raise ValueError("Symbolic links are not accepted")
    if not current.is_file():
        raise ValueError("Target must be an existing regular file")
    return current


@dataclass(frozen=True)
class Change:
    path: str
    original_hash: str
    original: bytes
    replacement: bytes


def preview(root: Path, replacements: dict[str, bytes], allowed: set[str]) -> tuple[list[Change], str]:
    if not replacements or set(replacements) - allowed:
        raise ValueError("Empty or unexpected target file set")
    changes = []
    output = []
    for name, replacement in sorted(replacements.items()):
        path = contained_file(root, Path(name))
        before = path.read_bytes()
        old = before.decode("utf-8")
        new = replacement.decode("utf-8")
        if old == new:
            continue
        changes.append(Change(name, digest(before), before, replacement))
        output.extend(difflib.unified_diff(old.splitlines(keepends=True), new.splitlines(keepends=True),
                                           fromfile=f"a/{name}", tofile=f"b/{name}"))
    return changes, "".join(output)


def _replace(path: Path, data: bytes) -> None:
    fd, name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(name, path.stat().st_mode & 0o777)
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def commit(root: Path, changes: list[Change], allowed: set[str], *, write=_replace) -> None:
    if not changes or len({c.path for c in changes}) != len(changes):
        raise ValueError("Empty or duplicate changes")
    if any(change.path not in allowed for change in changes):
        raise ValueError("Unexpected target")
    targets = [(contained_file(root, Path(change.path)), change) for change in changes]
    # Verify every input before the first write, including original content.
    for path, change in targets:
        if digest(change.original) != change.original_hash or path.read_bytes() != change.original:
            raise ValueError(f"Input changed since preview: {change.path}")
    written = []
    try:
        for path, change in targets:
            # Include the in-flight file: a writer can fail after replacing it.
            written.append((path, change))
            write(path, change.replacement)
    except Exception as exc:
        failed = []
        for path, change in reversed(written):
            try:
                _replace(path, change.original)
            except OSError:
                failed.append(change.path)
        if failed:
            raise RuntimeError("Rollback failed; inspect these files: " + ", ".join(failed)) from exc
        raise
