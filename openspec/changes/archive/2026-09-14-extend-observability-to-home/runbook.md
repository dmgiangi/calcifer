# Operational runbook

## Local retention limit

Alloy Home stores metric and log forwarding state below `/var/lib/alloy` on
the dedicated `local-path` PVC `alloy-data` (16Gi). The volume is a bounded
buffer, not a second long-term observability store. The acceptance test uses a
controlled 120-second outage. The 48-hour target remains an operational
capacity-planning goal subject to the measured Home ingest rate, not a
prerequisite that can be proven by the short acceptance test.

Monitor the Alloy WAL, pending samples, retry counters, dropped entries and
the PVC usage dashboard. Stop the rollout or enlarge the PVC if the measured
rate cannot fit the agreed outage window. Backend recovery must be checked
separately after confirming that the Cloud receive component is Ready and has
enough memory to replay its own WAL.

During the controlled test, Alloy Home retained the outage backlog and drained
its local queue after transit recovery. After the change was committed and
reconciled by Flux, the Grafana end-to-end recovery check completed successfully:
PromQL and LogQL queries filtered by `cluster="calcifer-home"` returned non-empty
results.

Thanos Receive uses `1Gi` of memory and
`--tsdb.out-of-order.time-window=2h` to tolerate temporary sample reordering
during replay. This window does not replace Alloy WAL retention: remote write
keeps `sample_age_limit="48h"`, while the dedicated 16Gi local PVC remains a
bounded buffer that must be monitored against the measured ingest rate.

## Ingest credential rotation

Generate separate replacement BasicAuth credentials for Thanos and Loki,
update the Cloud `*-home-ingest-auth.sops.yaml` files and the Home
`home-ingest-credentials.sops.yaml` file, apply the Cloud secrets and Home
HelmRelease in a coordinated rollout, then verify authenticated ingest and
that the old credentials are rejected. Never place plaintext credentials in
the repository or command output.

## Rollback

Remove or suspend only the Home observability root and the Cloud private
ingest routes, NodePort and related policies. Keep the existing Cloud
backends, Grafana, public Grafana route, Azure object stores and their
retention unchanged. Restore the WireGuard peer firewall rules only after
confirming no Home collector still targets the private NodePort.
