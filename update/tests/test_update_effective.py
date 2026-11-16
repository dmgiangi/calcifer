import json
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from update.catalog import annotate
from update.checks import check_all
from update.effective import effective_images
from update.model import Component
from update.scope import cluster_scopes
from update.tools import ToolResult


class EffectiveImagesTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which('kubectl'), 'kubectl is needed for the native render test')
    def test_native_render_applies_overlay_and_copies_generator_inputs(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            entries = self.fixture(root)
            base = root / 'clusters/shared'
            with (base / 'kustomization.yaml').open('a') as stream:
                stream.write('configMapGenerator:\n- name: settings\n  files: [settings.txt]\n')
            (base / 'settings.txt').write_text('some configuration, not a resource')
            for cluster, version in (('home', '1.0.0'), ('cloud', '2.0.0')):
                with (root / f'clusters/calcifer-{cluster}/kustomization.yaml').open('a') as stream:
                    stream.write(f'images:\n- name: app\n  newTag: {version}\n')
            scopes, _ = cluster_scopes(root)
            derived, errors = effective_images(root, entries, scopes)
            self.assertEqual(errors, [])
            self.assertEqual({x.declared for x in derived}, {'app:1.0.0', 'app:2.0.0'})
            self.assertEqual(entries[0].status, 'configured')

    def fixture(self, root):
        base = root / 'clusters/shared'
        base.mkdir(parents=True)
        (base / 'kustomization.yaml').write_text('apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources: [app.yaml]\n')
        (base / 'app.yaml').write_text('apiVersion: apps/v1\nkind: Deployment\nmetadata: {name: app}\nspec: {template: {spec: {containers: [{name: app, image: "app:unreleased"}]}}}\n')
        for cluster in ('home', 'cloud'):
            entry = root / f'clusters/calcifer-{cluster}'
            entry.mkdir()
            (entry / 'kustomization.yaml').write_text('apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources: [../shared]\n')
        return [Component('app', 'image', 'clusters/shared/app.yaml', declared='app:unreleased',
                          clusters=['cloud', 'home'])]

    def runner(self, argv, cwd, **kwargs):
        self.assertEqual(argv[:2], ['kubectl', 'kustomize'])
        self.assertNotIn('get', argv)
        self.assertTrue((cwd / 'clusters/shared/app.yaml').is_file())
        reference = 'app:1.0.0' if argv[-1].endswith('calcifer-home') else 'app:2.0.0'
        return ToolResult('ok', 0, json.dumps({'kind': 'Deployment', 'spec': {'image': reference}})
                          + '\n---\n' + json.dumps({'kind': 'Secret', 'data': {'image': 'do-not-report'}}))

    def test_checks_per_cluster_render_not_overridden_base(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            entries = self.fixture(root)
            scopes, errors = cluster_scopes(root)
            self.assertEqual(errors, [])
            derived, errors = effective_images(root, entries, scopes, runner=self.runner)
            self.assertEqual(errors, [])
            self.assertEqual({(x.declared, tuple(x.clusters)) for x in derived},
                             {('app:1.0.0', ('home',)), ('app:2.0.0', ('cloud',))})
            self.assertEqual(entries[0].status, 'configured')
            self.assertEqual(entries[0].declared, 'app:unreleased')
            self.assertIn(entries[0].source, json.loads(derived[0].metadata['source_files']))
            annotate(derived)
            with patch('update.checks.check_image') as check:
                check_all(entries + derived)
            self.assertEqual(check.call_count, 2)

    def test_failed_render_retains_original_and_coverage_gap(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            entries = self.fixture(root)
            scopes, _ = cluster_scopes(root)
            def runner(argv, **kwargs):
                return (ToolResult('failed') if argv[-1].endswith('calcifer-cloud')
                        else self.runner(argv, **kwargs))
            derived, errors = effective_images(root, entries, scopes, runner=runner)
            self.assertTrue(errors)
            self.assertEqual(len(derived), 1)
            self.assertNotIn('effective_state', entries[0].metadata)

    def test_remote_resources_never_reach_native_renderer(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            entries = self.fixture(root)
            target = root / 'clusters/calcifer-home/kustomization.yaml'
            target.write_text('apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources: [https://example.invalid/repo]\n')
            entries[0].clusters = ['home']
            with patch('update.effective.run_readonly') as run:
                _, errors = effective_images(root, entries, {}, runner=run)
            run.assert_not_called()
            self.assertTrue(errors)