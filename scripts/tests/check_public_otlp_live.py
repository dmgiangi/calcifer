#!/usr/bin/env python3
"""Cloud-only acceptance; opt in to tiny production writes, never print secrets."""

import argparse
import json
import secrets
import socket
import subprocess
import time
import urllib.parse
from contextlib import ExitStack, contextmanager

from check_public_otlp_runtime import KEYS, attribute, request, resource, trace_payload, wait_until

ENDPOINT = "https://otlp.calcifer.tech"


def run(arguments):
    result = subprocess.run(arguments, capture_output=True, timeout=30)
    assert result.returncode == 0, "command failed; sensitive output suppressed"
    return result.stdout


def isolation():
    pods = json.loads(run(["kubectl", "get", "pod", "-n", "kube-system", "-l",
                           "app.kubernetes.io/name=traefik", "-o", "json"]))["items"]
    pod = next(item["metadata"]["name"] for item in pods if item["status"]["phase"] == "Running")

    def probe(host, port):
        result = subprocess.run([
            "kubectl", "exec", "-n", "kube-system", pod, "--", "wget", "-q", "-T", "3",
            "-O", "/dev/null", f"http://{host}.monitoring.svc.cluster.local:{port}/v1/logs",
        ], capture_output=True, timeout=15)
        return result.returncode, result.stderr.lower()

    _, output = probe("otlp-vmauth", 8427)
    assert b"401" in output, "Traefik authentication-service control probe failed"
    for host, port in [("alloy-otlp-gateway", 4318), ("alloy-otlp-gateway", 4319),
                       ("otlp-vmauth-internal", 8428), ("alloy-otlp-gateway-internal", 12345)]:
        code, output = probe(host, port)
        assert code and any(word in output for word in (b"timed out", b"refused")), "Traefik bypass not denied"

    # Positive controls from an authorized scraper distinguish denial from dead services.
    for host, port, path in [("otlp-vmauth-internal", 8428, "/health"),
                             ("alloy-otlp-gateway-internal", 12345, "/-/ready")]:
        script = (f"exec 3<>/dev/tcp/{host}.monitoring.svc.cluster.local/{port}; "
                  f"printf 'GET {path} HTTP/1.0\\r\\n\\r\\n' >&3; head -n 1 <&3")
        output = run(["kubectl", "exec", "-n", "monitoring", "daemonset/alloy", "--",
                      "timeout", "5", "bash", "-ec", script])
        assert b"200" in output, "authorized internal-service control failed"
    print("PASS: Traefik reaches auth but not receivers/management; authorized scraper controls pass")


