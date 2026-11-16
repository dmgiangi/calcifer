"""Bounded subprocess execution without forwarding sensitive tool output."""
from dataclasses import dataclass
import os
from pathlib import Path
import subprocess
import tempfile


@dataclass(frozen=True)
class ToolResult:
    status: str
    returncode: int | None = None
    stdout: str = ""


def _redact(text: str, environment: dict[str, str]) -> str:
    """Remove known credential values from tool output before exposing it."""
    sensitive = ("TOKEN", "PASSWORD", "SECRET", "PRIVATE_KEY", "API_KEY", "ACCESS_KEY")
    values = {value for key, value in environment.items()
              if any(word in key.upper() for word in sensitive) and len(value) >= 8}
    for value in sorted(values, key=len, reverse=True):
        text = text.replace(value, "[REDACTED]")
    return text


def run_readonly(argv: list[str], *, cwd: Path, timeout: float = 30,
                 output_limit: int = 1024 * 1024) -> ToolResult:
    """Run a read-only command with isolated home/cache; never expose stderr.

    Callers must only use stdout for machine-readable, non-secret data and
    should validate it before presenting it. Tools must be run in a disposable
    copy if they can modify their working directory.
    """
    if not argv or not argv[0] or timeout <= 0:
        raise ValueError("command and positive timeout are required")
    with tempfile.TemporaryDirectory(prefix="calcifer-update-") as directory:
        env = os.environ.copy()
        env.update(HOME=directory, XDG_CACHE_HOME=directory,
                   TMPDIR=directory, MAVEN_USER_HOME=directory,
                   CARGO_HOME=directory, GOPATH=directory)
        try:
            # Files prevent an untrusted command from exhausting Python memory.
            with tempfile.TemporaryFile() as stdout, tempfile.TemporaryFile() as stderr:
                result = subprocess.run(argv, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                                        stdout=stdout, stderr=stderr, timeout=timeout,
                                        check=False)
                if result.returncode:
                    return ToolResult("failed", result.returncode)
                if stdout.tell() > output_limit:
                    return ToolResult("output-too-large", result.returncode)
                stdout.seek(0)
                return ToolResult("ok", result.returncode, _redact(stdout.read().decode("utf-8", errors="replace"), env))
        except FileNotFoundError:
            return ToolResult("missing")
        except subprocess.TimeoutExpired:
            return ToolResult("timeout")
        except OSError:
            return ToolResult("unavailable")


from contextlib import contextmanager
import shutil


@contextmanager
def isolated_workspace(root: Path, paths: list[Path]):
    """Copy only selected regular files into a temporary workspace.

    Paths must be relative to root, must not traverse symlinks, and cannot
    reference paths outside root. The yielded directory is removed on exit.
    """
    root = root.resolve(strict=True)
    with tempfile.TemporaryDirectory(prefix="calcifer-update-work-") as directory:
        workspace = Path(directory)
        for path in paths:
            if path.is_absolute() or not path.parts or ".." in path.parts:
                raise ValueError("workspace paths must be relative and contained")
            source = root
            for part in path.parts:
                source = source / part
                if source.is_symlink():
                    raise ValueError("workspace paths must not traverse symlinks")
            if not source.is_file():
                raise ValueError("workspace input must be a regular file")
            destination = workspace / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
        yield workspace
