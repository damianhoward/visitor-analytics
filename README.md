# visitor-analytics

Visitor analytics for the live sites. Tails Caddy's JSON access logs on the host, enriches each
kept request — GeoLite2 city/ASN, device class, a salted IP hash — and records visits in an
Oracle Autonomous DB. An admin dashboard, behind Caddy `basic_auth`, shows recent visits and
rollups: visits per day, top countries, top referrers, engaged rate.

It watches sites that are themselves public, and it is worth being direct about what that means:
if you visit one of them, a row like the ones this code writes is what results. The design is
here to be read for that reason rather than in spite of it. Nothing that protects the data is in
this repository — the hash salt, the database credential and the dashboard's password are host
state and always were — so publishing costs no protection, and keeping it private would only
have made the handling harder to check.

## Pipeline

```
Caddy JSON access logs → tail → parse → filter (assets/health/bots out) →
enrich (GeoLite2 city + ASN, device class, salted IP hash) → Autonomous DB → /admin
```

- The analytics database never stores the raw IP address — a salted SHA-256 hash supports
  repeat-visit detection. The salt has an enforced minimum length, because a short one puts the
  whole IPv4 space within brute-force reach of the hashes it is meant to protect. The source
  Caddy access logs on the hosts do contain raw IPs, subject to each host's log rotation.
- The reverse-DNS enrichment keeps the registrable domain only, never the full hostname, which
  can embed the address the hash exists to remove.
- Retention is 90 days; a pruner deletes older rows daily.
- When the DB is unreachable (Always-Free ADB idles out after ~7 days), visits buffer to a local
  write-ahead file and flush on reconnect.

## Build

```
./gradlew spotlessCheck
./gradlew clean build
```

JDK 25 via the Gradle toolchain; 90% instruction coverage enforced (only `MainKt` excluded).
Rollup SQL is tested against H2 in Oracle compatibility mode plus one live smoke against the
real ADB (`VISITOR_DB_URL` set enables it). CodeQL and dependency-review workflows need GitHub
Advanced Security on a private repo, so CI runs build/test/coverage only; OWASP dependency-check
runs via `./gradlew dependencyCheckAnalyze`.

## Configuration (environment)

| Variable             | Meaning                                                                                                        | Default  |
| -------------------- | -------------------------------------------------------------------------------------------------------------- | -------- |
| `CADDY_LOG_PATHS`    | Comma-separated JSON access log files to tail                                                                  | required |
| `DB_URL`             | JDBC URL for the ADB (wallet via `TNS_ADMIN` in URL)                                                           | required |
| `DB_USER`            | Schema user                                                                                                    | required |
| `DB_PASSWORD`        | Schema password                                                                                                | required |
| `GEOLITE_CITY_DB`    | Path to `GeoLite2-City.mmdb`                                                                                   | required |
| `GEOLITE_ASN_DB`     | Path to `GeoLite2-ASN.mmdb`                                                                                    | required |
| `IP_HASH_SALT`       | Salt for the IP hash                                                                                           | required |
| `BUFFER_PATH`        | Write-ahead file used while the DB is unreachable                                                              | required |
| `PORT`               | Admin server port (binds `127.0.0.1` only)                                                                     | `8083`   |
| `RETENTION_DAYS`     | Visit retention before pruning                                                                                 | `90`     |
| `INTERNAL_PROXY_IPS` | Estate hosts that reach a site on a visitor's behalf; their requests are dropped rather than counted as visits | empty    |

## Deploy

It runs on the host whose Caddy logs it reads, under systemd behind Caddy like the other
services, with a ~96 MB heap. The admin server binds loopback only; Caddy terminates TLS and
enforces `basic_auth` in front of it. See [`deploy/README.md`](deploy/README.md).

The trading site runs on a second host, so its access log is shipped over SSH into a local file
the tailer watches like any other. That rig spans both hosts and is owned by the private
infrastructure repository rather than this one — a service repository can only reach the host it
deploys to, and the half it could not reach is the forced command that restricts the shipping key
to reading one file.
