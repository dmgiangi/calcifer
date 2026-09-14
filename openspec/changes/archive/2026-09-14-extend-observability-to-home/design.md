## Context

`calcifer-cloud` ospita già una piattaforma observability non-HA composta da
Grafana Alloy, Thanos Receive/Query/Store Gateway/Compactor, Loki, Grafana
Operator e Grafana. Thanos e Loki persistono in Azure Blob tramite credenziali
SOPS; Grafana e l'authorization server sono pubblicati sulla VPS tramite
Traefik.

`calcifer-home` dispone del transit WireGuard verso la VPS ma non ha ancora una
raccolta observability. Il transit attuale è intenzionalmente limitato a rotte
di edge tra gli indirizzi WireGuard e non collega Pod CIDR o Service CIDR. La
nuova configurazione deve quindi introdurre un flusso applicativo esplicito da
Home verso i backend Cloud senza trasformare il tunnel in una rete Kubernetes
condivisa.

## Goals / Non-Goals

**Goals:**

- Raccogliere metriche Kubernetes e log dei workload di `calcifer-home`.
- Centralizzare metriche e log di entrambi i cluster nei backend già presenti
  su `calcifer-cloud`.
- Conservare Grafana, Grafana Operator, authorization server e Azure Blob sulla
  VPS.
- Mantenere i backend non pubblici e raggiungibili da Home soltanto attraverso
  WireGuard e gli endpoint di ingest autorizzati.
- Garantire buffering locale su Home durante una perdita del tunnel o
  l'indisponibilità dei backend.
- Rendere le query e le dashboard Grafana distinguibili per cluster.

**Non-Goals:**

- Spostare Thanos, Loki, Grafana o l'authorization server su Home.
- Instradare i Pod CIDR o Service CIDR tra i cluster.
- Rendere pubblici Thanos, Loki o endpoint di ingest.
- Introdurre HA, replica multi-nodo, federazione Prometheus o un secondo
  backend observability.
- Cambiare la retention Azure già definita per Thanos e Loki.

## Decisions

### Keep the observability backends and Grafana on Cloud

Thanos, Loki e Grafana rimangono sulla VPS. Alloy Cloud continua a inviare ai
Service locali; Alloy Home è un nuovo collector remoto. Questa scelta evita di
duplicare i backend, mantiene invariato l'accesso Azure dall'IP già autorizzato
e lascia intatto il percorso Grafana/OIDC/ForwardAuth esistente.

Spostare i backend su Home è stato considerato, ma avrebbe richiesto un nuovo
egress Azure autorizzato, il trasferimento dei PVC e delle credenziali, query
remote da Grafana e una migrazione più ampia.

### Send from Home Alloy directly to the Cloud backends

Alloy Home invierà direttamente metriche a Thanos Receive e log a Loki. Alloy
Cloud non fungerà da relay: aggiungerebbe un hop, uno stato intermedio e una
nuova dipendenza senza migliorare la durabilità.

Il traffico applicativo attraverserà il solo indirizzo Cloud del tunnel
WireGuard. Gli endpoint saranno esposti tramite Traefik Cloud su HTTPS, con
hostnames separati e path nativi:

- `thanos-ingest.calcifer.tech` → `/api/v1/receive` → Thanos Receive;
- `loki-ingest.calcifer.tech` → `/loki/api/v1/push` → Loki.

I nomi saranno risolti da Alloy Home verso l'indirizzo WireGuard Cloud tramite
una configurazione DNS/host esplicita; non sarà necessario pubblicare un record
Internet utilizzabile per questi endpoint. Il traffico userà il NodePort HTTPS
privato `32443` del Service Traefik con `externalTrafficPolicy: Local`: questo
evita il MASQUERADE del LoadBalancer K3s pubblico e conserva a Traefik la
sorgente `172.31.255.2`. Il firewall del nodo Cloud consente il NodePort solo
dal device WireGuard; il middleware Traefik mantiene comunque l'allowlist del
peer.

### Protect remote ingest with WireGuard, TLS, source filtering, and BasicAuth

Il percorso remoto richiederà contemporaneamente:

1. transito WireGuard;
2. certificato TLS valido per il nome dell'endpoint;
3. allowlist della sorgente WireGuard prevista;
4. credenziali BasicAuth separate per Thanos e Loki.

Le credenziali saranno Secret SOPS distinti, disponibili soltanto ad Alloy Home
e alla configurazione Traefik necessaria. Le query e gli endpoint amministrativi
dei backend non saranno inclusi nelle route remote.

BasicAuth è preferita a un token condiviso perché è supportata direttamente dai
client Alloy e può essere validata da Traefik senza modificare Thanos o Loki.
WireGuard e TLS forniscono il confine di rete e trasporto; BasicAuth aggiunge
un confine applicativo e credenziali revocabili.

