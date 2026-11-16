#!/usr/bin/env python3
"""Build and publish one private Therment release and watch its action."""

import argparse
import base64
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

import yaml

THERMENT = Path(__file__).resolve().parents[1]
ROOT = THERMENT.parent
DEVICE = "therment-01"
SERVER = "https://ota.calcifer.tech"
BOOT_FAILURE_TEST_VERSION = "rollback-test-2"
MQTT_SECRET = ROOT / "clusters/calcifer-home/apps/home-automation/mqtt/ota-publisher-credentials.sops.yaml"
status_spec = importlib.util.spec_from_file_location("ota_status", THERMENT / "scripts/ota-status.py")
status_module = importlib.util.module_from_spec(status_spec)
status_spec.loader.exec_module(status_module)
Error = status_module.StatusError


def event(phase, outcome="pending", **fields):
    print(json.dumps({"device": DEVICE, "phase": phase, "outcome": outcome, **fields}, separators=(",", ":")), flush=True)


class Server:
    def __init__(self, username, password):
        self.authorization = "Basic " + base64.b64encode(f"{username}:{password}".encode()).decode()
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), status_module.NoRedirect())

    def request(self, method, path, body=None, content_type="application/json"):
        headers = {"Authorization": self.authorization, "Accept": "application/json"}
        if body is not None:
            if not isinstance(body, bytes):
                body = json.dumps(body, separators=(",", ":")).encode()
            headers["Content-Type"] = content_type
        request = urllib.request.Request(SERVER + path, data=body, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=30) as response:
                data = response.read(1024 * 1024 + 1)
                if len(data) > 1024 * 1024:
                    raise Error("Server response exceeded the allowed size")
                return json.loads(data) if data else None
        except urllib.error.HTTPError as exc:
            raise Error(f"Update service returned HTTP {exc.code}") from exc
        except (OSError, ValueError) as exc:
            raise Error("Update service request failed") from exc

    def find(self, resource, name, version):
        query = urllib.parse.urlencode({"q": f"name=={name};version=={version}", "limit": 100})
        result = self.request("GET", f"/rest/v1/{resource}?{query}")
        matches = [x for x in result.get("content", []) if x.get("name") == name and x.get("version") == version]
        if len(matches) > 1:
            raise Error("Multiple releases match this version")
        return matches[0] if matches else None


def validate_release_mode(version, boot_failure_test):
    if boot_failure_test and version != BOOT_FAILURE_TEST_VERSION:
        raise Error("Boot-failure testing is restricted to its dedicated release version")
    if version == BOOT_FAILURE_TEST_VERSION and not boot_failure_test:
        raise Error("The dedicated boot-failure release requires --boot-failure-test")


