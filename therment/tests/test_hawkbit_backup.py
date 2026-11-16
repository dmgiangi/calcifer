import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "hawkbit_backup", Path(__file__).parents[2] / "scripts" / "hawkbit-local-backup.py"
)
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)


class RetentionTest(unittest.TestCase):
    def test_keeps_daily_and_weekly_and_ignores_unrelated_files(self):
        names = [f"hawkbit-202611{day:02d}T120000Z.tar" for day in range(1, 17)]
        names += ["hawkbit-20261025T120000Z.tar", "hawkbit-20261018T120000Z.tar", "other.tar"]
        expired = backup.expired_backups(names)
        self.assertNotIn("hawkbit-20261116T120000Z.tar", expired)
        self.assertNotIn("hawkbit-20261110T120000Z.tar", expired)
        self.assertNotIn("hawkbit-20261101T120000Z.tar", expired)
        self.assertIn("hawkbit-20261025T120000Z.tar", expired)
        self.assertNotIn("other.tar", expired)

    def test_invalid_timestamps_are_never_deleted(self):
        self.assertEqual(backup.expired_backups(["hawkbit-20269999T999999Z.tar"]), [])

    def test_no_retention_mode_preserves_every_existing_archive(self):
        names = ["hawkbit-20250101T000000Z.tar", "hawkbit-20261116T000000Z.tar"]
        self.assertEqual(backup.retention_candidates(names, disabled=True), [])


if __name__ == "__main__":
    unittest.main()
