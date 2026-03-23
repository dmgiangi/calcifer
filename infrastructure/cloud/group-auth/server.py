#!/usr/bin/env python3
"""
Group Authorization Middleware for Traefik.

Receives X-Forwarded-User (email) from forward-auth, resolves the user's
Keycloak groups via admin API, and checks against per-host authorization rules.

Optionally injects extra headers for downstream role mapping (e.g., Grafana).
"""

import os
import sys
import json
import time
import threading
import urllib.request
import urllib.parse
import yaml
from http.server import HTTPServer, BaseHTTPRequestHandler

sys.stdout.reconfigure(line_buffering=True)

PORT = int(os.environ.get("PORT", "4183"))
CONFIG_PATH = os.environ.get("CONFIG_PATH", "/etc/group-auth/auth-config.yaml")
KEYCLOAK_URL = os.environ.get("KEYCLOAK_URL", "http://keycloak:8080")
REALM = os.environ.get("REALM", "calcifer")
CLIENT_ID = os.environ.get("CLIENT_ID", "calcifer-api")
CLIENT_SECRET = os.environ.get("CLIENT_SECRET", "")
CACHE_TTL = int(os.environ.get("CACHE_TTL", "60"))  # seconds


def load_config():
    with open(CONFIG_PATH) as f:
        return yaml.safe_load(f)


CONFIG = load_config()

# ==================== Keycloak Admin Token ====================
_token_lock = threading.Lock()
_admin_token = None
_token_expiry = 0


def get_admin_token():
    """Get a valid admin token via client credentials grant."""
    global _admin_token, _token_expiry
    with _token_lock:
        if _admin_token and time.time() < _token_expiry - 30:
            return _admin_token
        try:
            data = urllib.parse.urlencode({
                "grant_type": "client_credentials",
                "client_id": CLIENT_ID,
                "client_secret": CLIENT_SECRET,
            }).encode()
            req = urllib.request.Request(
                f"{KEYCLOAK_URL}/realms/{REALM}/protocol/openid-connect/token",
                data=data, method="POST",
            )
            with urllib.request.urlopen(req, timeout=5) as resp:
                result = json.loads(resp.read())
                _admin_token = result["access_token"]
                _token_expiry = time.time() + result.get("expires_in", 300)
                return _admin_token
        except Exception as e:
            print(f"ERROR: Failed to get admin token: {e}")
            return None


# ==================== User Groups Cache ====================
_groups_cache = {}
_cache_lock = threading.Lock()


def get_user_groups(email):
    """Resolve user's Keycloak groups (cached)."""
    with _cache_lock:
        cached = _groups_cache.get(email)
        if cached and time.time() < cached[1]:
            return cached[0]

    token = get_admin_token()
    if not token:
        return []

    try:
        encoded_email = urllib.parse.quote(email, safe="")
        url = (f"{KEYCLOAK_URL}/admin/realms/{REALM}/users"
               f"?email={encoded_email}&exact=true")
        req = urllib.request.Request(url)
        req.add_header("Authorization", f"Bearer {token}")
        with urllib.request.urlopen(req, timeout=5) as resp:
            users = json.loads(resp.read())
            if not users:
                return []
            user_id = users[0]["id"]

        url = f"{KEYCLOAK_URL}/admin/realms/{REALM}/users/{user_id}/groups"
        req = urllib.request.Request(url)
        req.add_header("Authorization", f"Bearer {token}")
        with urllib.request.urlopen(req, timeout=5) as resp:
            groups_data = json.loads(resp.read())
            groups = [g["name"] for g in groups_data]

        with _cache_lock:
            _groups_cache[email] = (groups, time.time() + CACHE_TTL)

        return groups
    except Exception as e:
        print(f"ERROR: Failed to resolve groups for {email}: {e}")
        return []


def check_access(host, user_groups):
    """Returns (allowed: bool, extra_headers: dict)."""
    rules = CONFIG.get("rules", {})
    default = CONFIG.get("default_policy", "deny")

    rule = rules.get(host)
    if rule is None:
        return (default == "allow"), {}

    allowed_groups = rule.get("allowed_groups", [])
    matching = set(user_groups) & set(allowed_groups)

    if not matching:
        return False, {}

    # Collect extra headers from the highest-priority matching group
    headers = {}
    header_map = rule.get("headers", {})
    for group in allowed_groups:
        if group in matching and group in header_map:
            headers = header_map[group]
            break

    return True, headers


class GroupAuthHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        host = self.headers.get("X-Forwarded-Host", "")
        user = self.headers.get("X-Forwarded-User", "")

        if not user:
            self.send_response(401)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"Unauthorized: no user identity")
            return

        user_groups = get_user_groups(user)
        allowed, extra_headers = check_access(host, user_groups)

        if not allowed:
            print(f"DENIED {user} -> {host} (groups: {user_groups})")
            self.send_response(403)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"Forbidden: insufficient group membership")
            return

        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        for hdr_name, hdr_value in extra_headers.items():
            self.send_header(hdr_name, hdr_value)
        self.end_headers()
        self.wfile.write(b"OK")


def main():
    print(f"Group Auth middleware starting on port {PORT}")
    print(f"Keycloak: {KEYCLOAK_URL} | Realm: {REALM}")
    print(f"Default policy: {CONFIG.get('default_policy', 'deny')}")
    print(f"Rules: {', '.join(CONFIG.get('rules', {}).keys())}")
    print(f"Cache TTL: {CACHE_TTL}s")
    sys.stdout.flush()

    server = HTTPServer(("0.0.0.0", PORT), GroupAuthHandler)
    server.serve_forever()


if __name__ == "__main__":
    main()


