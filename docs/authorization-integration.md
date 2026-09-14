# Application identity integration

Applications in both clusters use the same OIDC issuer:

```text
https://auth.calcifer.tech
```

They must not configure separate Cloud/Home issuers and must not infer
authorization from the cluster where a token was issued.

## Interactive applications

Register one confidential OIDC client per application. Use authorization code
with PKCE and the redirect URI registered for that application. Discover the
authorization, token, JWKS, and user-info endpoints from the canonical issuer.

The initial administrator has the same canonical subject after either Google
or password login:

```text
sub:   user:admin
roles: [admin]
```

Applications map `admin` to their local administrator role. Email is a login
attribute, not the stable authorization identity.

## Machine clients

Use a separate private client per workload and `client_credentials`. Request
only the scope required by the target API and validate issuer, audience,
signature, expiry, and scope. Do not use a browser client secret for API calls.

## Network locality

Public clients reach the Cloud edge. LAN clients reach the Home edge through
split-horizon DNS. The identity contract is identical at both edges. The v1
implementation does not replicate live browser sessions or authorization-code
state, so an in-progress flow must remain on one edge and a path change may
require a new login.

## Operational rules

- Never trust a cluster name or an incoming identity header as authorization.
- Keep client secrets, signing material, and the password hash in SOPS.
- Rotate the common signing material and equivalent client configuration in
  both clusters together.
- Do not log tokens, authorization codes, passwords, hashes, or email labels.
