FROM debian:13.7-slim@sha256:a72b4433de1cf7d6bae0c4cee74d0547f58601a643b3375f070c51600d63b6b2
ARG TARGETARCH
RUN test "$TARGETARCH" = amd64 \
    && apt-get update \
    && apt-get install --no-install-recommends -y \
        ca-certificates \
        libgcc-s1 \
        libstdc++6 \
        zlib1g=1:1.3.dfsg+really1.3.1-1+deb13u1 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 nonroot \
    && useradd --uid 10001 --gid nonroot --no-create-home --no-log-init \
        --home-dir /nonexistent --shell /usr/sbin/nologin nonroot

WORKDIR /app
COPY --chown=nonroot:nonroot target/authorization-server /app/authorization-server
EXPOSE 8080
USER nonroot:nonroot
ENTRYPOINT ["/app/authorization-server"]
