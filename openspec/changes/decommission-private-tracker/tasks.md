# Tasks

## 1. Preserve generic authorization behavior

- [ ] 1.1 Remove the client-ID-specific placeholder-secret branch and its product-specific tests; verify blank and invalid generic client configuration still fails validation.
- [ ] 1.2 Replace product-named client policy and OIDC fixtures with synthetic clients; verify group, canonical-subject, authentication-method, default administrator, code-exchange, and machine-client cases remain covered.
- [ ] 1.3 Replace product-named shared-state tests with generic synthetic identities; verify serialized cross-edge sessions retain trusted identity/provenance and native-image tests pass.

## 2. Remove service-specific authorization configuration

- [ ] 2.1 Remove the app-only authorization profile activation from both edges, its client registration, and the service-only user/group/role entries; verify the default profile starts with existing clients and users only.
- [ ] 2.2 Remove only the associated keys from the app and authorization encrypted Secret manifests using SOPS; verify key names and Kustomize builds without printing decrypted values or changing unrelated secrets.

## 3. Remove application and delivery integration

- [ ] 3.1 Remove the private service module and its generated artifacts; verify no service-specific build target remains and unrelated modules still build.
- [ ] 3.2 Remove the Cloud workload resources and child reconciliation while retaining the persistent claim and its prune-disable annotation; verify rendered Cloud configuration excludes the workload and preserves the claim.
- [ ] 3.3 Remove the service release workflow and its dependency/image updater mappings; verify updater tests and generic authorization-server promotion behavior still pass.
- [ ] 3.4 Remove service-only helper scripts and operating/acceptance/recovery documentation; verify remaining operational documentation and script tests are valid.

## 4. Remove stale OpenSpec references

- [ ] 4.1 Remove the obsolete active product change rather than archiving it, and generalize affected archived authorization examples; verify no unrelated archived requirements are lost.
- [ ] 4.2 Apply the generic authorization-user and declarative-client spec deltas, then archive this neutral change after implementation; verify canonical specs retain the generic requirements and archived artifacts contain no stale product naming.

## 5. Integrated verification and safety

- [ ] 5.1 Run the authorization-server unit/integration suite, native compatibility checks, and updater tests; verify generic policy, restored-session, administrator, and machine-client contracts pass.
- [ ] 5.2 Build affected Cloud and Home Kustomizations with output suppressed and run strict OpenSpec validation; verify both edge configurations remain equivalent and no encrypted values are emitted.
- [ ] 5.3 Search tracked repository files case-insensitively for product-name variants using path-only output; verify there are no matches and Git history was not rewritten.
- [ ] 5.4 Verify the persistent claim and prune-disable annotation remain in the diff, and confirm no live cluster, database, backup, or restore operation was performed.