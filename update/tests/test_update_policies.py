import unittest
from unittest.mock import patch

from update.checks import check_all
from update.model import Component
from update.python_packages import check_python
from update.maven import check_maven
from update.versions import stable_version


class VersionPolicyTest(unittest.TestCase):
    def test_semver_build_identifiers_and_prereleases(self):
        self.assertEqual(stable_version('v1.2.3+spec-1.1.0'), stable_version('1.2.3'))
        for value in ('1.2.3-rc.1', '1.2.3-rc.1+build', '1.2.3+', '1.2.3+x..y',
                      '1.2.3+a_b', '01.2.3', '1.2.3+build!'):
            with self.subTest(value=value):
                self.assertIsNone(stable_version(value))

    def test_selects_latest_allowed_python_release_not_latest_absolute(self):
        item = Component('example', 'python-dependency', 'pyproject.toml', declared='example==1.2.0',
                         metadata={'version_policy': 'same-major'})
        check_python(item, {}, lambda _: ['1.3.0', '2.0.0', '1.4.0rc1'])
        self.assertEqual((item.status, item.candidate), ('update-available', '1.3.0'))

    def test_selects_maven_minor_line_and_rejects_unknown_policy(self):
        item = Component('org.example:lib', 'maven-dependency', 'pom.xml', declared='1.2.0',
                         metadata={'version_policy': 'same-minor'})
        check_maven(item, {}, lambda *_: ['1.2.1', '1.3.0', '2.0.0'])
        self.assertEqual(item.candidate, '1.2.1')
        item.metadata['version_policy'] = 'unrecognized'
        check_maven(item, {}, lambda *_: ['2.0.0'])
        self.assertEqual((item.status, item.candidate), ('unknown', None))

    def test_missing_baseline_cannot_establish_compatibility(self):
        item = Component('example', 'python-dependency', 'pyproject.toml', declared='example>=1',
                         metadata={'version_policy': 'same-major'})
        check_python(item, {}, lambda _: ['1.3.0'])
        self.assertEqual((item.status, item.candidate), ('unknown', None))

    def test_provenance_is_copied_to_deduplicated_checks(self):
        entries = [Component('example', 'rust-dependency', str(i), declared='1',
                             metadata={'locked_version': '1.0.0'}) for i in range(2)]
        with patch('update.checks.check_rust') as check:
            check.side_effect = lambda item, cache: setattr(item, 'candidate', '1.1.0')
            check_all(entries)
        for item in entries:
            self.assertEqual(item.metadata['candidate_source'], 'https://crates.io')
            self.assertEqual(item.metadata['version_policy'], 'latest-stable')
            self.assertEqual(item.metadata['current_basis'], 'resolved')
            self.assertIn('build metadata', item.metadata['comparison_basis'])