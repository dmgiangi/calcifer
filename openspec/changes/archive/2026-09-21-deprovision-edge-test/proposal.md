# Proposal

## Why

`edge-test` è un endpoint operativo non più necessario e mantiene un namespace
dedicato, route, certificati, DNS e Secret cifrati in entrambi i cluster. La sua
rimozione riduce il rumore infrastrutturale e impedisce che un health endpoint
di test venga mantenuto come parte implicita dell'edge Cloud/Home.

## What Changes

- **BREAKING**: deprovisionare completamente il namespace `edge-test` in
  `calcifer-cloud` e `calcifer-home`.
- Rimuovere il backend Home, i Service, l'EndpointSlice Cloud, le route Traefik
  e i relativi manifest Kustomize.
- Rimuovere il `DNSEndpoint` LAN e i certificati staging/production per
  `edge-test.calcifer.tech`.
- Rimuovere i Secret SOPS `basic-auth` dedicati all'endpoint.
- Aggiornare la documentazione operativa per eliminare verifiche, rollback e
  riferimenti al percorso `edge-test`.
- Conservare invariati gli endpoint e le route degli altri servizi Home e il
  transit privato Cloud/Home.

## Capabilities

### New Capabilities

Nessuna.

### Modified Capabilities

- `cloud-edge-home-routing`: ridurre l'insieme dei servizi Home pubblicati
  dall'edge Cloud rimuovendo `edge-test` e il relativo percorso LAN.

## Impact

- Manifest Kubernetes e Kustomize sotto `clusters/calcifer-cloud/apps/edge-test`
  e `clusters/calcifer-home/apps/edge-test`.
- Aggregatori `clusters/calcifer-cloud/apps/kustomization.yaml` e
  `clusters/calcifer-home/apps/kustomization.yaml`.
- Flux `cloud-apps` e `home-apps`, entrambi configurati con `prune: true`, che
  elimineranno le risorse rimosse dal desired state.
- DNS LAN e certificati Azure DNS per `edge-test.calcifer.tech`.
- `docs/home-edge-operations.md`.
- Nessuna nuova dipendenza, API o credenziale.
