#!/usr/bin/env bash
# Verifies that the Claude and Codex subscriptions work inside the sandbox image.
# Usage (Git Bash on Windows): bash sandbox/scripts/verify-creds.sh
# Never prints the token. Each container is removed after use.
set -u
IMAGE=${IMAGE:-playforge-sandbox:p2}

# Token: from the environment, or from the Windows user scope if this shell predates it.
PS=$(command -v powershell || echo /c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe)
if [ -z "${PLAYFORGE_CLAUDE_TOKEN:-}" ] && [ -x "$PS" ]; then
  PLAYFORGE_CLAUDE_TOKEN=$("$PS" -NoProfile -Command "[Environment]::GetEnvironmentVariable('PLAYFORGE_CLAUDE_TOKEN','User')" | tr -d '\r\n')
fi
if [ -z "${PLAYFORGE_CLAUDE_TOKEN:-}" ]; then
  echo "PLAYFORGE_CLAUDE_TOKEN is not set (run: claude setup-token)" >&2
  exit 2
fi
export CLAUDE_CODE_OAUTH_TOKEN="$PLAYFORGE_CLAUDE_TOKEN"

CODEX_AUTH=${CODEX_AUTH:-"$(cygpath -m "$USERPROFILE" 2>/dev/null || echo "$HOME")/.codex/auth.json"}
export MSYS_NO_PATHCONV=1

echo "== claude =="
docker run --rm -i -e CLAUDE_CODE_OAUTH_TOKEN "$IMAGE" \
  sh -c 'claude -p --output-format stream-json --verbose --dangerously-skip-permissions' <<'EOF'
Reply with exactly: pong
EOF
claude_rc=$?

echo "== codex =="
docker run --rm -i -v "$CODEX_AUTH:/mnt/creds/codex/auth.json:ro" "$IMAGE" \
  sh -c 'git init -q && codex exec --json --sandbox danger-full-access --skip-git-repo-check -' <<'EOF'
Reply with exactly: pong
EOF
codex_rc=$?

echo "claude exit=$claude_rc codex exit=$codex_rc"