@contextmanager
def forward(service, remote):
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    process = subprocess.Popen(["kubectl", "port-forward", "-n", "monitoring", "--address=127.0.0.1",
                                "service/" + service, f"{port}:{remote}"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        def ready():
            assert process.poll() is None, "private query port-forward exited"
            with socket.create_connection(("127.0.0.1", port), timeout=1):
                return True
        wait_until(ready, 15, "private query tunnel")
        yield f"http://127.0.0.1:{port}"
    finally:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def acceptance():
    document = json.loads(run(["sops", "--decrypt", "--output-type", "json", str(KEYS.DESTINATION)]))
    users = json.loads(document["stringData"]["auth.yaml"])["users"]
    tokens = {user["name"]: user["bearer_token"] for user in users}
    assert set(tokens) == set(KEYS.ALIASES), "unexpected credential aliases"
    assert len(set(tokens.values())) == 2, "credentials not independent"
    for signal in ("traces", "logs", "metrics"):
        for token in (None, secrets.token_urlsafe(32)):
            status, _ = request(ENDPOINT + "/v1/" + signal, {}, token)
            assert status == 401, "missing/invalid credential not rejected"
        for token in tokens.values():
            status, body = request(ENDPOINT + "/v1/" + signal, {}, token)
            assert status == 200, "authorized empty OTLP request failed"
            assert all(value.encode() not in body for value in tokens.values()), "response credential disclosure"
    for path in ("/metrics", "/-/ready", "/health", "/api/v1/query", "/v1/logs/extra"):
        assert request(ENDPOINT + path, {}, tokens[KEYS.ALIASES[0]])[0] == 404, "private path exposed"
    assert request(ENDPOINT + "/v1/logs", token=tokens[KEYS.ALIASES[0]], method="GET")[0] == 404
    print("PASS: verified HTTPS hostname/CA; both server keys/all signals; missing/invalid keys and private paths denied")

    marker = "otlp-live-check-" + secrets.token_hex(8)
    now = str(time.time_ns())
    trace_ids = {}
    for alias, token in tokens.items():
        trace_ids[alias] = secrets.token_hex(16)
        res = resource()
        res["attributes"].append(attribute("calcifer.test.run", marker))
        payloads = {
            "logs": {"resourceLogs": [{"resource": res, "scopeLogs": [{"logRecords": [{
                "timeUnixNano": now, "body": {"stringValue": marker},
                "attributes": [attribute("calcifer.ingest.client", "spoofed-log")],
            }]}]}]},
            "metrics": {"resourceMetrics": [{"resource": res, "scopeMetrics": [{"metrics": [{
                "name": "otlp_live_check", "gauge": {"dataPoints": [{"timeUnixNano": now, "asDouble": 1,
                "attributes": [attribute("calcifer.ingest.client", "spoofed-point")]}]},
            }]}]}]},
            "traces": trace_payload([(trace_ids[alias], True)]),
        }
        payloads["traces"]["resourceSpans"][0]["resource"]["attributes"].append(
            attribute("calcifer.test.run", marker))
        for signal, payload in payloads.items():
            assert request(ENDPOINT + "/v1/" + signal, payload, token)[0] == 200, "live signal ingestion failed"

    with ExitStack() as stack:
        logs = stack.enter_context(forward("victoria-logs", 9428))
        metrics = stack.enter_context(forward("victoria-metrics", 8428))
        traces = stack.enter_context(forward("victoria-traces", 10428))

        def query(url):
            status, body = request(url)
            assert status == 200, "private backend query failed"
            return body

        def log_ok():
            rows = [json.loads(line) for line in query(logs + "/select/logsql/query?" +
                    urllib.parse.urlencode({"query": marker})).splitlines() if line]
            return len(rows) == 2 and {row.get("calcifer.ingest.client") for row in rows} == set(tokens)

        def metric_ok():
            selector = 'otlp_live_check{"calcifer.test.run"="' + marker + '"}'
            rows = json.loads(query(metrics + "/api/v1/query?" + urllib.parse.urlencode({
                "query": selector, "nocache": 1})))["data"]["result"]
            return len(rows) == 2 and {row["metric"].get("calcifer.ingest.client") for row in rows} == set(tokens)

        def trace_ok():
            rows = json.loads(query(traces + "/select/jaeger/api/traces?" + urllib.parse.urlencode({
                "service": "public-otlp-test", "limit": 100,
                "start": int(now) // 1000 - 60_000_000, "end": time.time_ns() // 1000 + 60_000_000,
            }))).get("data", [])
            by_id = {row["traceID"]: row for row in rows}
            for alias, tid in trace_ids.items():
                row = by_id.get(tid)
                if not row or len(row["spans"]) != 2:
                    return False
                identities = {tag["value"] for proc in row["processes"].values()
                              for tag in proc.get("tags", []) if tag["key"] == "calcifer.ingest.client"}
                if identities != {alias}:
                    return False
            return True

        wait_until(log_ok, 45, "live logs and attribution")
        wait_until(metric_ok, 60, "live metrics and attribution")
        wait_until(trace_ok, 60, "live force-kept whole traces and attribution")
    print("PASS: two logs, two gauge points, two whole force-kept traces stored; both identities override spoofing")

    for target, namespace in [("deployment/otlp-vmauth", "monitoring"),
                               ("deployment/alloy-otlp-gateway", "monitoring"),
                               ("deployment/traefik", "kube-system")]:
        output = run(["kubectl", "logs", "-n", namespace, target, "--since=5m", "--tail=2000"])
        assert all(token.encode() not in output for token in tokens.values()), "credential found in recent logs"
        assert marker.encode() not in output, "payload found in recent gateway logs"
    print("PASS: captured recent gateway/Traefik logs contain neither test payload nor server keys")
    print("NOTE: server-key smoke test only; no GitHub SDK, live revocation, late-span or load test")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--network-only", action="store_true", help="read-only isolation/control probes")
    parser.add_argument("--accept-live-telemetry", action="store_true", help="authorize two tiny records per signal/client")
    args = parser.parse_args()
    if not args.network_only and not args.accept_live_telemetry:
        parser.error("choose --network-only or explicitly opt in with --accept-live-telemetry")
    assert run(["kubectl", "config", "current-context"]).strip() == b"calcifer-cloud", "wrong cluster context"
    isolation()
    if not args.network_only:
        acceptance()


if __name__ == "__main__":
    try:
        main()
    except Exception:
        # HTTP/command errors may contain headers or decrypted configuration.
        raise SystemExit("FAIL: live acceptance; sensitive exception/output suppressed") from None