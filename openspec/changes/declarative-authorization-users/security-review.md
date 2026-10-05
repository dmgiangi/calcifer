# Security review — 2026-10-05

## Scope and result

Reviewed the current Authorization Server working tree, including the declarative
user/group change and the operator-approved security remediation. Jackson was
upgraded to **3.1.7** and Tomcat to **11.0.25**. No secrets were read or changed,
and no live cluster, release, or deployment operations were performed.

The password-hash verification warning and all three code-review findings below
are fixed. The post-upgrade OSV scan reports **zero advisories across 171 resolved
artifact/version pairs** (140 compile/runtime artifacts). Full clean Maven verify
passes **98 tests**. This is bounded verification, not a guarantee that the
application or its deployed infrastructure is free of vulnerabilities.

## Password-hash fix

`AuthorizationServerConfiguration.validatedLocalPasswordHash`
now rejects local-login hashes unless their encoding and payload identify bcrypt
or Argon2id. Spring prefixes accepted are `{bcrypt}`, `{argon2}`, and
`{argon2@SpringSecurity_v5_8}`. Native bcrypt/Argon2id hashes are normalized to the
appropriate Spring prefix before use by the production delegating encoder.

Unsupported schemes, plaintext, malformed formats, Argon2i/Argon2d, and mismatched
encoding prefixes fail startup without including the configured hash in the error.
Disabled local login still requires no hash. Existing secret delivery is unchanged.

Regression coverage in `LocalLoginConfigurationTest` includes real authentication
with native and Spring-prefixed hashes, wrong-password rejection, Google-only
password rejection, disallowed schemes, and disabled local login.

## Dependency vulnerabilities — original findings, now remediated

Resolved dependencies were obtained with Maven `dependency:tree` in JSON format.
The public OSV `querybatch` endpoint was queried using only Maven coordinates and
versions, followed by individual advisory lookups to check severity, withdrawal,
and fixed versions. The original scan covered **171 distinct artifact/version pairs**, including
**140 compile/runtime artifacts** and test dependencies. No returned advisory was
withdrawn. Severity below is the database rating, not a demonstrated application
exploit: **3 critical, 5 high, 2 moderate**.

