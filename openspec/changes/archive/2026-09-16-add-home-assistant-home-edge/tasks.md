## 1. OIDC Client Registration in Authorization Server

- [x] 1.1 Declare `home-assistant` OIDC client in `clusters/apps/authorization-server/base/clients.yaml` with PKCE, code grant, and redirect URI
- [x] 1.2 Define client secret in authorization server secrets configuration
- [x] 1.3 Validate authorization-server client parsing and configuration tests

## 2. Home Assistant Workload & Storage (`calcifer-home`)

- [x] 2.1 Create application namespace, kustomization, and PVC on `local-path` for Home Assistant in `clusters/calcifer-home/apps/home-assistant/`
- [x] 2.2 Create `configuration.yaml` ConfigMap with `http.trusted_proxies` and `use_x_forwarded_for` for Traefik
- [x] 2.3 Configure `hass-oidc-auth` integration and provider settings in Home Assistant configuration
- [x] 2.4 Create Home Assistant Deployment (single replica, `Recreate` strategy) and ClusterIP Service
- [x] 2.5 Wire `home-assistant` into `clusters/calcifer-home/kustomizations/`

## 3. Ingress, TLS & LAN DNS (`calcifer-home`)

- [x] 3.1 Create cert-manager `Certificate` for `home.calcifer.tech` in `calcifer-home`
- [x] 3.2 Create Traefik `IngressRoute` on `calcifer-home` routing `home.calcifer.tech` to Home Assistant with WebSocket support
- [x] 3.3 Create `DNSEndpoint` resource for `home.calcifer.tech` pointing to Home LAN IP `192.168.0.102`
- [x] 3.4 Add Home Assistant service entry to Homepage dashboard in `clusters/apps/homepage/base/services.yaml`

## 4. Cloud Edge Routing & TLS (`calcifer-cloud`)

- [x] 4.1 Create `Service` and `EndpointSlice` targeting Home WireGuard transit (`172.31.255.2:443`) in `clusters/calcifer-cloud/apps/home-assistant/`
- [x] 4.2 Create cert-manager `Certificate` and Traefik `ServersTransport` on `calcifer-cloud`
- [x] 4.3 Create Cloud Traefik `IngressRoute` for `home.calcifer.tech` forwarding to the transit service
- [x] 4.4 Wire `home-assistant` edge into `clusters/calcifer-cloud/kustomizations/` and document public Azure DNS record

## 5. Automated Azure Blob Backup (`calcifer-home`)

- [x] 5.1 Configure Azure Blob Storage container for Home Assistant configuration backups
- [x] 5.2 Create SOPS-encrypted Secret on `calcifer-home` with container-scoped Azure credentials
- [x] 5.3 Implement scheduled backup `CronJob` with consistent SQLite snapshotting and Azure Blob upload
- [x] 5.4 Document restore and disaster recovery verification procedures
- [x] 5.5 Route Azure backup traffic through a destination-restricted Cloud egress proxy over WireGuard
