FROM gcr.io/distroless/base-debian12:nonroot
WORKDIR /app
COPY --chown=nonroot:nonroot target/authorization-server /app/authorization-server
EXPOSE 8080
USER nonroot:nonroot
ENTRYPOINT ["/app/authorization-server"]
