# visitor-analytics

Visitor analytics for the live sites (orderbook, risk, trading). Tails Caddy's JSON access logs
on box 1, enriches each kept request — GeoLite2 city/ASN, device class, a salted IP hash — and
records visits in an Oracle Autonomous DB. An admin dashboard (behind Caddy `basic_auth` at
`admin.damianhoward.com`) shows recent visits and rollups: visits per day, top countries, top
referrers, engaged rate.

This repo is private: the mechanism that captures and geolocates visits isn't something to
publish alongside the sites it watches.

## Pipeline

```
Caddy JSON access logs → tail → parse → filter (assets/health/bots out) →
enrich (GeoLite2 city + ASN, device class, salted IP hash) → Autonomous DB → /admin
```

- The analytics database never stores the raw IP address — a salted SHA-256 hash supports
  repeat-visit detection. The source Caddy access logs on the hosts do contain raw IPs, subject
  to each box's log rotation.
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

Box 1 (it reads box 1's Caddy logs directly), systemd + Caddy like the other services, ~96 MB
heap. The admin server binds loopback only; Caddy terminates TLS and enforces `basic_auth` for
`admin.damianhoward.com`.
