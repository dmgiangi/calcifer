## Why

Home automation and local IoT device management require an instance of Home Assistant running locally on `calcifer-home`, while remaining securely accessible both from the home LAN and remotely over the internet at `https://home.calcifer.tech`. Exposing the service remotely must leverage the established cloud-edge architecture without opening ports on the residential router, while authentication should integrate with the existing `authorization-server` SSO infrastructure and operational state must be protected with offsite backups on Azure.

## What Changes

- Deploy Home Assistant on `calcifer-home` with persistent configuration storage, trusted reverse proxy integration, and Traefik ingress routing.
- Expose `home.calcifer.tech` via split-horizon DNS: direct LAN routing (`192.168.0.102`) for local clients and Cloud Traefik edge routing over the WireGuard private transit for external internet traffic.
- Issue valid TLS certificates for `home.calcifer.tech` on both `calcifer-home` and `calcifer-cloud` using cert-manager and Let's Encrypt with Azure DNS validation.
- Configure an OIDC client registration in `authorization-server` for Home Assistant and integrate the community `hass-oidc-auth` component, while preserving local admin authentication for break-glass recovery.
- Establish an automated, encrypted disaster recovery backup job on `calcifer-home` backing up the Home Assistant `/config` volume to Azure Blob Storage through restricted static egress on `calcifer-cloud`.
- Publish Home Assistant in the Homepage service dashboard on `calcifer.tech` with health monitoring.

## Capabilities

### New Capabilities
- `home-assistant-workload`: Deployment, persistent storage, reverse proxy configuration, and ingress routing for Home Assistant on `calcifer-home` and `calcifer-cloud`.
- `home-assistant-oidc-auth`: Single sign-on authentication for Home Assistant using `authorization-server` as an OIDC Identity Provider with `hass-oidc-auth` and local emergency fallback.
- `home-assistant-backup`: Automated periodic backup of Home Assistant configuration to Azure Blob Storage with SOPS-encrypted credentials.

### Modified Capabilities
<!-- Existing capabilities whose REQUIREMENTS are changing (not just implementation).
     Only list here if spec-level behavior changes. Each needs a delta spec file.
     Use existing spec names from openspec/specs/. Leave empty if no requirement changes. -->

## Impact

- **Infrastructure & GitOps**: New manifests in `clusters/calcifer-home/apps/home-assistant/` and routing configuration in `clusters/calcifer-cloud/apps/home-assistant/`.
- **Identity & Auth**: New declarative OIDC client definition in `clusters/apps/authorization-server/base/clients.yaml` and client secret management.
- **Networking & DNS**: New `DNSEndpoint` for local LAN resolution, Azure DNS public record for `home.calcifer.tech` pointing to Cloud VPS, and a WireGuard-only Azure backup egress proxy on Cloud.
- **Dashboard**: Published Home Assistant in the Homepage service catalog on both clusters.
- **Backups**: Azure Blob Storage container allocation and SOPS-encrypted backup credentials for `calcifer-home`.
