## Why

`calcifer-home` non dispone di una raccolta centralizzata di metriche e log, mentre `calcifer-cloud` possiede già Thanos, Loki e Grafana funzionanti. La change estende la raccolta al cluster Home mantenendo i backend, Grafana, l'autenticazione e la persistenza Azure sulla VPS, così da evitare una seconda piattaforma observability e mantenere invariato il percorso verso Azure Blob.

## What Changes

- Aggiungere un Alloy gestito dal root GitOps su `calcifer-home` per raccogliere metriche Kubernetes, metriche dei nodi e log dei workload.
- Inviare direttamente da Alloy Home le metriche a Thanos Receive e i log a Loki sulla VPS attraverso il transit WireGuard privato.
- Configurare buffering persistente su disco per Alloy Home durante l'indisponibilità del tunnel o dei backend.
- Esporre sulla VPS endpoint HTTPS privati e autenticati per l'ingest remoto di Thanos e Loki, senza pubblicare i backend su Internet.
- Consentire al Thanos/Loki esistente di conservare e interrogare dati con label distinte per `calcifer-cloud` e `calcifer-home`.
- Rendere le dashboard Grafana multi-cluster e aggiungere la verifica dei dati provenienti da Home.
- Definire metriche, allarmi operativi e un test controllato del backlog WAL durante la perdita del tunnel e della saturazione del disco locale Home.
- Estendere il routing e le policy del transit senza instradare arbitrariamente Pod CIDR o Service CIDR tra i cluster.
- **BREAKING**: gli endpoint di ingest dei backend non saranno più accessibili soltanto da Pod Alloy nel cluster Cloud; dovranno accettare esclusivamente il flusso autenticato proveniente dal transit Home.

## Capabilities

### New Capabilities

- `home-observability-collection`: raccolta di metriche e log del cluster Home tramite Alloy e inoltro ai backend centralizzati.
- `observability-private-ingest`: ingest HTTPS privato e autenticato da Home verso Thanos Receive e Loki sulla VPS.
- `multi-cluster-observability`: identificazione, query e dashboard dei dati provenienti da entrambi i cluster.

### Modified Capabilities

<!-- Nessuna specifica principale esistente definisce ancora il comportamento multi-cluster dell'observability. -->

## Impact

- Aggiunge un'applicazione observability sotto `clusters/calcifer-home/apps/` e la relativa inclusione Flux.
- Modifica gli Alloy e le NetworkPolicy/IngressRoute di `calcifer-cloud` per supportare ingest dal transit Home.
- Modifica configurazione e dashboard Grafana nella change observability esistente, senza spostare Grafana o i backend.
- Richiede connettività bidirezionale esplicita Home→Cloud sul tunnel WireGuard, inclusi routing/SNAT o `hostNetwork` per il traffico dei Pod.
- Introduce credentiali di ingest separate, conservate esclusivamente in Secret SOPS-encrypted.
- Mantiene invariati i container Azure Blob, le retention dei backend e l'allowlist Azure dell'IP VPS.
- Richiede acceptance test su entrambi i cluster, inclusi un test controllato di disconnessione del tunnel e verifica tramite Grafana API dei dati disponibili.