def build(version, directory, boot_failure_test=False):
    validate_release_mode(version, boot_failure_test)
    target = THERMENT / "target/ota-release"
    target.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(target, 0o700)
    with (target / ".build.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise Error("Another OTA build is already running") from exc
        return build_in_target(version, directory, target, boot_failure_test)


def build_in_target(version, directory, target, boot_failure_test=False):
    defaults = target / "sdkconfig.generated.defaults"
    partition = str((THERMENT / "partitions-ota.csv").resolve())
    if any(c in partition for c in ('"', "\n", "\r")):
        raise Error("Unsupported partition table path")
    defaults_content = (THERMENT / "sdkconfig.ota.defaults").read_text() + f'\nCONFIG_PARTITION_TABLE_CUSTOM_FILENAME="{partition}"\n'
    fingerprint = hashlib.sha256(defaults_content.encode()).hexdigest()
    marker = target / "sdkconfig.fingerprint"
    if not marker.exists() or marker.read_text().strip() != fingerprint:
        # ESP-IDF keeps a generated sdkconfig and does not reapply changed
        # defaults to it. Remove only this disposable generated config.
        for config in target.glob("xtensa-esp32-espidf/release/build/esp-idf-sys-*/out/sdkconfig"):
            config.unlink()
    if not defaults.exists() or defaults.read_text() != defaults_content:
        defaults.write_text(defaults_content)
    os.chmod(defaults, 0o600)
    env = os.environ.copy()
    env.update(THERMENT_RELEASE_VERSION=version, CARGO_TARGET_DIR=str(target), ESP_IDF_SDKCONFIG_DEFAULTS=str(defaults))
    if boot_failure_test:
        env["THERMENT_ALLOW_BOOT_FAILURE"] = "1"
    else:
        env.pop("THERMENT_ALLOW_BOOT_FAILURE", None)
    version_marker = target / "release-version"
    version_marker.unlink(missing_ok=True)
    try:
        feature = "ota-test-boot-failure" if boot_failure_test else "ota"
        subprocess.run(["cargo", "build", "--release", "--locked", "--features", feature], cwd=THERMENT, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
        configs = list(target.glob("xtensa-esp32-espidf/release/build/esp-idf-sys-*/out/sdkconfig"))
        required = {
            "CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE=y",
            "CONFIG_ESP_TASK_WDT_PANIC=y",
            "CONFIG_ESPTOOLPY_FLASHSIZE_4MB=y",
            f'CONFIG_PARTITION_TABLE_CUSTOM_FILENAME="{partition}"',
        }
        if len(configs) != 1 or not required.issubset(set(configs[0].read_text().splitlines())):
            raise Error("Generated ESP-IDF configuration does not match the OTA profile")
        marker.write_text(fingerprint + "\n")
        os.chmod(marker, 0o600)
        image = Path(directory) / "firmware.bin"
        elf = target / "xtensa-esp32-espidf/release/therment"
        subprocess.run(["espflash", "save-image", "--chip", "esp32", str(elf), str(image)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    except (OSError, subprocess.CalledProcessError) as exc:
        raise Error("Firmware build failed; check the ESP toolchain and OTA configuration") from exc
    if not image.is_file() or not 0 < image.stat().st_size <= 0x1E0000:
        raise Error("Firmware image does not fit the OTA slot")
    version_marker.write_text(version + "\n")
    os.chmod(version_marker, 0o600)
    return image


def module_for(server, version, image):
    name = DEVICE
    digest = hashlib.sha256(image).hexdigest()
    module = server.find("softwaremodules", name, version)
    if module is None:
        created = server.request("POST", "/rest/v1/softwaremodules", [{"name": name, "version": version, "type": "os"}])
        module = created[0]
    module_id = int(module["id"])
    artifacts = server.request("GET", f"/rest/v1/softwaremodules/{module_id}/artifacts")
    if artifacts:
        if len(artifacts) != 1 or artifacts[0].get("hashes", {}).get("sha256", "").lower() != digest:
            raise Error("This version already exists with different firmware")
    else:
        boundary = "therment-" + os.urandom(16).hex()
        header = f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="therment.bin"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode()
        body = header + image + f"\r\n--{boundary}--\r\n".encode()
        server.request("POST", f"/rest/v1/softwaremodules/{module_id}/artifacts", body, f"multipart/form-data; boundary={boundary}")
    return module_id


def distribution_for(server, version, module_id):
    distribution = server.find("distributionsets", DEVICE, version)
    if distribution is None:
        distribution = server.request("POST", "/rest/v1/distributionsets", [{"name": DEVICE, "version": version, "type": "os"}])[0]
    distribution_id = int(distribution["id"])
    modules = server.request("GET", f"/rest/v1/distributionsets/{distribution_id}/assignedSM?limit=100")
    ids = [int(x["id"]) for x in modules.get("content", [])]
    if not ids:
        server.request("POST", f"/rest/v1/distributionsets/{distribution_id}/assignedSM", [{"id": module_id}])
    elif ids != [module_id]:
        raise Error("This version is attached to a different software module")
    return distribution_id


def action_for(server, distribution_id):
    offset = 0
    while True:
        result = server.request("GET", f"/rest/v1/targets/{DEVICE}/actions?limit=100&offset={offset}&sort=id:DESC")
        items = result.get("content", [])
        for item in items:
            action_id = int(item["id"])
            action = server.request("GET", f"/rest/v1/targets/{DEVICE}/actions/{action_id}")
            href = action.get("_links", {}).get("distributionset", {}).get("href", "")
            if urllib.parse.urlsplit(href).path == f"/rest/v1/distributionsets/{distribution_id}":
                return action_id
        offset += len(items)
        if not items or offset >= int(result.get("total", offset)):
            break
    result = server.request("POST", f"/rest/v1/distributionsets/{distribution_id}/assignedTargets", [{"id": DEVICE, "type": "forced"}])
    actions = result.get("assignedActions", [])
    if len(actions) != 1:
        raise Error("Assignment did not return exactly one action; inspect server before retrying")
    return int(actions[0]["id"])


def mqtt_field(value):
    data = value.encode()
    if len(data) > 65535:
        raise Error("MQTT credential is too long")
    return len(data).to_bytes(2, "big") + data


def mqtt_packet(kind, body):
    n = len(body)
    encoded = bytearray()
    while True:
        byte = n % 128
        n //= 128
        encoded.append(byte | (128 if n else 0))
        if not n:
            return bytes([kind]) + encoded + body


def read_exact(sock, size):
    result = bytearray()
    while len(result) < size:
        chunk = sock.recv(size - len(result))
        if not chunk:
            raise Error("MQTT connection closed")
        result.extend(chunk)
    return bytes(result)


def mqtt_trigger():
    try:
        decrypted = subprocess.run(["sops", "-d", str(MQTT_SECRET)], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=True)
        secret = yaml.safe_load(decrypted.stdout)["stringData"]
        client_id = "therment-release-" + os.urandom(6).hex()
        connect = b"\x00\x04MQTT\x04\xc2\x00\x1e" + mqtt_field(client_id) + mqtt_field(secret["username"]) + mqtt_field(secret["password"])
        with socket.create_connection(("192.168.0.102", 1883), timeout=10) as sock:
            sock.sendall(mqtt_packet(0x10, connect))
            if read_exact(sock, 4) != b"\x20\x02\x00\x00":
                raise Error("MQTT authentication failed")
            body = mqtt_field("therment/therment-01/ota/set") + b"\x00\x01INSTALL"
            sock.sendall(mqtt_packet(0x32, body))  # QoS 1, retained flag clear
            if read_exact(sock, 4) != b"\x40\x02\x00\x01":
                raise Error("MQTT trigger was not acknowledged")
            sock.sendall(b"\xe0\x00")
    except (OSError, subprocess.CalledProcessError, yaml.YAMLError, KeyError) as exc:
        raise Error("MQTT trigger failed") from exc


def observe(server, action_id, distribution_id, version, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        action = server.request("GET", f"/rest/v1/targets/{DEVICE}/actions/{action_id}")
        if str(action.get("id")) != str(action_id):
            raise Error("Server returned a different action")
        installed = server.request("GET", f"/rest/v1/targets/{DEVICE}/installedDS") if str(action.get("status", "")).lower() == "finished" else {}
        outcome = status_module.classify(action, installed)
        if outcome == "success" and (int(installed.get("id", -1)) != distribution_id or installed.get("version") != version):
            outcome = "unknown"
        event(str(action.get("status", "unknown")), outcome, action_id=action_id, release=version)
        if outcome == "success":
            return 0
        if outcome == "failure":
            return 2
        time.sleep(min(10, max(0, deadline - time.monotonic())))
    event("timeout", "unknown", action_id=action_id, release=version)
    return 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version", help="Unique firmware release version")
    parser.add_argument("--timeout", type=int, default=1800)
    parser.add_argument("--boot-failure-test", action="store_true", help="Build the dedicated rollback-test-2 image that intentionally fails to confirm startup")
    args = parser.parse_args()
    try:
        version = status_module.safe_segment(args.version)
        if len(version) > 64 or args.timeout <= 0:
            raise Error("Invalid release version or timeout")
        validate_release_mode(version, args.boot_failure_test)
        os.umask(0o077)
        username, password = status_module.read_credentials(status_module.DEFAULT_CREDENTIALS)
        server = Server(username, password)
        with tempfile.TemporaryDirectory(prefix="therment-release-", dir="/dev/shm") as directory:
            event("building", release=version)
            image = build(version, directory, args.boot_failure_test).read_bytes()
            event("uploading", release=version, size=len(image), sha256=hashlib.sha256(image).hexdigest())
            module = module_for(server, version, image)
            distribution = distribution_for(server, version, module)
            action = action_for(server, distribution)
            event("assigned", release=version, action_id=action)
            try:
                mqtt_trigger()
                event("triggered", release=version, action_id=action)
            except Error:
                event("trigger_failed", "unknown", release=version, action_id=action)
            return observe(server, action, distribution, version, args.timeout)
    except (Error, OSError, ValueError, KeyError, TypeError, IndexError) as exc:
        message = str(exc) if isinstance(exc, Error) else "Deployment failed; inspect the service state before retrying"
        event("error", "unknown", error=message)
        return 2


if __name__ == "__main__":
    sys.exit(main())
