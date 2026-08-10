# The persona SUBPROCESS jail image (requirements §12; design plan_docs/llm-sandbox.md).
#
# NOT the repo-root Dockerfile — that one is the CI *build* image (JDK 21 + Node, runs `./gradlew
# verifyAll`). This one holds nothing but what one `claude -p` invocation needs: the claude CLI, node for
# the baked gh-readonly MCP server, and `gh` for the read-only GitHub tools that server shells out to.
#
# Build it from the REPO ROOT (the context must include mcp/, which the root .dockerignore permits):
#
#   docker build -t aiforum-claude-jail -f docker/claude-jail/Dockerfile .
#
# Nothing here grants privilege — the containment lives in the `docker run` flags JailLauncher.wrap
# builds (read-only rootfs, all caps dropped, tmpfs home, an internal network with no route out). This
# image only has to be small, non-root, and free of anything that writes outside $HOME.
FROM node:22-bookworm-slim

# Pinned so a rebuild is reproducible rather than "whatever npm had this morning" — a jail whose contents
# drift silently is a jail whose contract you can't state. Verified against the host CLI on 2026-08-09;
# bump deliberately, and re-run `./gradlew jailContract` after.
ARG CLAUDE_CODE_VERSION=2.1.220

# `gh` from GitHub's own apt repo. dpkg --print-architecture keeps this correct on arm64 (Apple silicon)
# as well as amd64 — the repo publishes both, but the sources.list line has to name the right one.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
    && install -m 0755 -d /etc/apt/keyrings \
    && curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
         -o /etc/apt/keyrings/githubcli-archive-keyring.gpg \
    && chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
         > /etc/apt/sources.list.d/github-cli.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends gh \
    && rm -rf /var/lib/apt/lists/*

RUN npm install -g @anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}

# The gh-readonly MCP server, baked in rather than bind-mounted: a mount would be a second host path
# inside the jail, and the whole point is that the credential is the only one. Both files, because
# server.mjs imports tools.mjs relatively. JailLauncher rewrites --mcp-config to this path.
COPY mcp/gh-readonly/server.mjs mcp/gh-readonly/tools.mjs /opt/aiforum/mcp/gh-readonly/

# The node images set ENTRYPOINT ["docker-entrypoint.sh"]; clear it so `docker run … timeout … claude …`
# executes exactly the argv JailLauncher built, with nothing wrapping it.
ENTRYPOINT []

# Unprivileged by default (uid 1000, shipped by the node image). $HOME is the tmpfs the run mounts, which
# is the only writable place in the container — claude's cache and config land there and die with it.
USER node
ENV HOME=/home/node
