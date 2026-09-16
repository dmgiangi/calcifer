## Context

`calcifer` operates two K3s clusters: `calcifer-cloud` (VPS edge) and `calcifer-home` (LAN node at `192.168.0.102`). They are connected via a WireGuard private transit (`172.31.255.1` <-> `172.31.255.2`). Home Assistant must run on `calcifer-home` to access local network devices, while being reachable seamlessly from both LAN and Internet under `https://home.calcifer.tech`.

## Goals / Non-Goals

**Goals:**
- Deploy Home Assistant Core on `calcifer-home` with persistent storage on `local-path`.
- Configure dual ingress: direct LAN routing via split-horizon CoreDNS (`192.168.0.102`) and public Internet routing via Cloud Traefik -> WireGuard transit -> Home Traefik.
- Terminate TLS with valid Let's Encrypt certificates issued via cert-manager and Azure DNS on both edges.
- Publish Home Assistant in the unified Homepage service dashboard with health monitoring.
- Integrate OIDC single sign-on with `authorization-server` using the community `hass-oidc-auth` integration, while keeping local user/password fallback for emergency break-glass.
- Implement an automated, encrypted disaster recovery backup of `/config` to Azure Blob Storage.

**Non-Goals:**
- Home Assistant OS / Supervised: Add-ons are replaced by native Kubernetes workloads.
- Traefik forward-auth proxying: Avoided to prevent breaking mobile app notifications, WebSockets, and local device webhooks.
- Inbound port-forwarding on the home residential router.

## Decisions

### 1. Ingress and Transit Routing (Mirroring `edge-test`)
- **Decision**: Cloud Traefik acts as a public TLS edge forwarding over WireGuard (`172.31.255.2:443`) to Home Traefik. Home Traefik terminates TLS directly for both Cloud transit and direct LAN connections (`192.168.0.102`).
- **Rationale**: Eliminates residential port forwarding, keeps IP addresses private, and ensures uniform TLS validation.

### 2. Authentication: Declarative OIDC + `hass-oidc-auth` + Local Fallback
- **Decision**: Register a declarative client `home-assistant` in `authorization-server` with PKCE, authorization code grant, and redirect URI `https://home.calcifer.tech/auth/oidc/callback`. Home Assistant configures `hass-oidc-auth` in `configuration.yaml` alongside the default `homeassistant` auth provider.
- **Rationale**: Enables centralized SSO for web and companion app workflows without intercepting API/WebSocket traffic at the proxy level. Local fallback ensures availability during WAN/IdP outages.

### 3. Backup: Dedicated Azure Blob Backup CronJob
- **Decision**: Deploy a scheduled Kubernetes CronJob on `calcifer-home` mounting `/config`, creating a consistent SQLite snapshot, and syncing/archiving to a private Azure Blob container in `calciferobs` using SOPS-encrypted credentials. Route Azure HTTPS through an HTTP CONNECT proxy bound to the Cloud WireGuard address and restricted to the Home transit IP and the storage account endpoint.
- **Rationale**: `calcifer-home` does not currently run Velero. A lightweight containerized backup job avoids heavy Velero node-agent dependencies while achieving offsite disaster recovery on Azure. Cloud proxy egress presents the VPS static IP to the Azure firewall despite the dynamic residential Home IP, while preserving end-to-end TLS.

### 4. Networking: Standard Pod Networking
- **Decision**: Run Home Assistant with standard Kubernetes pod networking, exposed via ClusterIP Service to Traefik.
- **Rationale**: Simplifies network policies, port management, and service routing. If mDNS/SSDP broadcast discovery is needed for specific IoT hardware later, an mDNS reflector or helper can be deployed without giving the core container host privileges.

## Risks / Trade-offs

- **[Risk]** `hass-oidc-auth` breaking across Home Assistant core upgrades → **Mitigation**: Pin container image tags and custom component releases; maintain active local admin credentials for break-glass.
- **[Risk]** SQLite database corruption during live volume backup → **Mitigation**: Backup script executes SQLite backup (`sqlite3 /config/home-assistant_v2.db ".backup /tmp/ha_backup.db"`) before archiving.
- **[Risk]** Traefik reverse proxy blocked by Home Assistant default security → **Mitigation**: Configure `http.use_x_forwarded_for: true` and `http.trusted_proxies` in `configuration.yaml` with Traefik pod CIDR and node IP.
- **[Risk]** The Cloud egress proxy becomes a general-purpose relay → **Mitigation**: Bind only to the WireGuard address, allow only source `172.31.255.2`, and match only HTTP CONNECT authority `calciferobs.blob.core.windows.net:443`.
