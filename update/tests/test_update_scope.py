import tempfile
import unittest
from pathlib import Path
from update.scope import cluster_scopes


class ScopeTest(unittest.TestCase):
    def test_shared_base_is_reachable_from_both_clusters(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            for cluster in ('home', 'cloud'):
                entry = root / 'clusters' / f'calcifer-{cluster}'
                entry.mkdir(parents=True)
                (entry / 'kustomization.yaml').write_text('apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources:\n  - ../shared\n')
            shared = root / 'clusters/shared'
            shared.mkdir()
            (shared / 'kustomization.yaml').write_text('apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources:\n  - app.yaml\n')
            (shared / 'app.yaml').write_text('apiVersion: v1\nkind: Service\nmetadata:\n  name: app\n')
            scopes, errors = cluster_scopes(root)
            self.assertEqual(errors, [])
            self.assertEqual(scopes['clusters/shared/app.yaml'], ['cloud', 'home'])

    def test_external_reference_is_explicit_gap(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            entry = root / 'clusters/calcifer-home'
            entry.mkdir(parents=True)
            (entry / 'kustomization.yaml').write_text('apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources:\n  - https://example.com/repo\n')
            _, errors = cluster_scopes(root)
            self.assertTrue(errors)
