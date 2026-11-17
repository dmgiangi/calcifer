# Tasks

## 1. Preserve generic authorization behavior

- [x] 1.1 Remove the client-ID-specific placeholder-secret branch and its product-specific tests; verify blank and invalid generic client configuration still fails validation.
- [x] 1.2 Replace product-named client policy and OIDC fixtures with synthetic clients; verify group, canonical-subject, authentication-method, default administrator, code-exchange, and machine-client cases remain covered.
- [x] 1.3 Replace product-named shared-state tests with generic synthetic identities; verify serialized cross-edge sessions retain trusted identity/provenance and native-image tests pass.

## 2. Remove service-specific authorization configuration

- [x] 2.1 Remove the app-only authorization profile activation from both edges, its client registration, and the service-only user/group/role entries; verify the default profile starts with existing clients and users only.
- [x] 2.2 Remove only the associated keys from the app and authorization encrypted Secret manifests using SOPS; verify key names and Kustomize builds without printing decrypted values or changing unrelated secrets.

## 3. Remove application and delivery integration

- [x] 3.1 Remove the private service module and its generated artifacts; verify no service-specific build target remains and unrelated modules still build.
- [x] 3.2 Remove the Cloud workload resources and child reconciliation while retaining the persistent claim and its prune-disable annotation; verify rendered Cloud configuration excludes the workload and preserves the claim.
- [x] 3.3 Remove the service release workflow and its dependency/image updater mappings; verify updater tests and generic authorization-server promotion behavior still pass.
- [x] 3.4 Remove service-only helper scripts and operating/acceptance/recovery documentation; verify remaining operational documentation and script tests are valid.

## 4. Remove stale OpenSpec references

- [x] 4.1 Remove the obsolete active product change rather than archiving it, and generalize affected archived authorization examples; verify no unrelated archived requirements are lost.
- [x] 4.2 Apply the generic authorization-user and declarative-client spec deltas, then archive this neutral change after implementation; verify canonical specs retain the generic requirements and archived artifacts contain no stale product naming.

## 5. Integrated verification and safety

- [x] 5.1 Run the authorization-server unit/integration suite, native compatibility checks, and updater tests; verify generic policy, restored-session, administrator, and machine-client contracts pass.
- [x] 5.2 Build affected Cloud and Home Kustomizations with output suppressed and run strict OpenSpec validation; verify both edge configurations remain equivalent and no encrypted values are emitted.
- [x] 5.3 Search tracked repository files case-insensitively for product-name variants using path-only output; verify there are no matches outside the protected persistent-claim identity and manifest, and Git history was not rewritten.
- [x] 5.4 Verify the persistent claim and prune-disable annotation remain in the diff, and confirm no live cluster, database, backup, or restore operation was performed.