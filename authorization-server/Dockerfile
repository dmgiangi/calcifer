FROM debian:12-slim AS runtime-libs
RUN apt-get update \
    && apt-get install --no-install-recommends -y zlib1g \
    && rm -rf /var/lib/apt/lists/*

FROM gcr.io/distroless/cc-debian12:nonroot
COPY --from=runtime-libs /lib/x86_64-linux-gnu/libz.so.1.2.13 /lib/x86_64-linux-gnu/libz.so.1.2.13
COPY --from=runtime-libs /lib/x86_64-linux-gnu/libz.so.1 /lib/x86_64-linux-gnu/libz.so.1
WORKDIR /app
COPY --chown=nonroot:nonroot target/authorization-server /app/authorization-server
EXPOSE 8080
USER nonroot:nonroot
ENTRYPOINT ["/app/authorization-server"]
