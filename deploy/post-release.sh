#!/usr/bin/env bash
# Remote work specific to this service, run on the box by the shared deploy workflow after the
# unit sync and before the release switch. DEPLOY_DIR holds this repository's deploy/ directory
# as shipped; the workflow removes it afterwards, so nothing here cleans up after itself.
#
# Log shipping (box 2's trading access log -> /var/log/remote): a timer-driven incremental fetch.
# The script install is idempotent; the units sync on diff so an app release does not touch the
# schedule.
set -euo pipefail

sudo install -m 755 -o root -g root "$DEPLOY_DIR/logship-fetch.sh" /usr/local/bin/logship-fetch

# Bounds the shipped copy of box 2's access log, which logship-fetch appends to and nothing else
# rotates — the privacy notices state 90 days for every raw access log.
sudo install -m 644 -o root -g root "$DEPLOY_DIR/logship-logrotate" /etc/logrotate.d/trading-logship

logship_changed=0
for unit in trading-logship.service trading-logship.timer; do
  if ! cmp -s "$DEPLOY_DIR/$unit" "/etc/systemd/system/$unit"; then
    sudo cp "$DEPLOY_DIR/$unit" "/etc/systemd/system/$unit"
    logship_changed=1
  fi
done

if [ "$logship_changed" = 1 ]; then
  sudo systemctl daemon-reload
  sudo systemctl enable --now trading-logship.timer
fi

# Box 1's whole Caddy configuration, not a fragment of it. It lives in this repository because it
# carries the admin dashboard's basic_auth hash and this is the only private repository that
# deploys to box 1 — and because a single file split across four repositories is a file that
# drifts. It had: the live config gained a shared (security_headers) snippet and per-site access
# logging that no repository ever recorded, including the orderbook log block that this service
# reads. Rebuilding Caddy from the fragments would have silently stopped analytics capture.
if ! cmp -s "$DEPLOY_DIR/Caddyfile" /etc/caddy/Caddyfile; then
  # Validate before installing: a bad Caddyfile that reaches /etc and gets reloaded takes every
  # site on the box down at once, and nothing in the deploy would put it back.
  sudo caddy validate --config "$DEPLOY_DIR/Caddyfile" --adapter caddyfile
  sudo cp /etc/caddy/Caddyfile "/etc/caddy/Caddyfile.bak-$(date +%Y%m%d%H%M%S)"
  sudo cp "$DEPLOY_DIR/Caddyfile" /etc/caddy/Caddyfile
  sudo systemctl reload caddy
  echo "Caddyfile updated and caddy reloaded"
fi
