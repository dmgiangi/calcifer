# hawkBit operations

The Home-only endpoint is `https://ota.calcifer.tech`. LAN DNS resolves this
name to `192.168.0.102`. Traefik's Service uses `externalTrafficPolicy: Local`
to preserve client addresses, and the hawkBit middleware accepts only
`192.168.0.0/24`. Verify router and firewall rules separately, including that
no external forwarding or SNAT allows other networks to appear as LAN clients.
The data volume is not encrypted.

cert-manager renews the `hawkbit-tls` secret. Traefik reads certificate updates
from Kubernetes; monitor certificate expiry and verify the served certificate:

```sh
curl --silent --show-error --output /dev/null --write-out 'HTTP %{http_code}; TLS verify %{ssl_verify_result}\n' https://ota.calcifer.tech/rest/v1/targets
```

An unauthenticated request should return HTTP 401 and TLS verification 0.
