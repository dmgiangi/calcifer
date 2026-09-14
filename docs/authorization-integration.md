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

An authorization code and its browser session are stateful, so an application
deployment must pin all its browser-facing OIDC endpoints to its own edge.
This is deployment transport configuration, not an identity choice:

| Application location | Authorization, token, and user-info endpoint base | DNS behavior |
| --- | --- | --- |
| Cloud | `https://auth-cloud.calcifer.tech` | public Cloud edge, including for LAN browsers |
| Home | `https://auth-home.calcifer.tech` | Home LAN edge only |

Keep `https://auth.calcifer.tech` as the issuer/JWKS validation value. Do not
derive a stateful endpoint from the split-horizon canonical hostname when a
browser can be in a different network from the application backend. Frameworks
that use discovery for endpoint configuration must support explicit endpoint
overrides; configure the issuer separately for token validation.

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

Cloud applications always use the Cloud endpoint profile, even when their
browser is on the LAN. Home applications always use the Home endpoint profile.
The identity contract is identical at both edges. The v1 implementation does
not replicate live browser sessions or authorization-code state, so a session
is not shared between the two physical edges.

## Operational rules

- Never trust a cluster name or an incoming identity header as authorization.
- Keep client secrets, signing material, and the password hash in SOPS.
- Rotate the common signing material and equivalent client configuration in
  both clusters together.
- Do not log tokens, authorization codes, passwords, hashes, or email labels.
