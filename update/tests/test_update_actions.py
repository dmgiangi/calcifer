import unittest
from update.actions import check_action
from update.model import Component


def action(reference):
    return Component(reference, "action", ".github/workflows/build.yaml", declared=reference)


class ActionCheckTest(unittest.TestCase):
    def test_compares_stable_release_and_caches_repository(self):
        calls = []
        def fetch(owner, repo):
            calls.append((owner, repo))
            return "v2.1.0"
        cache = {}
        first = action("owner/action@v1.0.0")
        second = action("owner/action/subdir@v2.1.0")
        check_action(first, cache, fetch)
        check_action(second, cache, fetch)
        self.assertEqual((first.status, first.candidate), ("update-available", "v2.1.0"))
        self.assertEqual(second.status, "current")
        self.assertEqual(len(calls), 1)

    def test_does_not_guess_mutable_or_prerelease_versions(self):
        item = action("owner/action@v1")
        check_action(item, {}, lambda *_: "v1.4.0")
        self.assertEqual(item.status, "unknown")
        item = action("owner/action@v1.2.0")
        check_action(item, {}, lambda *_: "v2.0.0-rc.1")
        self.assertEqual(item.status, "unknown")

    def test_major_update_is_reported_without_assuming_current_revision(self):
        item = action("owner/action@v1")
        check_action(item, {}, lambda *_: "v2.0.0")
        self.assertEqual(item.status, "update-available")
        self.assertEqual(item.candidate, "v2.0.0")

    def test_digest_and_unrecognized_refs_are_not_guessed(self):
        for ref in ("owner/action@" + "a" * 40, "./local-action", "docker://example:1"):
            item = action(ref)
            check_action(item, {}, lambda *_: self.fail("must not fetch"))
            self.assertEqual(item.status, "unknown")
            self.assertIsNone(item.candidate)

    def test_source_failure_has_no_sensitive_exception_text(self):
        item = action("owner/action@v1.2.0")
        def fail(*_):
            raise OSError("secret-token")
        check_action(item, {}, fail)
        self.assertEqual(item.status, "unavailable")
        self.assertNotIn("secret-token", repr(item))


class MovingActionTagTest(unittest.TestCase):
    def test_major_tag_matching_release_commit_is_current(self):
        item = action('actions/checkout@v7')
        check_action(item, {}, fetch=lambda *_: 'v7.0.1', resolve=lambda *_: 'a' * 40)
        self.assertEqual(item.status, 'current')
        self.assertEqual(item.metadata['resolved_release'], 'v7.0.1')

    def test_different_commit_remains_unknown(self):
        item = action('actions/checkout@v7')
        check_action(item, {}, fetch=lambda *_: 'v7.0.1', resolve=lambda _o, _r, ref: 'a' * 40 if ref == 'v7' else 'b' * 40)
        self.assertEqual(item.status, 'unknown')
