#!/usr/bin/env bash
# Incremental fetch of box 2's trading.damianhoward.com access log into the file the tailer
# reads. The byte offset is persisted, so downtime — box 2 away, VCN blip, this box rebooting —
# never loses lines: the next run picks up from where the last one stopped. A failure between
# append and offset write refetches a batch; the store's unique visit key drops the replays.
set -euo pipefail

KEY=/home/ubuntu/.ssh/analytics_logship
REMOTE=ubuntu@10.0.0.91
DEST=/var/log/remote/trading.log
STATE=/var/lib/visitor-analytics/trading-logship.offset

remote() { ssh -i "$KEY" -o BatchMode=yes -o ConnectTimeout=10 "$REMOTE" "$1"; }

size="$(remote size)"
[[ "$size" =~ ^[0-9]+$ ]] || { echo "unexpected size reply: $size" >&2; exit 1; }

if [ -f "$STATE" ]; then
  offset="$(cat "$STATE")"
  [[ "$offset" =~ ^[0-9]+$ ]] || offset=0
else
  # First run: start from now, matching the tailer's own first-run behaviour.
  offset="$size"
fi

# The remote file shrank: rotation. Start at the top of the new file.
if (( size < offset )); then offset=0; fi

if (( size > offset )); then
  tmp="$(mktemp)"
  trap 'rm -f "$tmp"' EXIT
  remote "read $offset" > "$tmp"
  cat "$tmp" >> "$DEST"
  offset=$(( offset + $(stat -c%s "$tmp") ))
fi

echo "$offset" > "$STATE"
