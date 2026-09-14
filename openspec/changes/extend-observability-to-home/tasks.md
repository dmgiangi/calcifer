## 1. Prerequisiti e decisioni operative

- [x] 1.1 Verificare capacità, StorageClass e topologia dei nodi `calcifer-home` e fissare la dimensione della PVC/volume WAL per almeno 48 ore di buffering misurato.
- [x] 1.2 Verificare il source address osservato dal nodo Cloud per traffico originato da un Pod Home e scegliere `hostNetwork` oppure SNAT esplicito per Alloy Home.
- [x] 1.3 Fissare i nomi `thanos-ingest.calcifer.tech` e `loki-ingest.calcifer.tech` oppure documentare i nomi alternativi e la loro risoluzione interna verso l'indirizzo WireGuard Cloud.
- [x] 1.4 Generare credenziali BasicAuth separate per ingest Thanos e Loki e salvarle esclusivamente in Secret SOPS per i cluster interessati.
- [x] 1.5 Verificare che i certificati TLS per gli endpoint di ingest possano essere emessi dall'issuer Azure DNS senza creare esposizione pubblica dei backend.

## 2. Transit privato e ingest Cloud

- [x] 2.1 Estendere i manifesti WireGuard e le route host soltanto per il traffico Home→Cloud necessario agli endpoint HTTPS di ingest.
- [x] 2.2 Applicare la modalità `hostNetwork` o le regole SNAT scelte e verificare le rotte di ritorno senza aggiungere Pod CIDR o Service CIDR ai peer.
- [x] 2.3 Creare gli endpoint HTTPS Traefik separati per Thanos Receive `/api/v1/receive` e Loki `/loki/api/v1/push`, con certificati TLS, BasicAuth e allowlist della sorgente WireGuard.
- [x] 2.4 Collegare gli endpoint Traefik ai Service ClusterIP dei backend e aggiornare le NetworkPolicy per consentire soltanto il traffico dal proxy autorizzato.
- [x] 2.5 Verificare dal nodo e da un Pod Home che gli endpoint accettino richieste autorizzate e rifiutino richieste senza sorgente WireGuard, TLS valido o credenziali corrette.
- [x] 2.6 Verificare che query, admin API, Thanos Store Gateway, Loki API e Azure Blob non siano raggiungibili tramite le route di ingest.

## 3. Raccolta observability su Home

- [x] 3.1 Creare il root Kustomize/Flux per `calcifer-home/apps/observability` e includere namespace, HelmRepository e risorse Alloy senza modificare il root Cloud esistente.
- [x] 3.2 Installare su Home le sorgenti metriche necessarie per kube-state, nodo, kubelet/cAdvisor e workload con requests/limits espliciti.
- [x] 3.3 Creare RBAC e ServiceAccount Alloy Home con i permessi minimi per discovery Kubernetes, proxy kubelet e raccolta dei log Pod.
- [x] 3.4 Configurare Alloy Home per applicare `cluster="calcifer-home"` e inoltrare direttamente metriche a Thanos Receive tramite remote write autenticato.
- [x] 3.5 Configurare Alloy Home per applicare le label log stabili e inoltrare direttamente a Loki tramite `loki.write` autenticato.
- [x] 3.6 Configurare `storagePath` su volume persistente dedicato e abilitare/verificare la WAL remote write per metriche e log nella versione Alloy pinned.
- [x] 3.7 Configurare metriche Alloy per scrape failure, retry, backlog, WAL usage, dropped samples e dropped log entries.
- [x] 3.8 Verificare che restart o ricreazione del Pod Alloy Home preservi posizioni log e dati ancora non consegnati.

## 4. Modello multi-cluster e Grafana

- [x] 4.1 Verificare che Thanos e Loki preservino e indicizzino correttamente `cluster="calcifer-cloud"` e `cluster="calcifer-home"` senza collisioni.
- [x] 4.2 Aggiornare variabili, filtri, legende e query delle dashboard Grafana per selezionare entrambi i cluster invece di assumere soltanto Cloud.
- [x] 4.3 Aggiungere pannelli per stato del tunnel/ingest, retry e backlog dell’Alloy Home, saturazione del volume WAL e drop di dati.
- [x] 4.4 Mantenere Grafana, Grafana Operator, authorization server, OIDC, ForwardAuth e datasource locali sulla VPS senza introdurre endpoint pubblici per i backend.
- [x] 4.5 Aggiungere un dashboard/API check che mostri metriche e log per entrambi i cluster attraverso le datasource Grafana esistenti.

## 5. Validazione e rollout

- [x] 5.1 Renderizzare i root Kustomize Cloud e Home e verificare che tutti i Secret siano SOPS-encrypted e che nessuna credenziale raw sia tracciata.
- [x] 5.3 Emettere una metrica e una riga di log identificabili da un workload Home e verificarne l’arrivo nei backend.
- [x] 5.4 Usare l’API autenticata di Grafana per eseguire query PromQL e LogQL sui dati `calcifer-home` e verificare risultati non vuoti.
- [x] 5.5 Bloccare il tunnel Home→Cloud per 120 secondi, verificare crescita delle WAL e segnali di retry/backlog senza stampare credenziali.
- [x] 5.7 Eseguire test negativi da Internet e da sorgenti non autorizzate per confermare che ingest, query e admin backend restino protetti.
- [x] 5.8 Verificare CPU, memoria, capacità disco e crescita delle WAL su Home e Cloud; fermare il rollout se il buffering o la raccolta genera pressione di risorse.
- [x] 5.9 Documentare il limite di retention locale, la procedura di rotazione delle credenziali ingest e il rollback rimuovendo soltanto le risorse Home e le route private.
