import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from update.inventory import discover
from update.java import check_java_toolchain, latest_java_release
from update.model import Component


class JavaTest(unittest.TestCase):
    def item(self, distribution, version):
        return Component('build-jdk', 'java-toolchain', 'workflow.yaml', declared=version,
                         metadata={'distribution': distribution, 'toolchain_role': 'build-jdk'})

    def test_workflow_identity_is_separate_from_project_target(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workflows = root / '.github/workflows'
            workflows.mkdir(parents=True)
            (workflows / 'build.yaml').write_text('jobs:\n  build:\n    steps:\n'
                '    - uses: graalvm/setup-graalvm@v1\n      with:\n'
                '        java-version: "jdk-25.0.1"\n        distribution: graalvm-community\n'
                '    - uses: actions/setup-java@v5\n      with:\n'
                '        java-version: "25.0.1+8"\n        distribution: temurin\n')
            (root / 'pom.xml').write_text('<project><properties><java.version>25</java.version></properties></project>')
            items, errors = discover(root)
        self.assertEqual(errors, [])
        targets = [item for item in items if item.kind == 'java-toolchain']
        self.assertEqual(len(targets), 3)
        builds = [item for item in targets if item.metadata['toolchain_role'] == 'build-jdk']
        self.assertEqual({(item.declared, item.metadata['distribution']) for item in builds},
                         {('jdk-25.0.1', 'graalvm-community'), ('25.0.1+8', 'temurin')})
        project = next(item for item in targets if item.metadata['toolchain_role'] == 'project-target')
        self.assertNotIn('distribution', project.metadata)

    def test_same_major_different_distributions_do_not_share_cache(self):
        calls, cache = [], {}
        def fetch(distribution, major):
            calls.append((distribution, major))
            return 'jdk-25.0.1+9' if distribution == 'temurin' else 'jdk-25.0.2'
        temurin, graal = self.item('temurin', '25.0.1+8'), self.item('graalvm-community', '25.0.1')
        for item in (temurin, graal):
            check_java_toolchain(item, cache, fetch)
            self.assertEqual(item.status, 'update-available')
        self.assertEqual(calls, [('temurin', 25), ('graalvm-community', 25)])
        self.assertIn('adoptium/temurin25-binaries', temurin.metadata['candidate_source'])
        self.assertIn('graalvm/graalvm-ce-builds', graal.metadata['candidate_source'])

    def test_exact_graalvm_jdk_tag_is_a_comparable_pin(self):
        item = self.item('graalvm-community', 'jdk-25.0.2')
        check_java_toolchain(item, {}, lambda *_: 'jdk-25.0.2')
        self.assertEqual((item.candidate, item.status), ('jdk-25.0.2', 'current'))

    def test_selectors_stay_unknown_and_unsupported_distribution_is_not_substituted(self):
        for distribution, version in (('temurin', '25'), ('temurin', '25.0.1'), ('graalvm-community', '25')):
            item = self.item(distribution, version)
            check_java_toolchain(item, {}, lambda *_: 'jdk-25.0.2+8' if distribution == 'temurin' else 'jdk-25.0.2')
            self.assertEqual(item.status, 'unknown')
            self.assertIsNotNone(item.candidate)
        item = self.item('unknown-vendor', '25.0.1')
        check_java_toolchain(item, {}, lambda *_: self.fail('must not guess a distribution'))
        self.assertEqual((item.status, item.candidate), ('unknown', None))

    def test_non_lts_java_lines_do_not_produce_candidates(self):
        item = self.item('temurin', '24.0.1+8')
        check_java_toolchain(item, {}, lambda *_: self.fail('non-LTS line must not be queried'))
        self.assertEqual((item.status, item.candidate), ('unknown', None))
        self.assertIn('not LTS', item.detail)
        self.assertIsNone(latest_java_release('temurin', 24))

    def test_rejects_wrong_major_and_prerelease_and_respects_policy(self):
        for release in ('jdk-26.0.1', 'jdk-25.0.2-ea', 'jdk-25.0.2'):
            item = self.item('temurin', '25.0.1+8')
            check_java_toolchain(item, {}, lambda *_: release)
            self.assertEqual((item.status, item.candidate), ('unknown', None))
        item = self.item('graalvm-community', '25.0.1')
        item.metadata['version_policy'] = 'unrecognized'
        check_java_toolchain(item, {}, lambda *_: 'jdk-25.0.2')
        self.assertEqual((item.status, item.candidate), ('unknown', None))

    def test_official_graalvm_history_selects_requested_major_only(self):
        entries = [{'ref': 'refs/tags/' + tag} for tag in (
            'jdk-26.0.1', 'jdk-25.0.2', 'jdk-25.0.1', 'jdk-25.0.3', 'jdk-25.0.4-ea')]
        responses = [entries, {'tag_name': 'jdk-25.0.3', 'prerelease': True},
                     {'tag_name': 'jdk-25.0.2', 'prerelease': False}]
        with patch('update.java.urlopen', side_effect=[io.BytesIO(json.dumps(value).encode()) for value in responses]) as get:
            self.assertEqual(latest_java_release('graalvm-community', 25), 'jdk-25.0.2')
        urls = [call.args[0].full_url for call in get.call_args_list]
        self.assertTrue(urls[0].endswith('git/matching-refs/tags/jdk-25.'))
        self.assertTrue(urls[-1].endswith('releases/tags/jdk-25.0.2'))
        with patch('update.java.latest_release', return_value='jdk-25.0.2+8') as fetch:
            self.assertEqual(latest_java_release('temurin', 25), 'jdk-25.0.2+8')
        fetch.assert_called_once_with('adoptium', 'temurin25-binaries')

    def test_graalvm_mismatched_metadata_and_oversized_response_are_rejected(self):
        responses = [[{'ref': 'refs/tags/jdk-25.0.1'}], {'tag_name': 'jdk-26.0.1'}]
        with patch('update.java.urlopen', side_effect=[io.BytesIO(json.dumps(value).encode()) for value in responses]):
            with self.assertRaises(ValueError):
                latest_java_release('graalvm-community', 25)
        with patch('update.java.urlopen', return_value=io.BytesIO(b' ' * (1024 * 1024 + 1))):
            with self.assertRaises(ValueError):
                latest_java_release('graalvm-community', 25)

    def test_source_failures_are_unavailable_without_exception_content(self):
        item = self.item('graalvm-community', '25.0.1')
        def fail(*_):
            raise OSError('sensitive-response')
        check_java_toolchain(item, {}, fail)
        self.assertEqual(item.status, 'unavailable')
        self.assertNotIn('sensitive-response', repr(item))