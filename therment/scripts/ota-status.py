#!/usr/bin/env python3
"""Read a hawkBit action without printing credentials or claiming early success."""

import argparse
import base64
import json
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CREDENTIALS = ROOT / "clusters/calcifer-home/apps/home-automation/hawkbit/hawkbit-credentials.sops.yaml"


class StatusError(Exception):
    """An operational error that contains no sensitive server response."""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise StatusError("The update service returned a redirect; request refused")


def safe_segment(value):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", value):
        raise StatusError("Invalid device or action identifier")
    return value


def read_credentials(path):
    try:
        result = subprocess.run(
            ["sops", "--decrypt", str(path)],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=True,
        )
        document = yaml.safe_load(result.stdout)
        values = document["stringData"]
        return values["upload-username"], values["upload-password"]
    except (OSError, subprocess.CalledProcessError, yaml.YAMLError, KeyError, TypeError) as exc:
        raise StatusError("Could not decrypt the upload credentials") from exc


class Hawkbit:
    def __init__(self, base, username, password):
        try:
            url = urllib.parse.urlsplit(base)
            allowed = url.scheme == "https" and url.hostname == "ota.calcifer.tech" and url.port in (None, 443)
        except ValueError:
            raise StatusError("Update service must use the trusted Home HTTPS origin") from None
        if not allowed or url.username or url.password or url.query or url.fragment or url.path not in ("", "/"):
            raise StatusError("Update service must use the trusted Home HTTPS origin")
        self.base = base.rstrip("/")
        self.auth = "Basic " + base64.b64encode(f"{username}:{password}".encode()).decode()
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def get(self, path):
        req = urllib.request.Request(self.base + path, headers={"Authorization": self.auth, "Accept": "application/json"})
        try:
            with self.opener.open(req, timeout=15) as response:
                return json.load(response)
        except urllib.error.HTTPError as exc:
            raise StatusError(f"Update service request failed with HTTP {exc.code}") from exc
        except (OSError, ValueError) as exc:
            raise StatusError("Update service request failed") from exc


def classify(action, installed):
    state = str(action.get("status", "")).lower()
    dist_link = action.get("_links", {}).get("distributionset", {}).get("href", "")
    match = re.search(r"/distributionsets/(\d+)$", urllib.parse.urlsplit(dist_link).path)
    if state == "finished":
        if match and str(installed.get("id")) == match.group(1):
            return "success"
        return "unknown"
    if state in {"error", "canceled", "cancelled", "rejected"}:
        return "failure"
    if state in {"running", "scheduled", "pending", "retrieved", "download", "downloaded"}:
        return "pending"
    return "unknown"


def main():
    parser = argparse.ArgumentParser(description="Inspect one Therment OTA action as JSON")
    parser.add_argument("action_id", help="hawkBit action ID")
    parser.add_argument("--device", default="therment-01")
    parser.add_argument("--server", default="https://ota.calcifer.tech")
    parser.add_argument("--credentials", type=Path, default=DEFAULT_CREDENTIALS)
    args = parser.parse_args()
    try:
        device = safe_segment(args.device)
        action_id = safe_segment(args.action_id)
        if not action_id.isdecimal():
            raise StatusError("Action ID must be numeric")
        username, password = read_credentials(args.credentials)
        client = Hawkbit(args.server, username, password)
        action = client.get(f"/rest/v1/targets/{device}/actions/{action_id}")
        if str(action.get("id")) != action_id:
            raise StatusError("Update service returned a different action")
        # An assigned release is not necessarily the running release. Fetch the
        # installed distribution only for a completed action.
        installed = client.get(f"/rest/v1/targets/{device}/installedDS") if action.get("status") == "finished" else {}
        outcome = classify(action, installed)
        result = {"device": device, "action_id": action_id, "phase": action.get("status", "unknown"), "outcome": outcome}
        for field in ("createdAt", "lastModifiedAt"):
            if isinstance(action.get(field), int):
                result[field] = action[field]
        if installed.get("version"):
            result["installed_version"] = installed["version"]
        print(json.dumps(result, separators=(",", ":")), flush=True)
        return 0 if outcome == "success" else 2
    except StatusError as exc:
        print(json.dumps({"device": args.device, "action_id": args.action_id, "outcome": "unknown", "error": str(exc)}, separators=(",", ":")), flush=True)
        return 2


if __name__ == "__main__":
    sys.exit(main())
