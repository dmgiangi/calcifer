import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from update.catalog import annotate
from update.checks import check_all
from update.inventory import discover
from update.scope import cluster_scopes
from update.transaction import commit, preview, _replace


class IntegrationTest(unittest.TestCase):
    def test_two_cluster_shared_inventory_source_failure_and_rollback(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for cluster in ('home', 'cloud'):
                path = root / 'clusters' / f'calcifer-{cluster}'
                path.mkdir(parents=True)
                (path / 'kustomization.yaml').write_text('apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources:\n  - ../shared\n')
            shared = root / 'clusters/shared'
            shared.mkdir()
            (shared / 'kustomization.yaml').write_text('apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources:\n  - app.yaml\n')
            (shared / 'app.yaml').write_text('kind: Deployment\nspec:\n  template:\n    spec:\n      containers:\n      - image: example/app:1.0.0\n')
            (shared / 'settings.yaml').write_text('kind: ConfigMap\ndata:\n  image: {}\n')
            items, errors = discover(root)
            scopes, scope_errors = cluster_scopes(root)
            self.assertEqual((errors, scope_errors), ([], []))
            for item in items:
                item.clusters = scopes.get(item.source, [])
            annotate(items)
            image = next(item for item in items if item.kind == 'image')
            self.assertEqual(image.clusters, ['cloud', 'home'])
            with patch('update.checks.check_image', side_effect=OSError('credential must not leak')):
                check_all(items)
            self.assertEqual(image.status, 'unavailable')
            self.assertNotIn('credential', image.detail)
            unsupported = next(item for item in items if item.kind == 'unresolved-image')
            self.assertEqual(unsupported.status, 'unsupported')
            self.assertIn('settings.yaml', unsupported.source)
            other = shared / 'other.yaml'
            other.write_text('old\n')
            replacements = {'clusters/shared/app.yaml': b'new\n', 'clusters/shared/other.yaml': b'new\n'}
            changes, diff = preview(root, replacements, set(replacements))
            self.assertIn('+new', diff)
            before = (shared / 'app.yaml').read_text()
            def fail_second(path, data):
                if path == other:
                    raise OSError('test failure')
                _replace(path, data)
            with self.assertRaises(OSError):
                commit(root, changes, set(replacements), write=fail_second)
            self.assertEqual((shared / 'app.yaml').read_text(), before)
            self.assertEqual(other.read_text(), 'old\n')
