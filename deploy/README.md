# Deploy

The dashboard runs as a systemd JVM service behind Caddy at **https://admin.damianhoward.com**,
on the box that fronts the public sites whose access logs it reads.

`.github/workflows/deploy.yml` is a thin caller of the estate's shared pipeline: `clean build`,
package `installDist` once, then ask the box for a release over SSH against a host key pinned from
[`known_hosts.pub`](known_hosts.pub), with those exact bytes on stdin. The box unpacks into
`/srv/visitor-analytics/releases/<commit>`, moves `/srv/visitor-analytics/current` onto it, and
gates success on a health check. A release that does not come up is rolled back by the script on
the box, so a runner that dies mid-deploy cannot leave a broken release serving. Three releases
are retained.

## What is not here

Two things this service depends on are deliberately owned elsewhere.

**Caddy.** The proxy configuration covers every site on the box and outlives any one service, so
it is version-controlled as one whole file in the private infrastructure repository and installed
by an operator-run script that validates before reloading. A deploy of this service does not touch
it: a bad Caddyfile takes every site on the box down at once, which should not be reachable as a
side effect of shipping one application.

**The trading log-shipping rig.** The trading site runs on the other box, so its access log is
fetched incrementally over SSH into a local file this service's tailer watches like any other. All
of that — the fetch, its timer, its rotation rule, and the forced command on the far box that
restricts the shipping key to reading one file — now lives in the infrastructure repository too.

It moved for a reason worth stating: the rig spans two boxes and a deploy pipeline reaches one, so
this repository could only ever install half of it. The half it could not reach was the forced
command, which is the control that makes the arrangement safe. A security control whose two halves
are owned by different repositories, one of which cannot apply its half, is not really owned.

## Service

`visitor-analytics.service` runs the launcher with `-Xmx96m`. It is the only service on the estate
with `ReadWritePaths`, because it is the only one that writes: the visit buffer and the tailer's
byte offsets, both under one directory it owns. Everything else it touches — the env file, the
Oracle wallet, the access logs, the GeoLite2 databases — is read-only under `ProtectSystem=strict`,
which is what an input should be.

Configuration comes from `EnvironmentFile=/etc/visitor-analytics/env`: the log paths, the database
triple, the GeoLite2 paths, the IP hash salt, the buffer path and the port. The salt is required
and has a minimum length the code enforces, because a short one puts the whole IPv4 space within
brute-force reach of the hashes it protects.

## Access

The admin dashboard binds loopback and is reachable only through Caddy, which enforces HTTP basic
authentication in front of it. That credential is host state and lives with the proxy
configuration, not here.

`DEPLOY_HOST`, `DEPLOY_USER` and `DEPLOY_SSH_KEY` are GitHub Actions secrets and never in the
repository. `DEPLOY_SSH_KEY` is a key of CI's own, not the operator's, and on the box it is pinned
to a forced command: it can ask for a release and can do nothing else — no shell, no file copy, no
port forward. The account behind it may run exactly one command as root, `systemctl restart
visitor-analytics`.

The unit is not in this repository either. A unit file is a request to run anything as anyone, so
a deploy account able to install one holds root by another name; it is owned as host configuration
and applied by an operator.