| Artifact / resolved version | Advisory | CVE | Severity | Fixed in current release line |
|---|---|---|---|---|
| jackson-databind 3.1.5 | [GHSA-cxp5-3px4-pw24](https://osv.dev/vulnerability/GHSA-cxp5-3px4-pw24) | CVE-2026-91777 | High | 3.1.7 |
| jackson-databind 3.1.5 | [GHSA-gx83-3vf8-gh7j](https://osv.dev/vulnerability/GHSA-gx83-3vf8-gh7j) | CVE-2026-83557 | Moderate | 3.1.6 |
| jackson-databind 3.1.5 | [GHSA-q4xh-88c3-wmh7](https://osv.dev/vulnerability/GHSA-q4xh-88c3-wmh7) | CVE-2026-68497 | High | 3.1.6 |
| jackson-databind 3.1.5 | [GHSA-wjgm-6hv5-3cvf](https://osv.dev/vulnerability/GHSA-wjgm-6hv5-3cvf) | CVE-2026-19032 | Moderate | 3.1.6 |
| jackson-databind 3.1.5 | [GHSA-wv8q-qhhj-9h54](https://osv.dev/vulnerability/GHSA-wv8q-qhhj-9h54) | CVE-2026-91776 | High | 3.1.7 |
| jackson-core 3.1.5 | [GHSA-7hhh-6rmp-j9qf](https://osv.dev/vulnerability/GHSA-7hhh-6rmp-j9qf) | CVE-2026-89425 | High | 3.1.7 |
| jackson-core 3.1.5 | [GHSA-p6pp-m3f8-5c89](https://osv.dev/vulnerability/GHSA-p6pp-m3f8-5c89) | CVE-2026-89407 | High | 3.1.7 |
| tomcat-embed-core 11.0.24 | [GHSA-9xv2-5v5q-p794](https://osv.dev/vulnerability/GHSA-9xv2-5v5q-p794) | CVE-2026-65905 | Critical | 11.0.25 |
| tomcat-embed-core 11.0.24 | [GHSA-gcx9-497g-6cp6](https://osv.dev/vulnerability/GHSA-gcx9-497g-6cp6) | CVE-2026-65182 | Critical | 11.0.25 |
| tomcat-embed-core 11.0.24 | [GHSA-h3x4-894j-xpx5](https://osv.dev/vulnerability/GHSA-h3x4-894j-xpx5) | CVE-2026-68525 | Critical | 11.0.25 |

Jackson coordinates are `tools.jackson.core:jackson-databind` and
`tools.jackson.core:jackson-core`; Tomcat is
`org.apache.tomcat.embed:tomcat-embed-core`. Following operator approval, Maven
resolves Jackson core/databind **3.1.7** and all three embedded Tomcat artifacts
**11.0.25**. The dependency tree was regenerated after `mvn clean verify`, and the
complete OSV batch response at **2026-10-05T12:53:24.247Z** returned no advisories
for any of the 171 artifact/version pairs. No paginated results were returned.

### Applicability limits

Tomcat advisories involve container DIGEST/FORM authentication or servlet security
constraints. The inspected application uses Spring Security filters and does not
configure these Tomcat features; Spring `formLogin` is not Tomcat FORM authentication.
No exploit path for those advisories was demonstrated in this application.

Jackson advisories require particular binding/parser paths: identity references,
polymorphic types/fallbacks, XML datatype or Path binding, DataInput parsing, or
numeric-string handling. A bounded search of application sources found no explicit
identity/type annotations, XML datatype bindings, or DataInput parser calls. This
does not prove absence in framework/transitive code or provider-response processing.

Current raw scan evidence is saved under ignored build output in
`authorization-server/target/security-dependency-tree.json` and
`authorization-server/target/security-osv-report-after.json`. The clean build
removed the earlier generated reports; the original findings are preserved above.

## Additional code-review findings — remediated

### Medium — restored-session login-method policy: fixed

Previously, persisted sessions could authorize and receive tokens after removal
of their login method from the catalog, provided group membership still permitted
the client. `IdentityProperties.userFor` now revalidates the authentication type:
Google requires the Google provider, a verified OIDC email, and catalog permission;
password authentication requires enabled local login, the configured username,
and catalog permission. Unsupported principal types fail closed.

`RestoredSessionPolicyTest` exercises serialized/restored principals for both
methods, revoked methods, disabled local login, and unsupported principal types.
Interactive authorization and JWT issuance independently use the current policy.

### Medium contract issue — UserInfo membership claims: fixed

Previously, the framework UserInfo filter handled `/userinfo` before the MVC
controller and its default mapper omitted groups/roles. The production OIDC
configuration now installs `CatalogOidcUserInfoMapper`, which reads the stored
authentication, revalidates the current catalog/method, cross-checks the canonical
subject against the stored ID token, and supplies current groups and legacy roles.

Framework bearer-token activity and `openid` scope checks remain in place;
standard claims remain restricted to authorized scopes. Seven
`OidcUserInfoEndpointTest` tests exercise the real filter chain, including unknown,
invalidated, and mismatched tokens, revoked login methods, minimal scopes, and the
local administrator. The obsolete `UserInfoController` and its direct unit test
were removed to avoid retaining an unreachable, weaker alternative implementation.

### Medium contract issue — ID-token audience: fixed

Previously, JWT customization replaced the ID-token client audience with the
configured resource audience. `jwtClaimsCustomizer` now sets resource audiences
only for access tokens and uses the registered client ID for ID tokens.

`AuthorizationClaimsCustomizerTest` verifies differing client/resource identifiers:
the ID token addresses `dashboard-ui`, while the access token addresses `metrics-api`.

## Validation performed

- IDE compilation: passed.
- Focused `LocalLoginConfigurationTest`: passed, 23 tests.
- `mvn -f authorization-server/pom.xml clean verify`: passed, **98 tests,
  zero failures/errors/skips**, packaged application successfully.
- Post-clean dependency tree and OSV re-scan: **171 pairs, zero advisories**.
- `openspec validate declarative-authorization-users --strict`: passed.
- Cloud and Home Kustomize rendering: passed; no cluster writes.
- `git diff --check`: passed.
- Bounded independent review confirmed the authentication attribute key and
  policy/subject trust checks. Its dead-controller and minimal-scope role-test
  recommendations were applied before the final clean verify.
- Existing Redis API deprecation and Maven/JDK/Mockito runtime warnings remain;
  these did not cause compilation or test failures.

This was a dependency database check and bounded static review, not a penetration
test. Container OS/JDK vulnerabilities, build-plugin dependencies, native-image
behavior, live HTTP flows, and deployed images/clusters were not tested.