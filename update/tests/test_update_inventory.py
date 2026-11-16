import tempfile
import unittest
from pathlib import Path

from update.inventory import discover


class InventoryTest(unittest.TestCase):
    def test_finds_declarations_and_skips_encrypted_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cluster = root / "clusters/calcifer-home/apps"
            cluster.mkdir(parents=True)
            (cluster / "app.yaml").write_text("containers:\n  - image: example/app:1\n")
            (cluster / "secret.sops.yaml").write_text("image: private/secret:1\n")
            (root / "Dockerfile").write_text("FROM example/base:1 AS build\n")
            (root / "pyproject.toml").write_text("[project]\nname='fixture'\n")
            components, errors = discover(root)
            self.assertFalse(errors)
            self.assertEqual({x.kind for x in components}, {"image", "docker-base", "python"})
            image = next(x for x in components if x.kind == "image")
            self.assertEqual(image.clusters, ["home"])
            self.assertEqual(image.status, "unsupported")

    def test_repository_includes_known_components(self):
        components, errors = discover()
        self.assertFalse(errors)
        self.assertTrue(any(c.kind == "rust" and c.source == "therment/Cargo.toml" for c in components))
        self.assertTrue(any(c.kind == "image" and "home-assistant" in c.name for c in components))


class PythonFilesTest(unittest.TestCase):
    def test_requirements_and_lock_are_inventoried_without_urls(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'requirements.txt').write_text('example==1.0\n-r other.txt\nhttps://user:password@example.test/package\n')
            (root / 'uv.lock').write_text('[[package]]\nname = "example"\nversion = "1.0"\n')
            items, errors = discover(root)
            self.assertEqual(errors, [])
            self.assertFalse(any(x.kind == 'python-locked' for x in items))
            direct = next(x for x in items if x.kind == 'python-dependency')
            self.assertEqual(direct.metadata.get('locked_version'), '1.0')
            self.assertTrue(any(x.kind == 'python-dependency' for x in items))
            self.assertTrue(any(x.kind == 'dependency-lock-or-requirements' for x in items))
            self.assertNotIn('password', repr(items))


class UvWorkspaceTest(unittest.TestCase):
    def test_workspace_dependency_is_not_classified_as_pypi_package(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'pyproject.toml').write_text('[project]\nname="root"\ndependencies=["local-service"]\n[tool.uv.sources]\nlocal-service={workspace=true}\n')
            (root / 'uv.lock').write_text('[[package]]\nname="local-service"\nversion="1.0.0"\nsource={editable="service"}\n')
            items, errors = discover(root)
            self.assertFalse(errors)
            local = [item for item in items if item.name == 'local-service']
            self.assertEqual(len(local), 1)
            self.assertTrue(all(item.kind == 'python-local-project' for item in local))


class DirectDependenciesTest(unittest.TestCase):
    def test_lockfiles_and_indirect_go_are_not_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'pyproject.toml').write_text('[project]\nname="example"\ndependencies=["direct>=1"]\n')
            (root / 'uv.lock').write_text('[[package]]\nname="direct"\nversion="1.2.0"\n[[package]]\nname="transitive"\nversion="2.0.0"\n')
            (root / 'Cargo.toml').write_text('[package]\nname="example"\nversion="0.1.0"\n[dependencies]\ndirect-rust="1"\nalias={package="real-rust",version="1"}\n')
            (root / 'Cargo.lock').write_text('[[package]]\nname="direct-rust"\nversion="1.1.0"\nsource="registry+https://github.com/rust-lang/crates.io-index"\n[[package]]\nname="real-rust"\nversion="1.0.1"\nsource="registry+https://github.com/rust-lang/crates.io-index"\n[[package]]\nname="transitive-rust"\nversion="2.0.0"\n')
            (root / 'go.mod').write_text('module example.test/app\nrequire (\n example.test/direct v1.0.0\n example.test/transitive v1.0.0 // indirect\n)\n')
            (root / 'go.sum').write_text('example.test/transitive v1.0.0 h1:abc\n')
            items, errors = discover(root)
            self.assertEqual(errors, [])
            names = {item.name for item in items}
            self.assertFalse({'transitive', 'transitive-rust', 'example.test/transitive'} & names)
            py = next(item for item in items if item.kind == 'python-dependency')
            self.assertEqual(py.metadata.get('locked_version'), '1.2.0')
            rust = {item.name: item for item in items if item.kind == 'rust-dependency'}
            self.assertEqual(rust['real-rust'].metadata.get('locked_version'), '1.0.1')
            self.assertEqual(rust['direct-rust'].metadata.get('locked_version'), '1.1.0')
            self.assertTrue(any(item.name == 'example.test/direct' for item in items))
