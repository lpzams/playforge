# PlayForge sandbox (p3): one container per run.
# - Claude Code CLI (coder) and Codex CLI (reviewer), run as the non-root user "node"
#   (Claude Code refuses --dangerously-skip-permissions as root)
# - /template: Vite game starter with the probe SDK, dependencies preinstalled (works offline)
# - /opt/playtest: Playwright + Chromium playtest harness (run.mjs)
FROM node:22-bookworm-slim

ARG NPM_REGISTRY=https://registry.npmjs.org/
ARG CLAUDE_CODE_VERSION=2.1.303
ARG CODEX_VERSION=0.166.0
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright

RUN apt-get update \
 && apt-get install -y --no-install-recommends git ca-certificates tar \
 && rm -rf /var/lib/apt/lists/*

RUN npm config set registry "${NPM_REGISTRY}" \
 && npm install -g "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}" "@openai/codex@${CODEX_VERSION}" \
 && npm cache clean --force

# Playtest harness dependencies + Chromium (and its system libraries)
COPY playtest/package.json /opt/playtest/package.json
RUN cd /opt/playtest \
 && npm install \
 && npx playwright install --with-deps chromium \
 && rm -rf /var/lib/apt/lists/* \
 && npm cache clean --force
COPY playtest/ /opt/playtest/

# Game template with dependencies preinstalled
COPY template/ /template/
RUN cd /template && npm install && npm cache clean --force

COPY entrypoint.sh /entrypoint.sh
RUN chmod 755 /entrypoint.sh \
 && mkdir -p /workspace /home/node/.codex \
 && chown -R node:node /workspace /home/node /template /opt/playtest

USER node
ENV HOME=/home/node
WORKDIR /workspace

ENTRYPOINT ["/entrypoint.sh"]
CMD ["sleep", "infinity"]
