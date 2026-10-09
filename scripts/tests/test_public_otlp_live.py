"""Unit-test the live checker opt-in and cluster guards without cluster access."""

import contextlib
import io
import unittest
from unittest.mock import patch

import check_public_otlp_live as live


class LiveOtlpGuardTests(unittest.TestCase):
    def test_no_flag_refuses_live_writes(self):
        with patch("sys.argv", ["check"]), patch.object(live, "run") as run:
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                live.main()
            run.assert_not_called()

    def test_other_cluster_refuses_even_with_opt_in(self):
        with patch("sys.argv", ["check", "--accept-live-telemetry"]), \
                patch.object(live, "run", return_value=b"calcifer-home\n"), \
                patch.object(live, "isolation") as isolation, \
                patch.object(live, "acceptance") as acceptance:
            with self.assertRaises(AssertionError):
                live.main()
            isolation.assert_not_called()
            acceptance.assert_not_called()

    def test_network_only_never_reads_credentials_or_writes_telemetry(self):
        with patch("sys.argv", ["check", "--network-only"]), \
                patch.object(live, "run", return_value=b"calcifer-cloud\n"), \
                patch.object(live, "isolation") as isolation, \
                patch.object(live, "acceptance") as acceptance:
            live.main()
            isolation.assert_called_once_with()
            acceptance.assert_not_called()

    def test_opt_in_checks_isolation_then_acceptance(self):
        calls = []
        with patch("sys.argv", ["check", "--accept-live-telemetry"]), \
                patch.object(live, "run", return_value=b"calcifer-cloud\n"), \
                patch.object(live, "isolation", side_effect=lambda: calls.append("isolation")), \
                patch.object(live, "acceptance", side_effect=lambda: calls.append("acceptance")):
            live.main()
            self.assertEqual(calls, ["isolation", "acceptance"])


if __name__ == "__main__":
    unittest.main()