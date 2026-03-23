#!/usr/bin/env python3
"""
Token Validator Service
Validates JWT Bearer tokens against Keycloak introspection endpoint.
Used as Traefik forwardAuth middleware for API access.
Instrumented with OpenTelemetry (traces + metrics via OTLP).
"""

import os
import sys
import urllib.request
import urllib.parse
import json
from http.server import HTTPServer, BaseHTTPRequestHandler

# Flush stdout immediately for docker logs
sys.stdout.reconfigure(line_buffering=True)

# ==================== OpenTelemetry Setup ====================
from opentelemetry import trace, metrics
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.exporter.otlp.proto.grpc.metric_exporter import OTLPMetricExporter
from opentelemetry.trace.propagation import TraceContextTextMapPropagator

OTEL_ENDPOINT = os.environ.get('OTEL_EXPORTER_OTLP_ENDPOINT', 'http://alloy:4317')

resource = Resource.create({"service.name": "token-validator"})

# Traces
tracer_provider = TracerProvider(resource=resource)
tracer_provider.add_span_processor(
    BatchSpanProcessor(OTLPSpanExporter(endpoint=OTEL_ENDPOINT, insecure=True))
)
trace.set_tracer_provider(tracer_provider)
tracer = trace.get_tracer("token-validator")

# Metrics
metric_reader = PeriodicExportingMetricReader(
    OTLPMetricExporter(endpoint=OTEL_ENDPOINT, insecure=True),
    export_interval_millis=15000,
)
meter_provider = MeterProvider(resource=resource, metric_readers=[metric_reader])
metrics.set_meter_provider(meter_provider)
meter = metrics.get_meter("token-validator")

# Metric instruments
requests_counter = meter.create_counter("token_validator.requests", description="Total requests")
authorized_counter = meter.create_counter("token_validator.authorized", description="Authorized requests")
unauthorized_counter = meter.create_counter("token_validator.unauthorized", description="Unauthorized requests")
errors_counter = meter.create_counter("token_validator.errors", description="Validation errors")

propagator = TraceContextTextMapPropagator()

# ==================== Config ====================
KEYCLOAK_URL = os.environ.get('KEYCLOAK_URL', 'http://keycloak:8080')
REALM = os.environ.get('REALM', 'calcifer')
CLIENT_ID = os.environ.get('CLIENT_ID', 'calcifer-api')
CLIENT_SECRET = os.environ.get('CLIENT_SECRET', '')
PORT = int(os.environ.get('PORT', '4182'))

INTROSPECT_URL = f"{KEYCLOAK_URL}/realms/{REALM}/protocol/openid-connect/token/introspect"


def validate_token(token: str) -> bool:
    """Validate token against Keycloak introspection endpoint."""
    with tracer.start_as_current_span("validate_token") as span:
        try:
            data = urllib.parse.urlencode({
                'token': token,
                'client_id': CLIENT_ID,
                'client_secret': CLIENT_SECRET
            }).encode()

            req = urllib.request.Request(INTROSPECT_URL, data=data, method='POST')
            with urllib.request.urlopen(req, timeout=5) as resp:
                result = json.loads(resp.read().decode())
                active = result.get('active', False)
                span.set_attribute("token.active", active)
                return active
        except Exception as e:
            errors_counter.add(1)
            span.set_attribute("error", True)
            span.set_attribute("error.message", str(e))
            print(f"Token validation error: {e}")
            return False


class TokenValidatorHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass  # Suppress default logging, OTel handles observability

    def do_GET(self):
        # Health check
        if self.path == '/health':
            self.send_response(200)
            self.send_header('Content-Type', 'text/plain')
            self.end_headers()
            self.wfile.write(b'OK')
            return

        # Extract trace context from incoming headers (propagated by Traefik)
        carrier = {k.lower(): v for k, v in self.headers.items()}
        ctx = propagator.extract(carrier=carrier)

        with tracer.start_as_current_span("handle_request", context=ctx) as span:
            requests_counter.add(1)
            span.set_attribute("http.method", "GET")
            span.set_attribute("http.path", self.path)

            auth = self.headers.get('Authorization', '')
            if not auth:
                auth = self.headers.get('X-Forwarded-Authorization', '')

            if auth.startswith('Bearer '):
                token = auth[7:]
                if validate_token(token):
                    authorized_counter.add(1)
                    span.set_attribute("auth.result", "authorized")
                    self.send_response(200)
                    self.send_header('Content-Type', 'text/plain')
                    self.end_headers()
                    self.wfile.write(b'OK')
                    return

            unauthorized_counter.add(1)
            span.set_attribute("auth.result", "unauthorized")
            self.send_response(401)
            self.send_header('Content-Type', 'text/plain')
            self.end_headers()
            self.wfile.write(b'Unauthorized')


def main():
    print(f"Token Validator starting on port {PORT}")
    print(f"Keycloak: {KEYCLOAK_URL} | Realm: {REALM} | Client: {CLIENT_ID}")
    print(f"OTLP endpoint: {OTEL_ENDPOINT}")
    sys.stdout.flush()

    server = HTTPServer(('0.0.0.0', PORT), TokenValidatorHandler)
    server.serve_forever()


if __name__ == '__main__':
    main()

