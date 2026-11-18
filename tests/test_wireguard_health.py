import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[1]
HOME = ROOT / "clusters/calcifer-home/infrastructure/private-transit"
CLOUD = ROOT / "clusters/calcifer-cloud/apps/observability"
SCRIPT = HOME / "wireguard-healthcheck.sh"
IDENTITY = "PEER_IDENTITY_MUST_NOT_BE_LOGGED"


class WireGuardHealthTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.state = self.directory / "state"
        stubs = {
            "date": 'printf "%s\\n" "$MOCK_NOW"',
            "cut": 'printf "%s\\n" "$MOCK_UPTIME"',
            "wg": 'printf "%s" "$MOCK_WG_OUTPUT"; exit "$MOCK_WG_EXIT"',
        }
        for name, body in stubs.items():
            path = self.directory / name
            path.write_text("#!/bin/sh\n" + body + "\n")
            path.chmod(0o700)
        self.assertEqual(self.run_health("init", uptime=1000).returncode, 0)

    def run_health(self, mode, handshake=None, now=10600, uptime=1600, wg_exit=0):
        env = dict(os.environ)
        env.update(
            PATH=str(self.directory) + os.pathsep + env.get("PATH", "/usr/bin:/bin"),
            WG_HEALTH_STATE_DIR=str(self.state),
            MOCK_NOW=str(now),
            MOCK_UPTIME=str(uptime),
            MOCK_WG_OUTPUT=handshake if handshake is not None else f"{IDENTITY}\t{now - 1}\n",
            MOCK_WG_EXIT=str(wg_exit),
        )
        result = subprocess.run(
            ["/bin/sh", str(SCRIPT), mode], env=env,
            capture_output=True, text=True, timeout=3,
        )
        self.assertNotIn(IDENTITY, result.stdout + result.stderr)
        return result

    def test_fresh_idle_handshake_needs_no_application_probe(self):
        for mode in ("ready", "live"):
            self.assertEqual(self.run_health(mode).returncode, 0)
        self.assertFalse((self.state / "last-recovery").exists())

    def test_readiness_threshold(self):
        for age, expected in ((180, 0), (181, 1)):
            with self.subTest(age=age):
                result = self.run_health("ready", f"{IDENTITY}\t{10600 - age}\n")
                self.assertEqual(result.returncode, expected)
        self.assertFalse((self.state / "last-recovery").exists())

    def test_liveness_threshold_records_recovery_before_failure(self):
        self.assertEqual(self.run_health("live", f"{IDENTITY}\t10300\n").returncode, 0)
        result = self.run_health("live", f"{IDENTITY}\t10299\n")
        self.assertEqual(result.returncode, 1)
        self.assertIn("action=restart-requested", result.stdout)
        self.assertEqual((self.state / "last-recovery").read_text().strip(), "1600")

    def test_missing_handshake_has_five_minute_startup_grace(self):
        missing = f"{IDENTITY}\t0\n"
        self.assertEqual(self.run_health("ready", missing, uptime=1001).returncode, 1)
        self.assertEqual(self.run_health("live", missing, uptime=1299).returncode, 0)
        self.assertEqual(self.run_health("live", missing, uptime=1300).returncode, 1)

    def test_malformed_failed_and_multiple_peer_results_are_unhealthy(self):
        outputs = ("", "garbage", f"{IDENTITY}\tnan\n", f"{IDENTITY}\t-1\n",
                   f"{IDENTITY}\t0\n", f"{IDENTITY}\t10599\nother\t10599\n")
        for output in outputs:
            with self.subTest(output=output):
                self.assertEqual(self.run_health("ready", output).returncode, 1)
        self.assertEqual(self.run_health("ready", wg_exit=1).returncode, 1)

    def test_missing_interface_requests_recovery_after_grace(self):
        self.assertEqual(self.run_health("live", "", wg_exit=1).returncode, 1)

    def test_cooldown_survives_container_init_but_readiness_stays_false(self):
        missing = f"{IDENTITY}\t0\n"
        self.assertEqual(self.run_health("live", missing).returncode, 1)
        self.assertEqual(self.run_health("init", uptime=1602).returncode, 0)
        self.assertEqual((self.state / "last-recovery").read_text().strip(), "1600")
        self.assertEqual(self.run_health("live", missing, uptime=3399).returncode, 0)
        self.assertEqual(self.run_health("ready", missing, uptime=3399).returncode, 1)
        self.assertEqual(self.run_health("live", missing, uptime=3400).returncode, 1)
        self.assertEqual((self.state / "last-recovery").read_text().strip(), "3400")

    def test_success_during_cooldown_does_not_reset_retry_budget(self):
        self.assertEqual(self.run_health("live", "").returncode, 1)
        self.assertEqual(self.run_health("ready", uptime=1700).returncode, 0)
        self.assertEqual(self.run_health("live", "", uptime=1800).returncode, 0)
        self.assertEqual((self.state / "last-recovery").read_text().strip(), "1600")

    def test_clock_adjustments_do_not_bypass_monotonic_cooldown(self):
        self.assertEqual(self.run_health("live", "").returncode, 1)
        for now in (1, 999999999):
            with self.subTest(now=now):
                self.assertEqual(self.run_health("live", "", now=now, uptime=1700).returncode, 0)
        self.assertEqual(self.run_health("ready", f"{IDENTITY}\t10601\n").returncode, 1)

    def test_unknown_mode_is_rejected(self):
        self.assertEqual(self.run_health("other").returncode, 2)


