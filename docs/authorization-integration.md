# Application identity integration

Applications in both clusters validate the same OIDC issuer:

```text
https://auth.calcifer.tech
```

They must not configure separate Cloud/Home issuers and must not infer
authorization from the cluster where a token was issued. `iss` is always
`https://auth.calcifer.tech`.

## Interactive applications

Register one confidential OIDC client per application. Use authorization code
with PKCE and the redirect URI registered for that application.

Connected authorization codes, consents, and browser sessions are stored in the
dedicated Cloud Redis service. The only supported stateful OAuth hostname is the
canonical hostname, which public DNS resolves to Cloud and the Home LAN DNS
override resolves to Home:

| Application location | Authorization, token, and user-info endpoint base | DNS behavior |
| --- | --- | --- |
| Cloud | `https://auth.calcifer.tech` | public Cloud edge |
| Home | `https://auth.calcifer.tech` | Home LAN split-horizon edge |

Keep `https://auth.calcifer.tech` as both the issuer/JWKS validation value and
the stateful endpoint base. The only registered Google callback is
`https://auth.calcifer.tech/login/oauth2/code/google`. The former
`auth-cloud.calcifer.tech` and `auth-home.calcifer.tech` endpoint profiles are
retired and must not be configured in clients or application discovery
overrides.

The initial administrator has the same canonical subject after either Google
or password login:

```text
sub:   user:admin
roles: [admin]
```

Applications map `admin` to their local administrator role. Email is a login
attribute, not the stable authorization identity.

When an application already has accounts keyed by a provider-specific subject,
it must perform a controlled one-time migration to the canonical subject. For
the single-tenant Grafana instance, email lookup is enabled only for this
adoption; new applications should key accounts directly by `sub`.

## Machine clients

Use a separate private client per workload and `client_credentials`. Request
only the scope required by the target API and validate issuer, audience,
signature, expiry, and scope. Do not use a browser client secret for API calls.

## Network locality

Cloud and Home applications use the same canonical endpoint. Redis is
authoritative while connected, so a restart does not discard unexpired state.
If Home loses Redis reachability, it enters isolation only between requests and
uses a fresh process-local epoch; isolated state is never copied to Redis.
Google login is unavailable without Internet access, while the local password
path remains available on Home. Recovery gates stateful requests, advances the
Redis generation once, discards Home-local state, and requires affected users
to authenticate again.

## Operational rules

- Never trust a cluster name or an incoming identity header as authorization.
- Keep client secrets, signing material, and the password hash in SOPS.
- Keep Redis ACL credentials in the dedicated `authorization/redis-auth` Secret;
  provision that Secret out of band or through the repository's approved SOPS
  workflow before enabling the stateful release. No credential belongs in Git
  plaintext.
- Rotate the common signing material and equivalent client configuration in
  both clusters together.
- Do not log tokens, authorization codes, passwords, hashes, or email labels.
- Access tokens remain short-lived at five minutes; refresh tokens remain
  disabled. Generation recovery invalidates stored grants and sessions but does
  not revoke an already issued unexpired JWT.
- Follow `docs/homepage-operations.md` when adding a declarative client. Client
  metadata belongs in `clients.yaml`, secrets remain SOPS-encrypted, and Secret
  changes require an explicit Deployment revision bump.
