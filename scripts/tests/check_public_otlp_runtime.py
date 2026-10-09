#!/usr/bin/env python3
"""Bounded Docker acceptance test; synthetic data, no cluster or real credentials."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
OBSERVABILITY = ROOT / "clusters/calcifer-cloud/apps/observability"
SPEC = importlib.util.spec_from_file_location("otlp_keys", ROOT / "scripts/provision-public-otlp-keys.py")
KEYS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(KEYS)
BODY_MARKER = "synthetic-otlp-body-must-not-be-logged"


def command(arguments: list[str]) -> bytes:
    result = subprocess.run(arguments, capture_output=True)
    if result.returncode:
        stage = arguments[1] if arguments[0] == "docker" else "command"
        categories = (b"not published", b"No such image", b"mount", b"network", b"permission denied")
        category = next((item.decode() for item in categories if item in result.stderr), "unclassified")
        raise RuntimeError(f"{arguments[0]} {stage} failed ({category}); output suppressed")
    return result.stdout + (result.stderr if arguments[:2] == ["docker", "logs"] else b"")


def request(url: str, payload: dict | None = None, token: str | None = None,
            method: str | None = None) -> tuple[int, bytes]:
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    if payload is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=None if payload is None else json.dumps(payload).encode(),
                                 headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=5) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def wait_until(check, timeout: float = 30, stage: str = "test service") -> None:
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        try:
            if check():
                return
        except (OSError, ValueError):
            pass
        time.sleep(0.25)
    raise RuntimeError(f"timed out waiting for {stage}")


def attribute(key: str, value: str | bool) -> dict:
    return {"key": key, "value": {"boolValue" if isinstance(value, bool) else "stringValue": value}}


def resource() -> dict:
    return {"attributes": [attribute("service.name", "public-otlp-test"),
                           attribute("calcifer.ingest.client", "spoofed-client"),
                           attribute("calcifer_ingest_client", "spoofed-normalized")]}


def trace_payload(entries: list[tuple[str, str | bool | None]], resource_keep: bool = False) -> dict:
    spans = []
    now = time.time_ns()
    for trace_id, marker in entries:
        for index in range(2):
            attrs = [attribute("calcifer.ingest.client", "spoofed-span")]
            if marker is not None and index == 0:
                attrs.append(attribute("calcifer.sampling.keep", marker))
            spans.append({"traceId": trace_id, "spanId": secrets.token_hex(8),
                          "name": f"span-{index}", "kind": 1,
                          "startTimeUnixNano": str(now), "endTimeUnixNano": str(now + 1_000_000),
                          "attributes": attrs})
    res = resource()
    if resource_keep:
        res["attributes"].append(attribute("calcifer.sampling.keep", True))
    return {"resourceSpans": [{"resource": res, "scopeSpans": [{"spans": spans}]}]}


class Sandbox:
    def __init__(self):
        self.network = "otlp-check-" + uuid.uuid4().hex[:12]
        self.containers = []

    def start(self, image: str, port: int, alias: str, args: list[str],
              mounts: list[str] | None = None, memory: str = "256m") -> str:
        cid = command(["docker", "run", "-d", "--network", self.network,
                       "--network-alias", alias, "--read-only", "--tmpfs", "/tmp",
                       "--memory", memory, "-p", f"127.0.0.1::{port}",
                       *(mounts or []), image, *args]).decode().strip()
        self.containers.append(cid)
        print("STARTED:", alias, flush=True)
        state = json.loads(command(["docker", "inspect", cid, "--format", "{{json .State}} "]))
        if not state["Running"]:
            output = command(["docker", "logs", cid])
            category = next((word for word in ("flag", "permission", "retention", "memory")
                             if word.encode() in output.lower()), "unclassified")
            raise RuntimeError(f"test backend stopped (exit {state['ExitCode']}, category {category})")
        published = command(["docker", "port", cid, f"{port}/tcp"]).decode().strip()
        return "http://" + published

    def close(self):
        for cid in reversed(self.containers):
            subprocess.run(["docker", "rm", "-f", cid], capture_output=True)
        subprocess.run(["docker", "network", "rm", self.network], capture_output=True)


def main() -> None:
    sandbox = Sandbox()
    # A dedicated bridge permits loopback-only published query ports. Docker's
    # --internal bridge does not publish these ports on some daemon versions.
    command(["docker", "network", "create", sandbox.network])
    tokens = (secrets.token_urlsafe(32), secrets.token_urlsafe(32))
    try:
        with tempfile.TemporaryDirectory(prefix="calcifer-otlp-check-") as temporary:
            auth_path = Path(temporary) / "auth.yaml"
            auth_path.touch(mode=0o600)
            config = KEYS.auth_config(tokens)
            auth_path.write_text(json.dumps(config))
            traces = sandbox.start("victoriametrics/victoria-traces:v0.11.0", 10428,
                                   "victoria-traces.monitoring.svc.cluster.local",
                                   ["-storageDataPath=/tmp/data", "-retentionPeriod=6M", "-loggerLevel=WARN"])
            logs = sandbox.start("victoriametrics/victoria-logs:v1.52.0", 9428,
                                 "victoria-logs.monitoring.svc.cluster.local",
                                 ["-storageDataPath=/tmp/data", "-retentionPeriod=6M", "-loggerLevel=WARN"])
            metrics = sandbox.start("victoriametrics/victoria-metrics:v1.151.0", 8428,
                                    "victoria-metrics.monitoring.svc.cluster.local",
                                    ["-storageDataPath=/tmp/data", "-retentionPeriod=365d", "-loggerLevel=WARN",
                                     "-search.latencyOffset=0s"])
            collector = sandbox.start("grafana/alloy:v1.19.2", 12345,
                                      "alloy-otlp-gateway.monitoring.svc.cluster.local",
                                      ["run", "/etc/alloy/config.alloy", "--storage.path=/tmp/alloy",
                                       "--server.http.listen-addr=0.0.0.0:12345", "--disable-reporting"],
                                      ["-v", f"{OBSERVABILITY / 'otlp-gateway.alloy'}:/etc/alloy/config.alloy:ro",
                                       "--user", "10001:10001"], "512m")
            gateway = sandbox.start("victoriametrics/vmauth:v1.153.0", 8427, "otlp-vmauth",
                                    ["-auth.config=/etc/vmauth/auth.yaml", "-httpListenAddr=:8427",
                                     "-httpInternalListenAddr=:8428", "-configCheckInterval=1s",
                                     "-logInvalidAuthTokens=false", "-loggerLevel=WARN"],
                                    ["-v", f"{auth_path}:/etc/vmauth/auth.yaml:ro"], "128m")
            for backend in (traces, logs, metrics):
                wait_until(lambda backend=backend: request(backend + "/health")[0] == 200)
            wait_until(lambda: request(collector + "/-/ready")[0] == 200)
            wait_until(lambda: request(gateway + "/v1/logs", {}, tokens[0])[0] == 200)

            def ingest(signal: str, payload: dict, token: str) -> None:
                status, body = request(gateway + "/v1/" + signal, payload, token)
                assert status == 200, "valid ingestion failed"
                assert all(key.encode() not in body for key in tokens), "credential response disclosure"

            for signal in ("traces", "logs", "metrics"):
                for token in (None, "synthetic-invalid-key"):
                    status, body = request(gateway + "/v1/" + signal, {}, token)
                    assert status == 401, "missing/invalid credential accepted"
                    assert all(key.encode() not in body for key in tokens), "credential response disclosure"
                for token in tokens:
                    ingest(signal, {}, token)
            for path in ("/metrics", "/-/reload", "/health", "/api/v1/query", "/v1/logs/extra"):
                assert request(gateway + path, {}, tokens[0])[0] >= 400, "non-ingestion route exposed"
            assert request(gateway + "/v1/logs", token=tokens[0], method="GET")[0] == 405, "GET accepted"
            print("PASS: both aliases/all signals authenticate; invalid credentials and private paths rejected")

            now = str(time.time_ns())
            for token in tokens:
                ingest("logs", {"resourceLogs": [{"resource": resource(), "scopeLogs": [{"logRecords": [{
                    "timeUnixNano": now, "body": {"stringValue": BODY_MARKER},
                    "attributes": [attribute("calcifer.ingest.client", "spoofed-log")],
                }]}]}]}, token)
                ingest("metrics", {"resourceMetrics": [{"resource": resource(), "scopeMetrics": [{"metrics": [{
                    "name": "otlp_identity_test", "gauge": {"dataPoints": [{"timeUnixNano": now, "asDouble": 7,
                    "attributes": [attribute("calcifer.ingest.client", "spoofed-point"),
                                   attribute("calcifer_ingest_client", "spoofed-normalized-point")]}]},
                }, {"name": "otlp_cumulative_test", "sum": {"aggregationTemporality": 2,
                    "isMonotonic": True, "dataPoints": [{"startTimeUnixNano": str(int(now) - 1_000_000),
                    "timeUnixNano": now, "asDouble": 9}]} }]}]}]}, token)

            def metric_series(name: str) -> list:
                status, body = request(metrics + "/api/v1/query?" + urllib.parse.urlencode({"query": name, "nocache": 1}))
                assert status == 200, "metric query failed"
                return json.loads(body)["data"]["result"]

            wait_until(lambda: len(metric_series("otlp_identity_test")) == 2, 20, "gauge metric storage")
            aliases = {row["metric"].get("calcifer.ingest.client", row["metric"].get("calcifer_ingest_client"))
                       for row in metric_series("otlp_identity_test")}
            assert aliases == set(KEYS.ALIASES), "metric identity spoofed/lost"
            wait_until(lambda: len(metric_series("otlp_cumulative_test")) == 2, 20, "cumulative metric storage")

            log_query = logs + "/select/logsql/query?" + urllib.parse.urlencode({"query": BODY_MARKER})
            def log_rows() -> list:
                status, body = request(log_query)
                assert status == 200, "log query failed"
                return [json.loads(line) for line in body.splitlines() if line]
            wait_until(lambda: len(log_rows()) == 2, 20, "log storage")
            assert {row.get("calcifer.ingest.client") for row in log_rows()} == set(KEYS.ALIASES), "log identity spoofed/lost"
            print("PASS: logs and gauge/cumulative metrics unsampled; stored identities override spoofing")

            marked = [secrets.token_hex(16) for _ in range(20)]
            resource_marked = [secrets.token_hex(16) for _ in range(20)]
            groups = {"absent": None, "false": False, "string": "true"}
            unmarked = {name: [secrets.token_hex(16) for _ in range(100)] for name in groups}
            shared_ids = [secrets.token_hex(16) for _ in range(100)]
            ingest("traces", trace_payload([(tid, True) for tid in marked + shared_ids]), tokens[0])
            ingest("traces", trace_payload([(tid, None) for tid in resource_marked], True), tokens[1])
            ingest("traces", trace_payload([(tid, None) for tid in shared_ids]), tokens[1])
            for name, marker in groups.items():
                ingest("traces", trace_payload([(tid, marker) for tid in unmarked[name]]), tokens[0])

            trace_query = traces + "/select/jaeger/api/traces?" + urllib.parse.urlencode({
                "service": "public-otlp-test", "limit": 1000,
                "start": time.time_ns() // 1000 - 120_000_000, "end": time.time_ns() // 1000 + 120_000_000,
            })
            def trace_rows() -> dict:
                status, body = request(trace_query)
                assert status == 200, "trace query failed"
                return {row["traceID"]: row for row in json.loads(body).get("data", [])}
            wait_until(lambda: set(marked + resource_marked + shared_ids) <= set(trace_rows()), 50, "force-kept trace storage")
            rows = trace_rows()
            for tid in marked + resource_marked:
                assert len(rows[tid]["spans"]) == 2, "force keep failed to retain the whole trace"
            for name, tids in unmarked.items():
                retained = len(set(tids) & set(rows))
                assert 25 <= retained <= 75, "unmarked/false/string sampling inconsistent with 50 percent"
            for row in rows.values():
                identities = {tag["value"] for proc in row["processes"].values() for tag in proc.get("tags", [])
                              if tag["key"] == "calcifer.ingest.client"}
                assert identities <= set(KEYS.ALIASES) and identities, "trace resource identity spoofed/lost"
                assert not any(tag["key"] == "calcifer.ingest.client" for span in row["spans"]
                               for tag in span.get("tags", [])), "spoofed span identity survived"
            github_kept = sum(any(proc.get("tags") and any(tag["key"] == "calcifer.ingest.client" and
                              tag["value"] == KEYS.ALIASES[1] for tag in proc["tags"])
                              for proc in rows[tid]["processes"].values()) for tid in shared_ids)
            assert 25 <= github_kept <= 75, "cross-client trace decisions contaminated"
            print("PASS: span/resource boolean keep, whole traces, absent/false/string 50%, cross-client decisions")

            # Rewrite the same mounted inode: mimic Secret projection/reload without key rotation.
            config["users"] = config["users"][:1]
            auth_path.write_text(json.dumps(config))
            wait_until(lambda: request(gateway + "/v1/logs", {}, tokens[1])[0] == 401, 10)
            ingest("logs", {}, tokens[0])
            print("PASS: revoking GitHub key reloads independently; local key continues working")

            for cid in sandbox.containers:
                output = command(["docker", "logs", cid])
                assert all(token.encode() not in output for token in tokens), "credential appeared in logs"
                assert BODY_MARKER.encode() not in output, "payload appeared in logs"
            print("PASS: test container logs contain neither credentials nor synthetic payload")
    finally:
        sandbox.close()


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError, AssertionError, KeyError) as error:
        # Only fixed diagnostic messages are used above; never show HTTP bodies/container logs.
        raise SystemExit(f"FAIL: {error}") from None