class WireGuardManifestTest(unittest.TestCase):
    def test_home_wires_script_and_preserves_recovery_state(self):
        manifest = yaml.safe_load((HOME / "wireguard-daemonset.yaml").read_text())
        pod = manifest["spec"]["template"]["spec"]
        container = pod["containers"][0]
        for mode, name in (("ready", "readinessProbe"), ("live", "livenessProbe")):
            self.assertEqual(container[name]["exec"]["command"],
                             ["/bin/sh", "/health/healthcheck.sh", mode])
            self.assertEqual(container[name]["failureThreshold"], 1)
        self.assertLess(container["args"][0].index("healthcheck.sh init"),
                        container["args"][0].index("wg-quick up"))
        volumes = {volume["name"]: volume for volume in pod["volumes"]}
        self.assertEqual(volumes["run"]["emptyDir"]["medium"], "Memory")
        self.assertEqual(volumes["health"]["configMap"]["name"], "home-edge-wireguard-health")
        kustomization = yaml.safe_load((HOME / "kustomization.yaml").read_text())
        generator = kustomization["configMapGenerator"][0]
        self.assertEqual(generator["files"], ["healthcheck.sh=wireguard-healthcheck.sh"])

    def test_cloud_probe_is_local_tls_verified_and_matches_alert(self):
        releases = list(yaml.safe_load_all((CLOUD / "helmreleases.yaml").read_text()))
        alloy = next(item for item in releases if item["metadata"]["name"] == "alloy")
        config = alloy["spec"]["values"]["alloy"]["configMap"]["content"]
        block = config.split('prometheus.exporter.blackbox "private_transit"', 1)[1]
        module = yaml.safe_load(re.search(r"config = `(.*?)`", block, re.S).group(1))
        http = module["modules"]["home_edge_https"]["http"]
        self.assertEqual(http["headers"]["Host"], "home.calcifer.tech")
        self.assertEqual(http["tls_config"], {"server_name": "home.calcifer.tech"})
        self.assertFalse(http["follow_redirects"])
        self.assertIn('address = "https://172.31.255.2/"', block)
        target_name = re.search(r'name = "([^"]+)"', block).group(1)
        self.assertIn('cluster = "calcifer-cloud"', config)
        alert = yaml.safe_load((CLOUD / "grafana/private-transit-alerts.yaml").read_text())
        rule = alert["spec"]["rules"][0]
        self.assertEqual(rule["for"], "5m")
        self.assertEqual(rule["condition"], "B")
        expr = rule["data"][0]["model"]["expr"]
        self.assertIn('cluster="calcifer-cloud"', expr)
        self.assertIn('probe_name="cloud-home-edge-https"', expr)
        self.assertIn(f'job="{target_name}"', expr)
        self.assertIn("time() - timestamp(", expr)
        self.assertIn("< 90", expr)
        self.assertTrue(expr.endswith("or vector(0)"))
        threshold = rule["data"][1]["model"]
        self.assertEqual(threshold["conditions"][0]["evaluator"], {"type": "lt", "params": [1]})
        self.assertEqual(threshold["expression"], "A")
        resources = yaml.safe_load((CLOUD / "grafana/kustomization.yaml").read_text())["resources"]
        self.assertIn("private-transit-alerts.yaml", resources)


if __name__ == "__main__":
    unittest.main()