import unittest
from unittest.mock import patch
from update.checks import check_all
from update.model import Component


class ChecksTest(unittest.TestCase):
    def test_parent_candidate_is_verified_before_bundled_checks(self):
        host = Component('k3s', 'k3s', 'host', clusters=['home'])
        bundled = Component('traefik', 'k3s-component', 'file', clusters=['home'])
        def platform(item, cache):
            item.candidate = 'v1.32.0+k3s1'
        def bundle(item, hosts, cache):
            self.assertEqual(hosts[0].candidate, 'v1.32.0+k3s1')
        with patch('update.checks.check_platform', side_effect=platform), patch('update.checks.check_bundled', side_effect=bundle) as check:
            check_all([bundled, host])
        check.assert_called_once()

    def test_duplicate_entries_are_checked_once(self):
        entries = [Component('a', 'action', str(i), declared='owner/action@v1.0.0') for i in range(2)]
        def fake(item, cache, **kwargs):
            item.status = 'update-available'
            item.candidate = 'v2.0.0'
        with patch('update.checks.check_action', side_effect=fake) as check:
            check_all(entries, workers=2)
        self.assertEqual(check.call_count, 1)
        self.assertTrue(all(x.candidate == 'v2.0.0' for x in entries))

    def test_unexpected_error_is_private_and_partial(self):
        item = Component('a', 'action', 'file', declared='owner/action@v1.0.0')
        with patch('update.checks.check_action', side_effect=RuntimeError('secret-token')):
            check_all([item])
        self.assertEqual(item.status, 'unavailable')
        self.assertNotIn('secret-token', item.detail)


class ProgressTest(unittest.TestCase):
    def test_progress_reports_actual_completions_and_deduplicates(self):
        from threading import Event
        first_completed = Event()
        entries = [Component('slow', 'action', 'first', declared='owner/slow@v1.0.0'),
                   Component('fast', 'action', 'second', declared='owner/fast@v1.0.0'),
                   Component('fast', 'action', 'duplicate', declared='owner/fast@v1.0.0')]
        messages = []
        def fake(item, cache, **kwargs):
            if item.name == 'slow':
                self.assertTrue(first_completed.wait(2))
            item.status = 'current' if item.name == 'slow' else 'update-available'
        def progress(message):
            messages.append(message)
            if 'update-available' in message:
                first_completed.set()
        with patch('update.checks.check_action', side_effect=fake):
            check_all(entries, workers=2, progress=progress)
        self.assertEqual(messages[0], '0/2 controlli completati')
        self.assertIn('1/2', messages[1])
        self.assertIn('update-available', messages[1])
        self.assertIn('2/2', messages[2])
