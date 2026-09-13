# Cloud observability

The cloud observability stack uses Grafana Alloy, Thanos, Loki, and Grafana
Operator in the `monitoring` namespace.

Deployment settings:

- Grafana hostname: `grafana.calcifer.tech`
- Metrics retention: 30 days at raw and 5-minute resolution; 1 hour blocks are
  retained for 1 year.
- Logs retention: 30 days, enforced by Loki compactor.
- Azure containers: private `thanos` and `loki` containers in `calciferobs`.
- Azure lifecycle deletion is intentionally not configured; Thanos Compactor
  and Loki retention own deletion for their respective data.
- Thanos uses its container-scoped SAS credential. Loki uses a dedicated Azure
  service principal with `Storage Blob Data Contributor` scoped to only the
  `loki` container because the pinned Loki Azure client has an upstream SAS
  connection-string bug; all identity credentials remain SOPS-encrypted.

The Grafana Operator, Alloy, Loki, and Thanos Community charts are pinned to
the versions in `helmrepositories.yaml`/`helmreleases.yaml`. The local
dashboards are version-matched operational fallbacks: the Thanos dashboard
targets Thanos v0.42.4, while the Loki dashboard targets Loki v3.7.7.
Upstream Loki/Thanos mixin dashboards were not vendored because their
recording rules and component panels assume
components not deployed on this single-node cluster. The fallback dashboards
use only metrics and labels collected here and are provisioned in the
`Observability` folder.

The service-account token is generated at runtime by Grafana Operator in the
`monitoring` namespace. Rotate it by changing the token expiry in
`grafana/grafana-service-account.yaml` before `2027-09-13`.
