import tempfile
import unittest
from pathlib import Path
from update.action_prepare import prepare_action
from update.model import Component


class ActionPreparationTest(unittest.TestCase):
    def test_all_matching_workflows_are_updated_after_preview(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            paths = ['.github/workflows/a.yaml', '.github/workflows/b.yml']
            for path in paths:
                file = root / path
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text('steps:\n  - uses: actions/checkout@v4\n')
            items = [Component('actions/checkout@v4', 'action', path, declared='actions/checkout@v4') for path in paths]
            opts = {'fetch': lambda *_: 'v5.0.0', 'require_clean': lambda *_: None}
            diff, token = prepare_action(root, 'actions/checkout@v4', 'v5.0.0', items, **opts)
            self.assertIn('+  - uses: actions/checkout@v5.0.0', diff)
            prepare_action(root, 'actions/checkout@v4', 'v5.0.0', items, write=True, expected=token, **opts)
            self.assertTrue(all('v5.0.0' in (root / path).read_text() for path in paths))
