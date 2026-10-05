# Rage Quit

Private Java 25 / Spring Boot 4.1.1 application, delivered only to Calcifer Cloud.
SQLite holds self-reported smoking/resistance events and explicit zero-smoking
declarations; sessions are ephemeral, records are not.

## Configuration

| Variable | Required value |
| --- | --- |
| `RAGE_QUIT_ISSUER` | `https://auth.calcifer.tech` |
| `RAGE_QUIT_CLIENT_ID` | `rage-quit` |
| `RAGE_QUIT_CLIENT_SECRET` | Operator-provisioned secret, shared with both auth edges |
| `RAGE_QUIT_DATABASE` | `/data/rage-quit.sqlite` (persistent, writable parent) |
| `RAGE_QUIT_START_DATE` | Mandatory operator-agreed ISO date, not in the future in Europe/Rome |
| `PORT` | `8080` |

The start date is stored once in schema version 1. Changing it in configuration
does not reset history: startup rejects a mismatch. There is no suggested or
default production start date. Storage uses foreign keys, a two-second busy
timeout, `journal_mode=DELETE`, and `synchronous=FULL`.

## Local checks

Use the existing Maven settings and approved dependencies. Do not supply production
credentials or real records to tests. The module's executable jar is the single
`target/rage-quit-*.jar` (currently `rage-quit-0.1.0-SNAPSHOT.jar`).

<augment_code_snippet mode="EXCERPT">
````sh
mvn -B -ntp -f rage-quit/pom.xml verify -s authorization-server/.mvn/settings.xml
bash scripts/rage-quit-validate.sh
docker build --platform linux/amd64 -t calcifer-rage-quit:checked rage-quit
bash scripts/rage-quit-container-check.sh calcifer-rage-quit:checked
bash scripts/rage-quit-rehearse.sh
````
</augment_code_snippet>

Run from the repository root with JDK 25 (`java`/`jar`), Bash, Docker, kubectl,
curl, OpenSSL and coreutils. Backup/rehearsal also require the already-used `age`
tooling and `flock`. Optional installed actionlint/shellcheck add lint coverage;
the mandatory Java validator uses the application's bundled SnakeYAML, not a new
dependency. Test images/volumes and private synthetic scratch files are cleaned
up by the scripts; no live cluster or release operations run.

The image runs UID/GID 10001 with native access explicitly enabled and a private
0077 file-creation umask; the launcher replaces itself with Java as PID 1. Writable
`/tmp` must be bounded **and executable** for SQLite native extraction; `/data`
must persist. Do not disable durability or add replicas. Liveness checks the
process only; readiness also validates storage/schema. Auth endpoints are explicit
and lazy, so an issuer outage is not a startup dependency or restart trigger.
The public Traefik route refuses actuator paths; probes use the private service.

## Delivery and operations

- [Operator setup and rollout](../docs/rage-quit-operations.md)
- [Offline encrypted backup, guarded restore and rollback](../docs/rage-quit-recovery.md)
- [Local evidence and operator-only live acceptance](../docs/rage-quit-acceptance.md)

The initial Flux entry is suspended. A missing start date, Secret/key, explicit
placeholder secret and unreleased image are intentional fail-closed setup gates,
not ready-to-deploy values.
Release dispatch, secret/DNS writes and live restore require explicit operator
authorization. The Rage Quit workflow never promotes a Home workload or changes
the separate symmetric authorization-server release workflow.
