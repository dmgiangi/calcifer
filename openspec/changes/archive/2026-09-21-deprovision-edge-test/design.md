# Design

## Context

See `proposal.md` for the motivation and scope. The current `edge-test`
resources are declared under separate `apps/edge-test` directories in both
clusters and are included by their respective application aggregators. The
Cloud and Home Flux application Kustomizations both use `prune: true`, so the
Git desired state is also the mechanism that removes the live resources.

The Home route is more than a disposable Deployment: it has a LAN
`DNSEndpoint`, staging and production certificates, an authorization Secret,
and an ingress route. The Cloud side has a selectorless Service,
EndpointSlice, certificate, authorization Secret, and ingress configuration.

## Goals / Non-Goals

**Goals:**

- Remove the complete `edge-test` resource graph from both clusters.
- Ensure the public Cloud route is withdrawn before the Home backend and LAN
  DNS override are removed.
- Keep other Home edge services, private transit, cert-manager, and LAN DNS
  infrastructure unchanged.
- Make the operational documentation match the new topology.
- Leave a clear Git-revert path for restoring the endpoint if needed.

**Non-Goals:**

- Do not replace `edge-test` with another health endpoint.
- Do not consolidate the remaining workload namespaces in this change.
- Do not alter WireGuard, Traefik installation, cert-manager issuers, or the
  general Home LAN DNS resolver.
- Do not edit archived OpenSpec artifacts that describe the historical edge
  connectivity rollout.

## Decisions

### Use Git removal and Flux pruning as the deprovisioning mechanism

Remove the `edge-test` entries from both application aggregators and remove
the resource directories. Flux will prune the previously managed resources,
keeping the repository as the source of truth. Direct `kubectl delete` is not
the primary mechanism because it would be recreated by Flux while the
resources remain declared.

### Withdraw Cloud exposure before removing Home access

Apply the change in two logical reconciliation stages. First stop including
the Cloud `edge-test` resources so public traffic can no longer enter the
path. Then remove the Home resources, including the LAN `DNSEndpoint`,
certificates, backend, and namespace. This follows the existing rollback
ordering and avoids leaving a public route pointing at a backend being
decommissioned.

An alternative is deleting both application trees in one commit. That is
simpler, but it gives less operational control over the order in which the
two clusters reconcile.

### Remove the complete resource graph, including encrypted Secret manifests

Delete the SOPS `basic-auth` manifests along with the routes and workloads.
The encrypted files contain no plaintext credential, and the namespace prune
removes the corresponding live Secret. No replacement credential or external
dependency is required.

### Update only current operational documentation

Remove `edge-test` from active secret inventories, verification commands,
hostname checks, and rollback guidance in `docs/home-edge-operations.md`.
Archived OpenSpec documents remain historical records and are not changed.

## Risks / Trade-offs

- **[Risk] Flux reconciliation is delayed or fails, leaving resources live.**
  → Check both application Kustomization status and explicitly verify the
  namespace and resources after reconciliation.
- **[Risk] DNS or certificate cleanup is asynchronous.**
  → Verify the `DNSEndpoint` and Certificate resources are gone, then check
  the managed DNS record and allow for normal DNS TTL propagation.
- **[Risk] A rollback requires reissuing certificates and restoring DNS.**
  → Keep the change reversible through Git and document that rollback may not
  restore the endpoint immediately because cert-manager and DNS are
  asynchronous.
- **[Risk] A broad Kustomize edit accidentally removes another workload.**
  → Review rendered resources and the diff for both aggregators; only the
  `edge-test` entry and directory may be removed.

## Migration Plan

1. Remove the Cloud `edge-test` aggregator entry and reconcile `cloud-apps`.
2. Verify the Cloud Service, EndpointSlice, route, certificate, and namespace
   are pruned and that no public edge route remains.
3. Remove the Home `edge-test` aggregator entry and resource directory,
   including the backend, LAN `DNSEndpoint`, certificates, route, Secret, and
   namespace.
4. Reconcile `home-apps` and verify the Home resources and namespace are
   pruned.
5. Update the current edge operations documentation and validate both
   application Kustomizations.
6. If rollback is required, revert the Git change, reconcile Cloud first,
   then Home, and wait for DNS and certificate issuance to recover.

