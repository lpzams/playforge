# PlayForge sandbox: one container per run step sequence.
# Runs Claude Code CLI (coder) and Codex CLI (reviewer) as the non-root user "node"
# (Claude Code refuses --dangerously-skip-permissions as root).
FROM node:22-bookworm-slim

ARG NPM_REGISTRY=https://registry.npmjs.org/
ARG CLAUDE_CODE_VERSION=2.1.303
ARG CODEX_VERSION=0.166.0

RUN apt-get update \
 && apt-get install -y --no-install-recommends git ca-certificates tar \
 && rm -rf /var/lib/apt/lists/*

RUN npm config set registry "${NPM_REGISTRY}" \
 && npm install -g "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}" "@openai/codex@${CODEX_VERSION}" \
 && npm cache clean --force

COPY entrypoint.sh /entrypoint.sh
RUN chmod 755 /entrypoint.sh \
 && mkdir -p /workspace /home/node/.codex \
 && chown -R node:node /workspace /home/node

USER node
ENV HOME=/home/node
WORKDIR /workspace

ENTRYPOINT ["/entrypoint.sh"]
CMD ["sleep", "infinity"]
