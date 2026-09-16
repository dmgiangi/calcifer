## Why

Il sistema non dispone di un punto di ingresso unico per i link dei servizi né di una vista semplice della loro raggiungibilità. Inoltre, aggiungere un nuovo client OIDC all'Authorization Server richiede oggi modifiche al codice, mentre Homepage deve poter essere autenticata tramite `auth.calcifer.tech` sia nel cluster Cloud sia nel cluster Home.

## What Changes

- Aggiungere Homepage come dashboard dichiarativa, deployata in modo equivalente su `calcifer-cloud` e `calcifer-home`.
- Pubblicare la dashboard su `https://calcifer.tech`, con risoluzione split-horizon già prevista per l'accesso dalla LAN.
- Gestire il catalogo dei servizi tramite `services.yaml` in ConfigMap, senza discovery Kubernetes o RBAC aggiuntivo per Homepage.
- Configurare controlli di raggiungibilità HTTP tramite `siteMonitor` per Authorization Server, Grafana e futuri servizi.
- Estendere l'Authorization Server per caricare client OAuth/OIDC da configurazione YAML esterna e registrarli all'avvio.
- Separare configurazione non sensibile e secret: redirect URI, scope e grant type in ConfigMap; client secret in Secret gestito con SOPS.
- Registrare il client OIDC di Homepage in modo dichiarativo, con riavvio del pod accettabile quando cambia la configurazione.

## Capabilities

### New Capabilities

- `homepage-dashboard`: catalogo dichiarativo dei servizi, accesso su `calcifer.tech` e monitoraggio HTTP deployato sui due cluster.
- `declarative-oidc-clients`: configurazione e registrazione dei client OAuth/OIDC dell'Authorization Server tramite file YAML esterno e secret Kubernetes.

### Modified Capabilities

Nessuna.

## Impact

- Nuovi manifest Kustomize sotto `clusters/apps/homepage/` con base condivisa e overlay Cloud/Home.
- Modifiche ai manifest e alla configurazione dell'Authorization Server per montare la configurazione dei client e i relativi secret.
- Modifiche al codice Java dell'Authorization Server e relativi test per il binding e la registrazione dinamica dei client.
- Nessuna nuova dipendenza applicativa prevista e nessun accesso RBAC Kubernetes richiesto da Homepage.
- Il rollout di una modifica ai client OIDC comporterà il riavvio controllato dell'Authorization Server.