### Preserve explicit node-level routing and handle Pod source addresses

Alloy Home userà `hostNetwork: true` e `dnsPolicy: ClusterFirstWithHostNet`,
con il traffico originato dall'indirizzo WireGuard `172.31.255.2`. I due
hostname di ingest saranno inseriti come `hostAliases` nel Pod e risolti verso
`172.31.255.1`. Questa modalità evita SNAT aggiuntivo e mantiene l'allowlist
Traefik limitata al singolo peer WireGuard. Nessuna rotta Pod/Service CIDR sarà
aggiunta ai peer WireGuard.

### Persist Home Alloy state on local disk

Alloy Home userà un volume persistente locale dedicato per `storagePath`.
`prometheus.remote_write` userà la WAL per le metriche e `loki.write` userà la
WAL disponibile nella versione Alloy pinned per i log. Il volume non sarà
condiviso con altri workload e sarà monitorato per capacità, backlog e drop.

Il dimensionamento iniziale sarà una PVC `local-path` da 16Gi, pari a circa il
16% dello storage allocatable del nodo Home attualmente osservato. La capacità
è stata verificata con un test controllato di 120 secondi; il target operativo
di almeno 48 ore di indisponibilità del transit resta un obiettivo di capacity
planning da misurare sul volume reale dei log Home, non un gate della change.

La perdita del volume locale può creare un gap per i dati non ancora consegnati;
i dati già accettati da Thanos/Loki continuano a seguire le retention Azure
esistenti.

### Collect Home platform metrics with the same label contract

Home installerà le sorgenti metriche equivalenti a Cloud, includendo metriche
di stato Kubernetes e del nodo. Alloy applicherà sempre
`cluster="calcifer-home"` alle metriche e ai log Home; Cloud manterrà
`cluster="calcifer-cloud"`.

I label di log resteranno a bassa cardinalità (`cluster`, `namespace`, `pod`,
`container`) e le dashboard useranno una variabile cluster invece di query
hardcoded soltanto su Cloud.

### Keep Grafana and authentication local to Cloud

Le datasource Grafana continueranno a usare i Service ClusterIP locali. Non
servirà esporre Thanos o Loki per gli utenti Internet e non cambieranno
hostname, OIDC, ForwardAuth o token API Grafana.

La dashboard acceptance dovrà però verificare query PromQL e LogQL per entrambi
i valori di `cluster` e l'arrivo di un log emesso da un workload Home.

## Risks / Trade-offs

- [Il transito WireGuard accetta il nodo ma non il source IP del Pod] → Usare
  `hostNetwork` o SNAT esplicito, testare il source address osservato da
  Traefik e non allargare l'allowlist a intere reti Kubernetes.
- [Il disco WAL Home si riempie durante un'interruzione lunga] → Separare il
  volume, fissare una capacità massima, esporre metriche di utilizzo/backlog e
  documentare il limite di perdita accettabile.
- [Una route di ingest diventa accidentalmente pubblica] → IP allowlist del
  tunnel, BasicAuth, TLS, test da una sorgente Internet e nessuna route per
  query/admin.
- [La WAL log di Alloy cambia disponibilità o comportamento nella versione
  pinned] → Verificare la capability nella versione scelta prima del rollout e
  bloccare l'acceptance se il buffering log non è persistente.
- [Home e Cloud producono collisioni di label o dashboard incomprensibili] →
  Rendere `cluster` obbligatorio, validare le serie/log nei test e migrare le
  query hardcoded a variabili.
- [Il backend VPS rimane un singolo failure domain] → Conservare buffering
  Home, monitorare la salute del tunnel e mantenere la retention Azure; HA e
  backend locale Home restano change future.

## Migration Plan

1. Creare Secret SOPS e certificati necessari per gli endpoint privati senza
   modificare le route pubbliche esistenti.
2. Aggiungere le route Traefik Cloud, le policy e il test locale dai Pod Cloud
   verso Thanos/Loki.
3. Installare namespace, HelmRepository, metric exporters e Alloy su Home con
   un volume persistente dedicato.
4. Verificare prima il flusso di metriche e poi quello dei log da Home; in caso
   di errore rimuovere soltanto il root observability Home.
5. Aggiornare label, dashboard e acceptance Grafana per entrambi i cluster.
6. Simulare la perdita del tunnel, verificare crescita della WAL e ripristino
   dell'invio senza stampare credenziali.
7. Rollback rimuovendo il root Home e le route private Cloud; i backend Cloud,
   Grafana e i dati Azure restano invariati.

## Open Questions

- Misurare nel tempo il rate reale di ingest Home e confermare che la PVC da
  16Gi sia sufficiente per il target operativo di 48 ore; questa attività non
  blocca l'acceptance test controllato della change.
