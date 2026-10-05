# Build the tested executable jar first; this context contains no secrets or source.
FROM eclipse-temurin:25-jre-noble
WORKDIR /app
COPY --chown=10001:10001 target/rage-quit-*.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
# SQLite extracts its native library into the bounded, executable /tmp mount.
# Liveness is process-only; readiness additionally validates schema and storage.
# Keep newly created DB/journal files private; exec preserves Java as PID 1.
ENTRYPOINT ["sh", "-c", "umask 0077; exec java --enable-native-access=ALL-UNNAMED -XX:MaxRAMPercentage=70 -Djava.io.tmpdir=/tmp -Dorg.sqlite.tmpdir=/tmp -jar /app/app.jar \"$@\"", "--"]
