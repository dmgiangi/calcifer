# hawkBit operations

The Home-only endpoint is `https://ota.calcifer.tech:8443`. Configure the LAN
DNS server to resolve `ota.calcifer.tech` to `192.168.0.102`. The proxy binds
that address and accepts connections only from `192.168.0.0/24`; verify router
and firewall rules separately, including that no external forwarding or SNAT
allows other networks to appear as LAN clients. The data volume is not encrypted.

cert-manager renews the `hawkbit-tls` secret, but HAProxy loads its certificate
only at startup. After every certificate renewal, restart the proxy deployment
and verify the certificate served at the endpoint:

```sh
kubectl --context calcifer-home -n home-automation rollout restart deployment/hawkbit-lan-proxy
kubectl --context calcifer-home -n home-automation rollout status deployment/hawkbit-lan-proxy
curl --silent --show-error --output /dev/null --write-out 'HTTP %{http_code}; TLS verify %{ssl_verify_result}\n' https://ota.calcifer.tech:8443/
```

The last command may return an authentication error; TLS verification must
still succeed. Monitor the certificate expiry and schedule this restart before
the certificate currently served by the proxy expires.
