"""Resolve the selected Go module graph in an isolated workspace."""
import json
from pathlib import Path

from .tools import isolated_workspace, run_readonly


def selected_modules(root, mod_path, runner=run_readonly):
    mod = Path(mod_path)
    paths = [mod]
    checksum = mod.parent / 'go.sum'
    if (root / checksum).is_file():
        paths.append(checksum)
    try:
        with isolated_workspace(root, paths) as work:
            result = runner(['go', 'list', '-mod=readonly', '-m', '-json', 'all'], cwd=work / mod.parent, timeout=120)
            if result.status != 'ok':
                return {}, f'Go module graph unavailable: {result.status}'
            decoder = json.JSONDecoder()
            text = result.stdout
            offset = 0
            modules = {}
            while offset < len(text):
                while offset < len(text) and text[offset].isspace():
                    offset += 1
                if offset == len(text):
                    break
                entry, offset = decoder.raw_decode(text, offset)
                if isinstance(entry, dict) and isinstance(entry.get('Path'), str):
                    modules[entry['Path']] = (entry.get('Version'), bool(entry.get('Replace')))
            return modules, None
    except (OSError, ValueError, TypeError):
        return {}, 'Cannot safely resolve the Go module graph'
