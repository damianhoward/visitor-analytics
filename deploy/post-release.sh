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

# Box 1's Caddy configuration was installed here until 2026-08-05 and is now in the private
# estate-infra repository, applied by its own script. It fronts every site on the box and outlives
# any one of them, so tying it to this service's releases tied it to this service's lifetime.
#
# What stays here is genuinely this service's: the log shipper is how the analytics pipeline
# reaches box 2's access log, and it has no meaning without the pipeline that reads it.
