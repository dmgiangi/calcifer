#!/usr/bin/env python3
"""Validate rendered Cloud staging and preservation of existing collection."""

import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
AREA = "clusters/calcifer-cloud/apps/observability"


def run(arguments, data=None):
    result = subprocess.run(arguments, input=data, capture_output=True, cwd=ROOT)
    if result.returncode:
        raise RuntimeError(f"{arguments[0]} failed; output suppressed")
    return result.stdout


def objects(data):
    # kubectl create emits successive JSON objects for multi-document input.
    text = run(["kubectl", "create", "--dry-run=client", "--validate=false",
                "-f", "-", "-o", "json"], data).decode()
    decoder = json.JSONDecoder()
    result = []
    while text.strip():
        document, end = decoder.raw_decode(text.lstrip())
        text = text.lstrip()[end:]
        result.extend(document.get("items", [document]))
    return {(item["kind"], item["metadata"]["name"]): item for item in result}


def main():
    rendered = objects(run(["kubectl", "kustomize", AREA]))
    gateway = rendered[("Deployment", "alloy-otlp-gateway")]["spec"]
    config_name = gateway["template"]["spec"]["volumes"][0]["configMap"]["name"]
    assert config_name.startswith("alloy-otlp-gateway-")
    assert rendered[("ConfigMap", config_name)]["data"]["config.alloy"]
    assert gateway["replicas"] == 1 and gateway["strategy"]["type"] == "Recreate"
    assert rendered[("Deployment", "otlp-vmauth")]["spec"]["template"]["spec"]["volumes"][0]["secret"]["secretName"] == "public-otlp-auth"
    assert rendered[("Secret", "public-otlp-auth")]["stringData"]["auth.yaml"].startswith("ENC[")
    assert ("IngressRoute", "public-otlp") not in rendered
    assert ("Certificate", "public-otlp") not in rendered
    print("PASS: rendered ConfigMap/Secret references; one replica; public route excluded")

    def allowed(policy, labels, port):
        return any(any(peer.get("podSelector", {}).get("matchLabels") == labels and
                       "namespaceSelector" not in peer for peer in rule.get("from", [])) and
                   any(item["port"] == port for item in rule["ports"])
                   for rule in policy["spec"]["ingress"])

    policy = rendered[("NetworkPolicy", "alloy-otlp-gateway-ingress")]
    for port in (4318, 4319):
        assert allowed(policy, {"app.kubernetes.io/name": "otlp-vmauth"}, port)
        assert not allowed(policy, {"app.kubernetes.io/name": "traefik"}, port)
        assert not allowed(policy, {}, port)
    rules = policy["spec"]["ingress"]
    assert len(rules) == 2
    assert [p["port"] for p in rules[1]["ports"]] == [12345]
    print("PASS: receiver policy selects only authentication pods; separate internal scrape port")

    old_releases = objects(run(["git", "show", f"HEAD:{AREA}/helmreleases.yaml"]))
    for key, old in old_releases.items():
        current = rendered[key]
        if key[1] in ("victoria-traces", "victoria-logs"):
            server = old["spec"]["values"]["server"]
            server["retentionPeriod"] = "6M"
            server["retentionDiskSpaceUsage"] = "20GiB" if key[1] == "victoria-traces" else "8GiB"
        assert old["spec"] == current["spec"], "unexpected existing HelmRelease change"
    for name in ("victoria-traces", "victoria-logs", "victoria-metrics"):
        filename = f"{AREA}/{name}-ingress-network-policy.yaml"
        old = objects(run(["git", "show", f"HEAD:{filename}"]))[("NetworkPolicy", name + "-ingress")]
        old["spec"]["ingress"][0]["from"].insert(1, {
            "podSelector": {"matchLabels": {"app.kubernetes.io/name": "alloy-otlp-gateway"}}})
        assert old["spec"] == rendered[("NetworkPolicy", name + "-ingress")]["spec"]
    assert not run(["git", "diff", "--name-only", "HEAD", "--", "clusters/calcifer-home"]).strip()
    print("PASS: existing Alloy/collection/backups unchanged; PVCs preserved; Home unchanged")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError, AssertionError, KeyError):
        raise SystemExit("FAIL: manifest contract check; sensitive tool output suppressed") from None