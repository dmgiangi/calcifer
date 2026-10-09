#!/usr/bin/env python3
"""Advanced, isolated Docker acceptance; never a production capacity/load test.

Run with Python's -B option. No images are pulled, dependencies installed, cluster
accessed, or production files changed. Only synthetic credentials are generated.
Production-default stages mount otlp-gateway.alloy unchanged. Other stages use
explicitly TEST-ONLY copies with smaller bounds. In particular cache-disabled
decisions survive *while resident*, not forever: after buffer eviction a trace
can be evaluated again, and an earlier drop cannot recover its original spans.

Expected semantics verified against Alloy v1.19.2 tail_sampling/{tail_sampling,
types}.go and config_queue.go, contrib v0.158.0 tailsamplingprocessor/processor.go,
and VictoriaLogs v1.52.0 lib/logstorage/storage.go. The latter's disk cap removes
older daily partitions but preserves the latest two; it is not a hard quota.

All responses, configurations, subprocess output and container logs remain in
memory/private temporary files. Only fixed stages/results and counts are printed.
Each container has one CPU, a RAM/swap cap, 64 MiB tmpfs, bounded Docker logs and
128 PIDs. The whole run has a 450-second deadline, including checks/cleanup.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import secrets
import signal
import struct
import subprocess
import tempfile
import time
import urllib.parse
import urllib.error
import urllib.request

import check_public_otlp_runtime as runtime

IMAGES = {
    "alloy": "grafana/alloy:v1.19.2",
    "traces": "victoriametrics/victoria-traces:v0.11.0",
    "logs": "victoriametrics/victoria-logs:v1.52.0",
    "metrics": "victoriametrics/victoria-metrics:v1.151.0",
    "auth": "victoriametrics/vmauth:v1.153.0",
}
ALLOY = runtime.OBSERVABILITY / "otlp-gateway.alloy"
STAGE = "preflight"


class CheckFailed(Exception):
    """No exception detail is ever emitted, including from reused helpers."""


def require(condition: bool) -> None:
    if not condition:
        raise CheckFailed()


def stage(name: str) -> None:
    global STAGE
    STAGE = name
    print("STAGE:", name, flush=True)


def passed(name: str, **counts: int) -> None:
    print("PASS:", name, *(f"{key}={int(value)}" for key, value in counts.items()), flush=True)


def docker(arguments: list[str]) -> bytes:
    # Sandbox's verified lifecycle/port checks are reused below, with stricter
    # resource and subprocess time bounds and fully captured output.
    require(arguments[0] == "docker")
    arguments = list(arguments)
    if arguments[1] == "create":
        require(arguments[arguments.index("--tmpfs") + 1] == "/tmp")
        arguments[arguments.index("--tmpfs") + 1] = "/tmp:size=64m,mode=1777"
        memory = arguments[arguments.index("--memory") + 1]
        arguments[2:2] = ["--pull=never", "--cpus=1", "--memory-swap", memory,
                          "--pids-limit=128", "--security-opt=no-new-privileges",
                          "--cap-drop=ALL", "--log-driver=json-file",
                          "--log-opt=max-size=2m", "--log-opt=max-file=1"]
    result = subprocess.run(arguments, capture_output=True, timeout=12)
    require(result.returncode == 0)
    return result.stdout + (result.stderr if arguments[1] == "logs" else b"")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Even synthetic Authorization must never follow an off-sandbox redirect.
        raise CheckFailed()


class BoundedSandbox(runtime.Sandbox):
    def __init__(self, tokens: tuple[str, str]):
        super().__init__()
        self.tokens = tokens
        self.network_id = ""

    def create(self) -> None:
        self.network_id = docker(["docker", "network", "create", self.network]).decode().strip()
        require(bool(re.fullmatch(r"[0-9a-f]{64}", self.network_id)))

    def start(self, image: str, port: int, alias: str, args: list[str],
              mounts: list[str] | None = None, memory: str = "256m") -> str:
        # Same verified Sandbox lifecycle/port checks, but create first so even
        # a failed start has an exact owned ID available for cleanup.
        cid = docker(["docker", "create", "--network", self.network,
                      "--network-alias", alias, "--read-only", "--tmpfs", "/tmp",
                      "--memory", memory, "-p", f"127.0.0.1::{port}",
                      *(mounts or []), image, *args]).decode().strip()
        require(bool(re.fullmatch(r"[0-9a-f]{64}", cid)))
        self.containers.append(cid)
        docker(["docker", "start", cid])
        state = json.loads(docker(["docker", "inspect", cid, "--format", "{{json .State}} "]))
        require(state["Running"])
        published = docker(["docker", "port", cid, f"{port}/tcp"]).decode().strip()
        return "http://" + published

    def check_logs(self, cid: str) -> None:
        output = docker(["docker", "logs", cid])
        require(all(token.encode() not in output for token in self.tokens))
        require(runtime.BODY_MARKER.encode() not in output)
        require(b"authorization:" not in output.lower() and b"bearer " not in output.lower())

    def retire(self, cid: str) -> None:
        require(cid in self.containers and bool(re.fullmatch(r"[0-9a-f]{64}", cid)))
        self.check_logs(cid)
        docker(["docker", "rm", "-f", cid])
        self.containers.remove(cid)

    def close(self) -> None:
        # Try every exact owned ID even if one cleanup fails. No broad prune,
        # name/glob removal, external resources or workspace file removal.
        failures = 0
        for cid in list(reversed(self.containers)):
            try:
                self.check_logs(cid)
            except Exception:
                failures += 1
            try:
                docker(["docker", "rm", "-f", cid])
                self.containers.remove(cid)
            except Exception:
                failures += 1
        if self.network_id:
            try:
                docker(["docker", "network", "rm", self.network_id])
                self.network_id = ""
            except Exception:
                failures += 1
        require(failures == 0)


class Acceptance:
    def __init__(self, sandbox: BoundedSandbox, temporary: Path):
        self.sandbox = sandbox
        self.temporary = temporary
        self.collector_id = self.gateway_id = ""
        self.collector = self.gateway = ""
        self.auth = temporary / "auth.yaml"
        self.auth.touch(mode=0o600)
        self.auth.write_text(json.dumps(runtime.KEYS.auth_config(sandbox.tokens)))
        self.backends = {}
        self.backend_ids = {}

    def http(self, url: str, payload: dict | bytes | None = None, token: str | None = None):
        # Never use proxy settings from the user's environment, or off-sandbox URLs.
        target = urllib.parse.urlparse(url)
        require(target.scheme == "http" and target.hostname == "127.0.0.1"
                and target.port is not None and target.username is None)
        if isinstance(payload, bytes):
            require(token is None)
            req = urllib.request.Request(url, data=payload, headers={"Content-Type": "application/x-protobuf"})
            try:
                with urllib.request.urlopen(req, timeout=5) as response:
                    status, body = response.status, response.read()
            except urllib.error.HTTPError as error:
                status, body = error.code, error.read()
        else:
            status, body = runtime.request(url, payload, token)
        require(all(key.encode() not in body for key in self.sandbox.tokens))
        return status, body

    def start_backends(self) -> None:
        for name, port in (("traces", 10428), ("logs", 9428), ("metrics", 8428)):
            stage("sandbox-start-" + name)
            endpoint = self.sandbox.start(IMAGES[name], port,
                f"victoria-{name}.monitoring.svc.cluster.local",
                ["-storageDataPath=/tmp/data", "-retentionPeriod=" + ("365d" if name == "metrics" else "6M"),
                 "-loggerLevel=WARN", *(["-search.latencyOffset=0s"] if name == "metrics" else [])])
            self.backends[name] = endpoint
            self.backend_ids[name] = self.sandbox.containers[-1]
            runtime.wait_until(lambda: self.http(endpoint + "/health")[0] == 200, 20)

    def pipeline(self, config: Path) -> None:
        for cid in (self.gateway_id, self.collector_id):
            if cid:
                self.sandbox.retire(cid)
        stage("sandbox-start-collector")
        self.collector = self.sandbox.start(IMAGES["alloy"], 12345,
            "alloy-otlp-gateway.monitoring.svc.cluster.local",
            ["run", "/etc/alloy/config.alloy", "--storage.path=/tmp/alloy",
             "--server.http.listen-addr=0.0.0.0:12345", "--disable-reporting"],
            ["-v", f"{config}:/etc/alloy/config.alloy:ro", "--user", "10001:10001"], "512m")
        self.collector_id = self.sandbox.containers[-1]
        runtime.wait_until(lambda: self.http(self.collector + "/-/ready")[0] == 200, 20)
        stage("sandbox-start-authentication")
        self.gateway = self.sandbox.start(IMAGES["auth"], 8427, "otlp-vmauth",
            ["-auth.config=/etc/vmauth/auth.yaml", "-httpListenAddr=:8427",
             "-httpInternalListenAddr=:8428", "-logInvalidAuthTokens=false", "-loggerLevel=WARN"],
            ["-v", f"{self.auth}:/etc/vmauth/auth.yaml:ro",
             "--user", f"{os.getuid()}:{os.getgid()}"], "128m")
        self.gateway_id = self.sandbox.containers[-1]
        runtime.wait_until(lambda: self.http(self.gateway + "/v1/logs", {}, self.sandbox.tokens[0])[0]
                           in (200, 503), 20)

    def test_config(self, name: str, replacements: dict[str, str]) -> Path:
        config = ALLOY.read_text()
        for old, new in replacements.items():
            require(old in config)
            config = config.replace(old, new)
        path = self.temporary / (name + ".alloy")
        path.write_text(config)
        return path

    def ingest(self, signal_name: str, payload: dict, alias: int = 0) -> tuple[int, bytes]:
        return self.http(self.gateway + "/v1/" + signal_name, payload, self.sandbox.tokens[alias])

    def traces(self, entries, alias: int = 0, resource_keep: bool = False) -> dict[str, set[str]]:
        payload = runtime.trace_payload(entries, resource_keep)
        spans = payload["resourceSpans"][0]["scopeSpans"][0]["spans"]
        ids: dict[str, set[str]] = {}
        for span in spans:
            span["name"] = runtime.BODY_MARKER
            ids.setdefault(span["traceId"], set()).add(span["spanId"])
        require(self.ingest("traces", payload, alias)[0] == 200)
        return ids

    def rows(self) -> dict:
        query = urllib.parse.urlencode({"service": "public-otlp-test", "limit": 1000,
            "start": time.time_ns() // 1000 - 600_000_000, "end": time.time_ns() // 1000 + 120_000_000})
        status, body = self.http(self.backends["traces"] + "/select/jaeger/api/traces?" + query)
        require(status == 200)
        return {row["traceID"]: {span["spanID"] for span in row["spans"]}
                for row in json.loads(body).get("data", [])}

    def metrics(self, endpoint: str | None = None) -> list[tuple[str, str, float]]:
        status, body = self.http((endpoint or self.collector) + "/metrics")
        require(status == 200)
        parsed = []
        for line in body.decode().splitlines():
            match = re.fullmatch(r'([a-zA-Z_:][a-zA-Z0-9_:]*)(\{.*\})?\s+([-+0-9.eE]+)(?:\s+\d+)?', line)
            if match:
                parsed.append((match[1], match[2] or "", float(match[3])))
        return parsed

    def count(self, name: str, endpoint: str | None = None, sampled: bool | None = None) -> float:
        return sum(value for metric, labels, value in self.metrics(endpoint)
                   if (sampled is None or re.search(
                       r'(?:\{|,)sampled="' + str(sampled).lower() + r'"(?:,|\})', labels))
                   and (metric in (name, name + "_total") or
                   (name.endswith("_count") and metric.startswith(name[:-6] + "_")
                    and metric.endswith("_count"))))

    def log_payload(self, seq: int, timestamp: int | None = None) -> dict:
        return {"resourceLogs": [{"resource": runtime.resource(), "scopeLogs": [{"logRecords": [{
            "timeUnixNano": str(timestamp or time.time_ns()), "body": {"stringValue": runtime.BODY_MARKER},
            "attributes": [runtime.attribute("acceptance.seq", str(seq))],
        }]}]}]}

    def log_rows(self, endpoint: str | None = None) -> set[int]:
        url = (endpoint or self.backends["logs"]) + "/select/logsql/query?" + urllib.parse.urlencode({
            "query": runtime.BODY_MARKER, "limit": 100})
        status, body = self.http(url)
        require(status == 200)
        return {int(json.loads(line)["acceptance.seq"]) for line in body.splitlines() if line}

    def log_protobuf(self, seq: int, timestamp: int | None = None) -> bytes:
        # Minimal synthetic OTLP LogsData fixture, using the stable public proto
        # field numbers. Direct VictoriaLogs requests need protobuf, unlike the
        # Alloy JSON receiver. No protobuf package or production code is added.
        def field(number: int, value: bytes) -> bytes:
            size, length = len(value), bytearray()
            while size >= 128:
                length.append((size & 127) | 128)
                size >>= 7
            length.append(size)
            return bytes([number << 3 | 2]) + bytes(length) + value

        def attribute(key: str, value: str) -> bytes:
            return field(1, key.encode()) + field(2, field(1, value.encode()))

        resource = field(1, attribute("service.name", "public-otlp-test"))
        record = b"\x09" + struct.pack("<Q", timestamp or time.time_ns())
        record += field(5, field(1, runtime.BODY_MARKER.encode()))
        record += field(6, attribute("acceptance.seq", str(seq)))
        return field(1, field(1, resource) + field(2, field(2, record)))

    def restart_backend(self, name: str) -> None:
        cid = self.backend_ids[name]
        docker(["docker", "start", cid])
        port = {"logs": 9428, "traces": 10428, "metrics": 8428}[name]
        # Docker may allocate a different ephemeral host port after stop/start.
        # Collector traffic still uses the stable in-network service alias.
        published = docker(["docker", "port", cid, f"{port}/tcp"]).decode().strip()
        self.backends[name] = "http://" + published
        runtime.wait_until(lambda: self.http(self.backends[name] + "/health")[0] == 200, 20)

    def production_late_spans(self) -> list[str]:
        stage("production-default-late-spans")
        populations = [[secrets.token_hex(16) for _ in range(48)] for _ in range(2)]
        forced = [[secrets.token_hex(16) for _ in range(4)] for _ in range(2)]
        initial = {}
        for alias in (0, 1):
            initial.update(self.traces([(tid, None) for tid in populations[alias]], alias))
            initial.update(self.traces([(tid, True) for tid in forced[alias]], alias))
        decisions = "otelcol_processor_tail_sampling_global_count_traces_sampled"
        runtime.wait_until(lambda: self.count(decisions) >= 104, 50)
        # Sampling decisions precede asynchronous backend export. Do not classify
        # a retained trace as dropped just because a query raced that export.
        runtime.wait_until(lambda: self.count("otelcol_exporter_sent_spans")
                           == 2 * self.count(decisions, sampled=True), 15)
        runtime.wait_until(lambda: sum(len(spans) for tid, spans in self.rows().items() if tid in initial)
                           == 2 * self.count(decisions, sampled=True), 15)
        runtime.wait_until(lambda: set(forced[0] + forced[1]) <= self.rows().keys(), 15)
        rows = self.rows()
        groups = []
        for alias in (0, 1):
            kept = set(populations[alias]) & rows.keys()
            dropped = set(populations[alias]) - rows.keys()
            require(len(kept) >= 8 and len(dropped) >= 8)
            groups.append((kept, dropped))
        require(all(rows[tid] == initial[tid] for kept, _ in groups for tid in kept))
        before = self.count("otelcol_processor_tail_sampling_new_trace_id_received")
        expected = {tid: initial[tid] for tid in rows if tid in initial}
        late_count = self.count("otelcol_processor_tail_sampling_sampling_late_span_age_count")
        for alias, (kept, dropped) in enumerate(groups):
            late = self.traces([(tid, None) for tid in kept | set(forced[alias])], alias)
            for tid, spans in late.items():
                expected[tid] = expected[tid] | spans
            self.traces([(tid, None) for tid in dropped], alias)
            self.traces([(tid, True) for tid in dropped], alias, resource_keep=bool(alias))
        runtime.wait_until(lambda: all(self.rows().get(tid) == spans for tid, spans in expected.items()), 15)
        runtime.wait_until(lambda: self.count("otelcol_processor_tail_sampling_sampling_late_span_age_count")
                           >= late_count + 104, 10)
        require(self.count("otelcol_processor_tail_sampling_new_trace_id_received") == before)
        require(self.count("otelcol_processor_tail_sampling_early_releases_from_cache_decision") == 0)
        # A fresh force-kept sentinel is a decision-window/export barrier. Observe
        # absence throughout another actual default window, not one negative query.
        sentinel = secrets.token_hex(16)
        sentinel_spans = self.traces([(sentinel, True)])[sentinel]
        all_dropped = groups[0][1] | groups[1][1]
        end = time.monotonic() + 50
        while time.monotonic() < end:
            rows = self.rows()
            require(not all_dropped & rows.keys())
            if rows.get(sentinel) == sentinel_spans:
                break
            time.sleep(0.5)
        else:
            raise CheckFailed()
        passed("production-default-kept-dropped-late-force-no-resurrection",
               kept=len(expected), dropped=len(all_dropped), aliases=2)
        return list(groups[0][1])[:2]

    def production_retry(self) -> None:
        stage("production-default-queued-retry")
        accepted = set(range(20))
        cid = self.backend_ids["logs"]
        # Stop/start this exact disposable backend. Unlike a pause, connection
        # attempts now fail instead of an old request succeeding after unpause.
        # Its tmpfs is disposable; no data pre-dates this stage. At WARN level
        # upstream retry INFO logs are deliberately unavailable. Observe the
        # in-flight retry operations and waiting queue, not terminal failures.
        sent_before = self.count("otelcol_exporter_sent_log_records")
        docker(["docker", "stop", "--time=2", cid])
        state = json.loads(docker(["docker", "inspect", cid, "--format", "{{json .State}} "]))
        require(not state["Running"])
        try:
            for seq in accepted:
                require(self.ingest("logs", self.log_payload(seq))[0] == 200)
            runtime.wait_until(lambda: self.count("otelcol_exporter_queue_size") > 0, 10)
            runtime.wait_until(lambda: self.count("otelcol_exporter_in_flight_requests") > 0, 10)
            end = time.monotonic() + 8
            while time.monotonic() < end:
                require(self.count("otelcol_exporter_queue_size") > 0)
                require(self.count("otelcol_exporter_sent_log_records") == sent_before)
                time.sleep(0.5)
            require(self.count("otelcol_exporter_enqueue_failed_log_records") == 0)
        finally:
            self.restart_backend("logs")
        runtime.wait_until(lambda: accepted <= self.log_rows(), 40)
        runtime.wait_until(lambda: self.count("otelcol_exporter_queue_size") == 0, 15)
        require(self.count("otelcol_exporter_sent_log_records") == sent_before + len(accepted))
        require(self.count("otelcol_exporter_send_failed_log_records") == 0)
        passed("production-default-queue-retry-recovery", delivered=len(accepted), lost=0)

    def eviction(self, dropped_ids: list[str]) -> None:
        config = self.test_config("test-only-eviction", {
            'otelcol.processor.tail_sampling "local" {':
            'otelcol.processor.tail_sampling "local" {\n  decision_wait = "1s"\n  num_traces = 8',
            'otelcol.processor.tail_sampling "github" {':
            'otelcol.processor.tail_sampling "github" {\n  decision_wait = "1s"\n  num_traces = 8',
        })
        self.pipeline(config)
        stage("test-only-small-buffer-cache-disabled-eviction")
        dropped, forced = dropped_ids
        self.traces([(dropped, None), (forced, True)])
        decisions = "otelcol_processor_tail_sampling_global_count_traces_sampled"
        runtime.wait_until(lambda: self.count(decisions) >= 2, 10)
        # The pinned trace backend makes fresh Jaeger data searchable after
        # roughly 30 seconds, independently of the accelerated sampler window.
        runtime.wait_until(lambda: forced in self.rows(), 50)
        require(dropped not in self.rows())
        self.traces([(dropped, True), (forced, None)])
        runtime.wait_until(lambda: len(self.rows().get(forced, set())) == 4, 50)
        require(dropped not in self.rows())
        require(self.count("otelcol_processor_tail_sampling_new_trace_id_received") == 2)
        fillers = [secrets.token_hex(16) for _ in range(9)]
        self.traces([(tid, True) for tid in fillers])
        runtime.wait_until(lambda: self.count("otelcol_processor_tail_sampling_sampling_trace_dropped_too_early")
                           >= 1, 10)
        runtime.wait_until(lambda: self.count(decisions) >= 10, 10)
        before = self.count("otelcol_processor_tail_sampling_new_trace_id_received")
        new_spans = self.traces([(dropped, True), (forced, None)])
        runtime.wait_until(lambda: self.count(decisions) >= 12, 10)
        runtime.wait_until(lambda: self.rows().get(dropped) == new_spans[dropped], 50)
        require(len(self.rows()[forced]) == 4)  # Lost force-keep, now probabilistically dropped.
        require(self.count("otelcol_processor_tail_sampling_new_trace_id_received") == before + 2)
        require(self.count("otelcol_processor_tail_sampling_early_releases_from_cache_decision") == 0)
        passed("test-only-eviction-re-evaluates-not-recovers-old-spans", re_evaluated=2,
               pending_overflow=int(self.count("otelcol_processor_tail_sampling_sampling_trace_dropped_too_early")))

    def queue_overflow(self) -> None:
        config = self.test_config("test-only-queue", {
            'otelcol.exporter.otlphttp "logs" {':
            'otelcol.exporter.otlphttp "logs" {\n  sending_queue {\n    num_consumers = 1\n    queue_size = 2\n  }',
        })
        self.pipeline(config)
        stage("test-only-small-queue-overflow")
        cid = self.backend_ids["logs"]
        docker(["docker", "stop", "--time=2", cid])
        accepted, rejected = set(), set()
        try:
            for seq in range(100, 120):
                status, body = self.ingest("logs", self.log_payload(seq))
                partial = json.loads(body).get("partialSuccess", {}) if status == 200 else {}
                if status == 200 and not int(partial.get("rejectedLogRecords", 0)):
                    accepted.add(seq)
                else:
                    require(status == 503 or int(partial.get("rejectedLogRecords", 0)) > 0)
                    rejected.add(seq)
            require(accepted and rejected)
            runtime.wait_until(lambda: self.count("otelcol_exporter_enqueue_failed_log_records") >= len(rejected), 10)
        finally:
            self.restart_backend("logs")
        runtime.wait_until(lambda: accepted <= self.log_rows(), 30)
        runtime.wait_until(lambda: self.count("otelcol_exporter_queue_size") == 0, 10)
        require(not rejected & self.log_rows())
        passed("test-only-queue-overflow-and-accepted-recovery", accepted=len(accepted), rejected=len(rejected))

    def memory_refusal(self) -> None:
        config = self.test_config("test-only-memory", {
            'check_interval = "1s"': 'check_interval = "100ms"',
            'limit          = "384MiB"': 'limit          = "1MiB"',
            'spike_limit    = "64MiB"': 'spike_limit    = "512KiB"',
        })
        self.pipeline(config)
        stage("test-only-small-memory-refusal")
        time.sleep(1)
        for alias in (0, 1):
            require(self.ingest("logs", self.log_payload(200 + alias), alias)[0] == 503)
            # An exemption is not allowed to bypass the upstream memory limiter.
            payload = runtime.trace_payload([(secrets.token_hex(16), True)], resource_keep=bool(alias))
            for span in payload["resourceSpans"][0]["scopeSpans"][0]["spans"]:
                span["name"] = runtime.BODY_MARKER
            require(self.ingest("traces", payload, alias)[0] == 503)
        runtime.wait_until(lambda: self.count("otelcol_processor_memory_limiter_refused_log_records") >= 2, 10)
        runtime.wait_until(lambda: self.count("otelcol_processor_memory_limiter_refused_spans") >= 4, 10)
        require(not {200, 201} & self.log_rows())
        state = json.loads(docker(["docker", "inspect", self.collector_id, "--format", "{{json .State}} "]))
        require(state["Running"] and not state["OOMKilled"])
        passed("test-only-memory-retryable-refusal-without-oom", refused_logs=2, refused_force_keep_spans=4)

    def disk_pressure(self) -> None:
        stage("test-only-backend-free-space-rejection")
        endpoint = self.sandbox.start(IMAGES["logs"], 9428, "test-only-disk-pressure",
            ["-storageDataPath=/tmp/data", "-retentionPeriod=6M", "-loggerLevel=WARN",
             "-storage.minFreeDiskSpaceBytes=128MiB"])
        cid = self.sandbox.containers[-1]
        runtime.wait_until(lambda: self.http(endpoint + "/health")[0] == 200, 20)
        runtime.wait_until(lambda: self.count("vl_storage_is_read_only", endpoint) == 1, 10)
        # A valid protobuf request reaches the read-only guard (retryable 429).
        # JSON sent directly here would instead fail format validation with 400.
        require(self.http(endpoint + "/insert/opentelemetry/v1/logs", self.log_protobuf(300))[0] == 429)
        require(300 not in self.log_rows(endpoint))
        self.sandbox.retire(cid)
        passed("test-only-backend-free-space-guard-retryable-429", refused=1, http_status=429)

        stage("test-only-backend-partition-cap")
        endpoint = self.sandbox.start(IMAGES["logs"], 9428, "test-only-disk-cap",
            ["-storageDataPath=/tmp/data", "-retentionPeriod=6M", "-loggerLevel=WARN",
             "-retention.maxDiskSpaceUsageBytes=1"])
        cid = self.sandbox.containers[-1]
        runtime.wait_until(lambda: self.http(endpoint + "/health")[0] == 200, 20)
        now = time.time_ns()
        for day in range(3):
            require(self.http(endpoint + "/insert/opentelemetry/v1/logs",
                              self.log_protobuf(400 + day, now - day * 86_400_000_000_000))[0] == 200)
        require(self.http(endpoint + "/internal/force_flush", {})[0] == 200)
        runtime.wait_until(lambda: self.count("vl_partitions", endpoint) == 2, 25)
        rows = self.log_rows(endpoint)
        require(rows == {400, 401})
        require(self.count("vl_storage_is_read_only", endpoint) == 0)
        self.sandbox.retire(cid)
        passed("test-only-cap-deletes-oldest-preserves-latest-two-not-hard-quota", remaining=2, evicted=1)


def main() -> None:
    def deadline(_signum, _frame):
        raise CheckFailed()

    signal.signal(signal.SIGALRM, deadline)
    signal.alarm(400)  # Reserve up to 50 seconds for exact-ID cleanup.
    urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect()))
    sandbox = BoundedSandbox((secrets.token_urlsafe(32), secrets.token_urlsafe(32)))
    failed = False
    try:
        for image in IMAGES.values():
            docker(["docker", "image", "inspect", image, "--format", "{{.Id}}"])
        sandbox.create()
        with tempfile.TemporaryDirectory(prefix="calcifer-otlp-advanced-", dir="/tmp") as temporary:
            test = Acceptance(sandbox, Path(temporary))
            stage("sandbox-start")
            test.start_backends()
            test.pipeline(ALLOY)
            dropped_ids = test.production_late_spans()
            test.production_retry()
            test.eviction(dropped_ids)
            test.queue_overflow()
            test.memory_refusal()
            test.disk_pressure()
            for cid in sandbox.containers:
                sandbox.check_logs(cid)
            passed("captured-responses-and-log-nondisclosure")
    except BaseException as error:
        failed = True
        # A source line number is safe, unlike exception text (which could
        # contain a response, payload, command or configuration value).
        tb = error.__traceback__
        check_line = 0
        while tb:
            if tb.tb_frame.f_code.co_filename == __file__ and tb.tb_frame.f_code.co_name != "require":
                check_line = tb.tb_lineno
            tb = tb.tb_next
        print("FAIL:", STAGE, f"check_line={check_line}", flush=True)
    finally:
        signal.alarm(50)
        try:
            sandbox.close()
            passed("exact-owned-id-cleanup")
        except BaseException:
            failed = True
            print("FAIL: exact-owned-id-cleanup", flush=True)
        signal.alarm(0)
    if failed:
        raise SystemExit(1)
    passed("advanced-acceptance")


if __name__ == "__main__":
    main()
