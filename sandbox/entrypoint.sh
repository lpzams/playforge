#!/bin/sh
# Copy the read-only mounted Codex credentials into the writable home, then run the command.
set -e
if [ -f /mnt/creds/codex/auth.json ]; then
  mkdir -p "$HOME/.codex"
  cp /mnt/creds/codex/auth.json "$HOME/.codex/auth.json"
  chmod 600 "$HOME/.codex/auth.json"
fi
exec "$@"
