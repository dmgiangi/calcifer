# Tasks

## 1. Remove the Cloud edge route

- [x] 1.1 Remove `edge-test` from `clusters/calcifer-cloud/apps/kustomization.yaml` and verify the Cloud application Kustomization no longer includes the workload
- [x] 1.2 Reconcile `cloud-apps` and verify the Cloud `edge-test` Service, EndpointSlice, ingress route, Certificate, Secret, and namespace are pruned
- [x] 1.3 Verify that `edge-test.calcifer.tech` no longer has a Cloud-managed route and that other Cloud/Home edge routes remain unchanged

## 2. Remove the Home backend and namespace

- [x] 2.1 Remove `edge-test` from `clusters/calcifer-home/apps/kustomization.yaml` and verify the Home application Kustomization no longer includes the workload
- [x] 2.2 Remove the `clusters/calcifer-cloud/apps/edge-test` and `clusters/calcifer-home/apps/edge-test` resource directories, including encrypted `basic-auth` manifests, and verify no non-archived active manifest references remain
- [x] 2.3 Reconcile `home-apps` and verify the Home Deployment, Service, ingress routes, `DNSEndpoint`, Certificates, Secret, and `edge-test` namespace are pruned
- [x] 2.4 Verify that the LAN `DNSEndpoint` and any edge-test-specific public DNS record are removed; preserve the shared `*.calcifer.tech` wildcard required by other services

## 3. Update operational documentation

- [x] 3.1 Remove `edge-test` from the active Secret inventory, verification commands, hostname checks, and rollback guidance in `docs/home-edge-operations.md`
- [x] 3.2 Verify that the documentation still describes the remaining private transit, LAN DNS, certificate, and edge-service operations correctly

## 4. Validate the resulting desired state

- [x] 4.1 Render or validate both `clusters/calcifer-cloud/apps` and `clusters/calcifer-home/apps` Kustomizations and verify no unrelated resources are removed
- [x] 4.2 Run the OpenSpec validation for `deprovision-edge-test` and verify the proposal, spec delta, design, and task checklist are coherent
- [x] 4.3 Confirm the final diff is limited to the `edge-test` deprovisioning and its current documentation, with archived OpenSpec artifacts unchanged
