import importlib.util
import io
import json
from pathlib import Path
from unittest import mock
import unittest

spec = importlib.util.spec_from_file_location("ota_deploy", Path(__file__).parents[1] / "scripts" / "ota-deploy.py")
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)


class FakeSocket:
    def __init__(self, incoming):
        self.incoming = bytearray(incoming)
        self.sent = []

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass

    def sendall(self, data):
        self.sent.append(data)

    def recv(self, size):
        data = self.incoming[:size]
        del self.incoming[:size]
        return bytes(data)


class DeployTest(unittest.TestCase):
    def test_boot_failure_release_requires_explicit_dedicated_mode(self):
        with self.assertRaises(deploy.Error):
            deploy.validate_release_mode("rollback-test-2", False)
        with self.assertRaises(deploy.Error):
            deploy.validate_release_mode("rollback-test-3", True)
        deploy.validate_release_mode("rollback-test-2", True)

    def test_mqtt_command_is_non_retained_and_requires_acknowledgement(self):
        fake = FakeSocket(b"\x20\x02\x00\x00\x40\x02\x00\x01")
        secret = b"stringData:\n  username: test-user\n  password: test-password\n"
        with mock.patch.object(deploy.subprocess, "run", return_value=mock.Mock(stdout=secret)), \
             mock.patch.object(deploy.socket, "create_connection", return_value=fake):
            deploy.mqtt_trigger()
        self.assertEqual(fake.sent[1][0], 0x32)
        self.assertIn(b"therment/therment-01/ota/set", fake.sent[1])
        self.assertTrue(fake.sent[1].endswith(b"INSTALL"))

    def test_action_retry_fetches_detail_and_does_not_reassign(self):
        server = mock.Mock()
        server.request.side_effect = [
            {"content": [{"id": 41, "_links": {"self": {}}}], "total": 1},
            {"id": 41, "_links": {"distributionset": {"href": "https://ota.calcifer.tech/rest/v1/distributionsets/8"}}},
        ]
        self.assertEqual(deploy.action_for(server, 8), 41)
        self.assertEqual(server.request.call_count, 2)
        self.assertTrue(all(call.args[0] == "GET" for call in server.request.call_args_list))

    def test_timeout_is_unknown_and_does_not_claim_success(self):
        server = mock.Mock()
        server.request.return_value = {"id": 41, "status": "running"}
        output = io.StringIO()
        with mock.patch.object(deploy.time, "monotonic", side_effect=[0, 2]), \
             mock.patch("sys.stdout", output):
            self.assertEqual(deploy.observe(server, 41, 8, "v1", 1), 2)
        self.assertEqual(json.loads(output.getvalue())["outcome"], "unknown")


if __name__ == "__main__":
    unittest.main()
