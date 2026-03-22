#!/usr/bin/env python3
"""
Token Validator Service
Validates JWT Bearer tokens against Keycloak introspection endpoint.
Used as Traefik forwardAuth middleware for API access.
"""

import os
import sys
import urllib.request
import urllib.parse
import json
from http.server import HTTPServer, BaseHTTPRequestHandler

# Flush stdout immediately for docker logs
sys.stdout.reconfigure(line_buffering=True)

KEYCLOAK_URL = os.environ.get('KEYCLOAK_URL', 'http://keycloak:8080')
REALM = os.environ.get('REALM', 'calcifer')
CLIENT_ID = os.environ.get('CLIENT_ID', 'calcifer-api')
CLIENT_SECRET = os.environ.get('CLIENT_SECRET', '')
PORT = int(os.environ.get('PORT', '4182'))

INTROSPECT_URL = f"{KEYCLOAK_URL}/realms/{REALM}/protocol/openid-connect/token/introspect"


def validate_token(token: str) -> bool:
    """Validate token against Keycloak introspection endpoint."""
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
            print(f"Token validation: active={active}")
            return active
    except Exception as e:
        print(f"Token validation error: {e}")
        return False


class TokenValidatorHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        print(f"[{self.address_string()}] {format % args}")

    def do_GET(self):
        print(f"Received request: {self.path}")
        print(f"Headers: {dict(self.headers)}")

        # Get Authorization header (Traefik forwards original headers)
        auth = self.headers.get('Authorization', '')

        # Also check X-Forwarded-Authorization (some setups use this)
        if not auth:
            auth = self.headers.get('X-Forwarded-Authorization', '')

        print(f"Auth header: {auth[:50]}..." if auth else "No auth header")

        if auth.startswith('Bearer '):
            token = auth[7:]
            if validate_token(token):
                print("-> 200 OK")
                self.send_response(200)
                self.send_header('Content-Type', 'text/plain')
                self.end_headers()
                self.wfile.write(b'OK')
                return

        # No valid token
        print("-> 401 Unauthorized")
        self.send_response(401)
        self.send_header('Content-Type', 'text/plain')
        self.end_headers()
        self.wfile.write(b'Unauthorized')


def main():
    print(f"=" * 50)
    print(f"Token Validator starting on port {PORT}")
    print(f"Keycloak: {KEYCLOAK_URL}")
    print(f"Realm: {REALM}")
    print(f"Client: {CLIENT_ID}")
    print(f"Secret: {'*' * 10 if CLIENT_SECRET else 'NOT SET!'}")
    print(f"=" * 50)
    sys.stdout.flush()

    server = HTTPServer(('0.0.0.0', PORT), TokenValidatorHandler)
    server.serve_forever()


if __name__ == '__main__':
    main()

