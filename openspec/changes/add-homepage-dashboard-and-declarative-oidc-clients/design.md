## Context

Il repository gestisce due cluster K3s, `calcifer-cloud` e `calcifer-home`, con manifest condivisi e overlay per ambiente. L'Authorization Server usa Spring Boot/Spring Authorization Server e oggi espone client configurati nel codice/configurazione esistente. Il DNS LAN usa già il dominio `calcifer.tech` con split-horizon; il dashboard deve quindi usare lo stesso hostname canonico in entrambi gli ambienti.

## Goals / Non-Goals

**Goals:**

- Deployare Homepage con una base comune e overlay Cloud/Home.
- Rendere il catalogo dei servizi completamente dichiarativo e uguale nei due cluster.
- Verificare la raggiungibilità tramite URL HTTP canonici, senza collegare Homepage all'API Kubernetes.
- Permettere l'aggiunta di client OAuth/OIDC tramite configurazione YAML montata e secret separati.
- Usare il client dichiarativo per proteggere Homepage tramite `auth.calcifer.tech`.

**Non-Goals:**

- Discovery automatica tramite annotazioni Kubernetes o integrazione RBAC di Homepage.
- Riutilizzo diretto dello stato interno di readiness/liveness Kubernetes come stato mostrato nella dashboard.
- Hot reload dei client OIDC senza riavvio del pod.
- Modifica del protocollo o del modello di identità esistente dell'Authorization Server.

## Decisions

### Catalogo Homepage statico

Il catalogo sarà un `services.yaml` in ConfigMap, gestito nella base Kustomize e montato nel pod Homepage. La base conterrà i servizi condivisi, inclusi Authorization Server e Grafana; gli overlay potranno differenziare solo i dettagli strettamente dipendenti dall'ambiente. Questa scelta è preferita alla discovery Kubernetes perché mantiene il comportamento identico tra cluster e non richiede ServiceAccount, ClusterRole o accesso all'API.

### Monitoraggio HTTP

Ogni servizio censito userà `siteMonitor` verso il proprio hostname HTTPS canonico. Il controllo misurerà la catena DNS, TLS, Traefik e applicazione realmente utilizzata dall'utente. Non verranno esposti endpoint Kubernetes interni né usati direttamente i probe di un workload come sorgente di stato della dashboard.

### Autenticazione OIDC Homepage

Homepage userà OIDC nativo contro l'issuer `https://auth.calcifer.tech`. Il redirect URI del client sarà quello canonico di Homepage su `https://calcifer.tech`; il client sarà configurato come authorization-code con PKCE e scope OIDC standard. Il secret del client e l'eventuale secret di sessione Homepage resteranno in Secret SOPS, mentre gli attributi non sensibili resteranno in ConfigMap.

### Client OIDC dichiarativi

L'Authorization Server binderà una mappa di definizioni client da proprietà Spring (`identity.clients`). Le definizioni potranno arrivare da un file YAML esterno importato con `spring.config.import`; i placeholder `${...}` consentiranno di risolvere i secret da variabili d'ambiente senza salvarli nel ConfigMap. Il repository `RegisteredClient` verrà costruito all'avvio iterando le definizioni e traducendo grant type, authentication method, redirect URI, scope, PKCE e TTL.

Le definizioni esistenti di Grafana saranno migrate o rappresentate nello stesso modello senza alterare i loro client ID, grant, scope, audience o callback. L'audience dei token verrà risolta dalla definizione del client richiedente, con comportamento di fallback compatibile per client esistenti.

### Rollout e riavvio

La modifica a ConfigMap o Secret produrrà un nuovo rollout dell'Authorization Server tramite checksum/referenza Kustomize già coerente con i pattern del repository. Il caricamento resta intenzionalmente startup-only: il riavvio è accettabile e rende deterministico il set di client attivo.

## Risks / Trade-offs

- **[Risk]** Un errore YAML o un placeholder secret mancante può impedire l'avvio dell'Authorization Server. → **Mitigation:** validazione con test di binding/registrazione e rollout controllato; mantenere i secret obbligatori espliciti negli esempi SOPS.
- **[Risk]** Il monitor HTTP può segnalare Grafana non raggiungibile da Home durante un'interruzione verso Cloud. → **Mitigation:** è il comportamento desiderato, perché rappresenta la raggiungibilità reale dal cluster/client.
- **[Risk]** Il catalogo statico può diventare obsoleto quando cambia un servizio. → **Mitigation:** rendere `services.yaml` parte della stessa revisione GitOps del relativo ingress e usare URL canonici stabili.
- **[Risk]** Un client dichiarato con redirect URI errato può essere inutilizzabile. → **Mitigation:** testare la registrazione e documentare il callback Homepage nel ConfigMap.

## Migration Plan

1. Implementare e testare il modello client generico mantenendo i client Grafana attuali.
2. Aggiungere il file ConfigMap dei client e il secret SOPS per Homepage nell'Authorization Server.
3. Deployare Homepage con autenticazione disabilitata solo durante il bootstrap, se necessario, quindi abilitarla nello stesso rollout configurato.
4. Verificare login OIDC, callback, accesso LAN tramite split-horizon e monitor HTTP in entrambi i cluster.
5. In caso di rollback, rimuovere il client Homepage e i manifest Homepage; i client Grafana restano disponibili tramite la configurazione compatibile.

## Open Questions

- Confermare la versione/tag dell'immagine Homepage da usare nel repository.
- Confermare gli endpoint HTTP pubblici da usare per i monitor di Authorization Server e Grafana, in particolare se serve un path health dedicato o se basta l'URL principale.
