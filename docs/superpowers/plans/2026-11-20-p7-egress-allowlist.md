# P7 沙箱网络白名单 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sandbox containers have no direct route to the internet; their only way out is an egress gateway that allows a configured list of domains, and every blocked attempt shows up in the run's story.

**Architecture:** A Docker `--internal` network `playforge-sandbox` holds the sandbox containers. A gateway container `playforge-egress` (the sandbox image running a Node-stdlib CONNECT proxy from a read-only bind mount) sits on both that network (alias `egress`) and the default bridge. Sandboxes get `HTTPS_PROXY=http://egress:3128`. After every step the orchestrator asks the sandbox which requests from its container IP were refused and publishes `NETWORK_BLOCKED`.

**Tech Stack:** Java 21 / Spring Boot (backend), Node 22 stdlib + `node:test` (gateway), Vue 3 + Vitest (story), Docker CLI.

**Spec:** `docs/superpowers/specs/2026-11-20-p7-egress-allowlist-design.md`

## Global Constraints

- Network name `playforge-sandbox`, created with `docker network create --internal`.
- Gateway container name `playforge-egress`, labels `playforge.egress=app` and `playforge.egress.config=<hash>`; alias `egress`, port `3128`; `--cpus 0.5 --memory 128m`; **no `-p` port publishing**.
- Sandbox env when enabled: `HTTPS_PROXY=http://egress:3128`, `HTTP_PROXY=http://egress:3128`, `NO_PROXY=localhost,127.0.0.1`, `NODE_USE_ENV_PROXY=1`, `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1`.
- The gateway only handles `CONNECT`; every other method → 403 and a log line. `CONNECT` only to allowed ports (default `443`).
- Rule matching: exact host, or `*.example.com` = strict subdomains only; case-insensitive; trailing dot ignored.
- Log line (JSONL, file `/tmp/egress.jsonl` in the gateway + stdout): `{"ts":"<ISO>","client":"<ip>","host":"<host>","port":<n>,"allowed":<bool>}`.
- Default allow list (application.yml): `api.anthropic.com, chatgpt.com, auth.openai.com, api.openai.com, registry.npmjs.org, registry.npmmirror.com, cdn.npmmirror.com`.
- `playforge.sandbox.egress.enabled: false` → exactly today's `docker run` (no `--network`, no proxy env). `local` sandbox unaffected.
- Reading blocked requests never fails a step: errors → `log.warn`, empty list.
- Commits: no `Co-Authored-By` line (user's standing rule overrides the harness reminder).
- Local tooling: node at `/c/nvm4w/nodejs`, docker at `/c/Program Files/Docker/Docker/resources/bin` (prefix PATH in bash); backend built with `backend/mvnw` on JDK 22.

## Review Focus

1. **Playwright's Chromium inside the sandbox picks up `HTTP(S)_PROXY`** and the game on `localhost` must still load → Task 5 integration test runs the existing hard-check playtest against the template with egress on.
2. **Docker Desktop restarts** wipe the gateway/network while the backend keeps running → Task 3: `ensure()` runs before every container start and in the 60 s health check; test that a missing gateway is recreated.
3. **Config change between backend restarts** (allow list edited) → Task 3: a gateway whose config label differs is replaced; test it.
4. **Hostnames with odd casing / trailing dot / `evil.com.api.anthropic.com` vs `api.anthropic.com.evil.com`** → Task 1 matcher tests.
5. **A slow or dead upstream proxy** must not hang a CLI forever → Task 1: upstream CONNECT has a 10 s timeout and answers the client 502; test with an upstream that never replies.

---

### Task 1: The gateway (Node)

**Files:**
- Create: `sandbox/egress/egress.mjs`
- Test: `sandbox/egress/egress.test.mjs`

**Interfaces:**
- Produces: `export function matches(host: string, rules: string[]): boolean`;
  `export function createEgress({ allow: string[], ports?: number[] = [443], upstream?: string, log?: (entry) => void, upstreamTimeoutMs?: number = 10000 }): http.Server`;
  when run directly (`node egress.mjs`): reads `EGRESS_ALLOW` (comma list), `EGRESS_PORTS` (default `443`), `EGRESS_UPSTREAM` (optional `http://host:port`), `EGRESS_LOG` (default `/tmp/egress.jsonl`), listens on `3128` on all interfaces, appends each log entry as JSON to the file and stdout.

- [ ] **Step 1: Write the failing tests** (`node:test` + `node:assert/strict`), each server on port 0, closed in `after`:
  - `matches`: `matches('api.anthropic.com', ['api.anthropic.com'])` true; `'API.Anthropic.com.'` true; `'evil.com'` false; `'api.anthropic.com.evil.com'` false; `'x.npmmirror.com'` vs `['*.npmmirror.com']` true; `'npmmirror.com'` vs `['*.npmmirror.com']` false; `'evilnpmmirror.com'` vs `['*.npmmirror.com']` false.
  - `CONNECT to an allowed host tunnels bytes`: a local TCP echo server; gateway `allow:['127.0.0.1'], ports:[echoPort]`; raw socket sends `CONNECT 127.0.0.1:<echoPort> HTTP/1.1\r\nHost: …\r\n\r\n`, expects status `200`, then writes `ping` and reads `ping`; log got `{host:'127.0.0.1', port:echoPort, allowed:true}` with a `client` string and ISO `ts`.
  - `CONNECT to a host not on the list → 403, logged as refused`: `allow:['example.org']`, CONNECT `127.0.0.1:<echoPort>` → status `403`, echo server saw no connection, log `allowed:false`.
  - `CONNECT to an allowed host on a port not on the list → 403`.
  - `plain GET http://… → 403 and logged` (`allowed:false`, port 80 when absent).
  - `upstream: tunnels through a second gateway`: gateway B `allow:['127.0.0.1'], ports:[echoPort]`; gateway A `allow:['127.0.0.1'], ports:[echoPort], upstream:'http://127.0.0.1:<B>'`; echo works through A; B's log has the entry.
  - `upstream that never answers → 502 within the timeout`: upstream = a TCP server that accepts and stays silent; `upstreamTimeoutMs: 300`; client gets `502` in < 2 s.

- [ ] **Step 2: Run** `PATH=/c/nvm4w/nodejs:$PATH node --test sandbox/egress/` → FAIL (module not found).

- [ ] **Step 3: Implement `sandbox/egress/egress.mjs`** with `node:http` server's `'connect'` event (`net.connect` to the target, or to the upstream followed by a `CONNECT` request whose response status line must be `200`); `'request'` event answers 403. Normalise host: lowercase, strip trailing `.`, strip `[]` for IPv6. Log only after the decision. Destroy both sockets on either side's error/close. Direct-run detection: `import.meta.url === pathToFileURL(process.argv[1]).href`.

- [ ] **Step 4: Run** the tests → all PASS.

- [ ] **Step 5: Commit** `feat(sandbox): egress gateway - CONNECT-only allowlist proxy`.

---

### Task 2: Docker plumbing and config

**Files:**
- Modify: `backend/src/main/java/dev/playforge/sandbox/docker/DockerRunSpec.java`, `DockerOps.java`, `CliDockerOps.java`
- Modify: `backend/src/main/java/dev/playforge/config/PlayforgeProperties.java`, `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/dev/playforge/sandbox/docker/CliDockerOpsTest.java`

**Interfaces:**
- Produces:
  - `DockerRunSpec` gains trailing components `String name` (nullable), `String network` (nullable), `String networkAlias` (nullable), `Map<String,String> env` (literal values, may be empty), `List<String> command` (empty = image default). Keep a 7-arg constructor with today's components that passes `null, null, null, Map.of(), List.of()` so existing call sites compile; add `DockerRunSpec withNetwork(String network, Map<String,String> env)` returning a copy.
  - `DockerOps`: `void ensureNetwork(String name, boolean internal)`; `void connect(String network, String containerId)`; `String containerIp(String containerId, String network)` (empty string if not attached); `List<String> findRunning(Map<String,String> labels)`.
  - `PlayforgeProperties.SandboxConfig` gains `Egress egress`; `record Egress(Boolean enabled, String upstream, List<String> allow, String script)`; `SandboxConfig.egressEnabled()` = `egress != null && !Boolean.FALSE.equals(egress.enabled())`.

- [ ] **Step 1: Failing tests in `CliDockerOpsTest`:**
  - `runWithNetworkAndLiteralEnv`: spec `SPEC.withNetwork("playforge-sandbox", Map.of("HTTPS_PROXY","http://egress:3128"))` → command contains `--network`, `playforge-sandbox`, and `-e`, `HTTPS_PROXY=http://egress:3128` (literal env goes as `NAME=value`; host-sourced secrets still go by name only — existing test keeps passing).
  - `runNamedWithAliasAndCommand`: name `playforge-egress`, alias `egress`, command `node /egress/egress.mjs` → contains `--name playforge-egress`, `--network-alias egress`, and the command tokens come **after** the image.
  - `runWithoutNetworkIsUnchanged`: `SPEC` → no `--network`, no `--name`.
  - `ensureNetworkCreatesWhenMissing`: runner returns exit 1 for `network inspect` → next command is `docker network create --internal playforge-sandbox`; exit 0 → no create. (Extend `RecordingRunner` with a per-command exit-code function.)
  - `containerIpReadsTheNamedNetwork`: command is `docker inspect -f {{(index .NetworkSettings.Networks "playforge-sandbox").IPAddress}} <id>`; stdout `172.30.0.5` → returns it.
  - `findRunningFiltersByLabels`: `docker ps -q --filter label=a=b`.

- [ ] **Step 2: Run** `cd backend && ./mvnw -q test -Dtest=CliDockerOpsTest` → FAIL (compile).

- [ ] **Step 3: Implement.** `removeLabeled` reuses the label-filter building of `findRunning` (but keeps `-a`). `connect` = `docker network connect <network> <id>`.

- [ ] **Step 4: Config.** `application.yml` under `playforge.sandbox`:
  ```yaml
  egress:
    enabled: true
    upstream: ""
    script: ../sandbox/egress/egress.mjs
    allow: [api.anthropic.com, chatgpt.com, auth.openai.com, api.openai.com, registry.npmjs.org, registry.npmmirror.com, cdn.npmmirror.com]
  ```
  Fix any test that builds `SandboxConfig` by hand (add `null` egress).

- [ ] **Step 5: Run** `./mvnw -q test` → all PASS. **Commit** `feat(sandbox): docker networks, named containers and egress config`.

---

### Task 3: `EgressGateway` and wiring the pool

**Files:**
- Create: `backend/src/main/java/dev/playforge/sandbox/docker/EgressGateway.java`, `backend/src/main/java/dev/playforge/sandbox/BlockedRequest.java`
- Modify: `ContainerPool.java` (prepare hook), `DockerSandboxProvider.java`, `DockerSandbox.java`, `backend/src/main/java/dev/playforge/sandbox/Sandbox.java`
- Test: `backend/src/test/java/dev/playforge/sandbox/docker/EgressGatewayTest.java`, `ContainerPoolTest.java`

**Interfaces:**
- Consumes: Task 2's `DockerOps` methods and `DockerRunSpec` components.
- Produces:
  - `record BlockedRequest(String host, int port, int count)` in `dev.playforge.sandbox`.
  - `Sandbox`: `default List<BlockedRequest> blockedSince(Instant since) { return List.of(); }`.
  - `EgressGateway(DockerOps ops, String image, Path script, List<String> allow, String upstream)`; constants `NETWORK = "playforge-sandbox"`, `NAME = "playforge-egress"`, `PROXY = "http://egress:3128"`; `void ensure()`; `void remove()`; `Map<String,String> sandboxEnv()` (the five variables from Global Constraints); `List<BlockedRequest> blocked(String clientIp, Instant since)`; package-private `static List<BlockedRequest> parse(List<String> jsonLines, String clientIp, Instant since)`.
  - `ContainerPool(DockerOps, DockerRunSpec, int size, boolean sweepOnStart, Runnable prepare)` — `prepare` runs before every `runDetached` (its exception becomes `lastCreateError`); existing constructors pass a no-op.
  - `DockerSandbox(String containerId, ContainerPool pool, DockerOps ops, EgressGateway egress)` (egress nullable; old 3-arg constructor passes null).

- [ ] **Step 1: Failing tests (`EgressGatewayTest`, with a fake `DockerOps` recording calls):**
  - `ensureCreatesNetworkAndGateway`: no running gateway → `ensureNetwork("playforge-sandbox", true)`; `removeLabeled({playforge.egress=app})`; `runDetached` with name `playforge-egress`, network `playforge-sandbox`, alias `egress`, cpus `0.5`, memory `128m`, a read-only mount of the script's absolute path at `/egress/egress.mjs`, env `EGRESS_ALLOW=api.anthropic.com,…`, label `playforge.egress.config=<hash>`, command `node /egress/egress.mjs`; then `connect("bridge", id)`.
  - `ensureKeepsAMatchingGateway`: `findRunning` with the current config label returns an id → no `runDetached`.
  - `ensureReplacesAGatewayWithOtherConfig`: running gateway only under the old hash → removed and recreated.
  - `parseKeepsRefusalsFromThisClientSinceTheStep`: lines for `172.30.0.5` refused `evil.com:443` twice after `since`, once before `since - 2s`, one allowed line, one from `172.30.0.6`, one garbage line → `[BlockedRequest("evil.com", 443, 2)]`; grouped by host+port, ordered by first appearance.
  - `blockedReadsTheLogInTheGateway`: `exec(NAME, ["cat","/tmp/egress.jsonl"], …)` stdout lines are parsed; a non-zero exit returns `List.of()`.
  - `ContainerPoolTest.prepareRunsBeforeEachContainerAndItsFailureIsTheCreateError`.

- [ ] **Step 2: Run** `./mvnw -q test -Dtest='EgressGatewayTest,ContainerPoolTest'` → FAIL.

- [ ] **Step 3: Implement.** Hash = first 12 hex of SHA-256 over `image|allow joined|upstream|script content`. `since` comparison allows 2 s of clock skew. Parse with the project's Jackson `ObjectMapper`.

- [ ] **Step 4: Wire.** `DockerSandboxProvider`: when `config.egressEnabled()`, build the gateway (script path resolved like other paths: `Path.of(script).toAbsolutePath().normalize()`), use `spec.withNetwork(NETWORK, gateway.sandboxEnv())`, pass `gateway::ensure` as `prepare`, call `gateway.ensure()` in `healthCheck()` (catch + warn), `gateway.remove()` in `stop()` after `pool.shutdown()`, and pass it to `DockerSandbox`. `DockerSandbox.blockedSince` looks up and caches `ops.containerIp(containerId, NETWORK)`; any exception → warn + `List.of()`. Disabled → nothing changes.

- [ ] **Step 5: Run** `./mvnw -q test` → PASS. **Commit** `feat(sandbox): egress gateway lifecycle; sandboxes join the internal network`.

---

### Task 4: `NETWORK_BLOCKED` in the run and the story

**Files:**
- Modify: `backend/src/main/java/dev/playforge/event/RunEventType.java`, `backend/src/main/java/dev/playforge/orchestration/RunOrchestrator.java` (step loop, ~line 160)
- Modify: `web/src/story/story.ts`, `web/src/components/RunStory.vue`, `web/src/api/types.ts` (event type union if it lists types)
- Test: `backend/src/test/java/dev/playforge/orchestration/NetworkBlockedTest.java`, `web/src/story/story.test.ts`

**Interfaces:**
- Consumes: `Sandbox.blockedSince(Instant)`, `BlockedRequest`.
- Produces: event `NETWORK_BLOCKED`, source `system`, payload `{"requests":[{"host":"evil.com","port":443,"count":2}]}`, published **after the step's work and before its `STEP_FINISHED`** (success or failure); `Entry.blocked: string` ('' when none).

- [ ] **Step 1: Failing backend test** `NetworkBlockedTest` (pattern of `ReviewResyncTest`: a `SandboxProvider` bean wrapping `LocalSandboxProvider` whose sandbox returns `[BlockedRequest("evil.com",443,2)]` from `blockedSince` for the CODE step only): run completes; events contain exactly one `NETWORK_BLOCKED` with that payload, positioned between the CODE `STEP_STARTED` and its `STEP_FINISHED`. Second test: `blockedSince` throws → run still `SUCCEEDED`, no `NETWORK_BLOCKED`.

- [ ] **Step 2: Implement** in the step loop: `Instant started = Instant.now();` and `try { runStep(...) } finally { reportBlocked(ctx, started); }` inside the existing try, so it runs before either `STEP_FINISHED`. Skip on cancellation shutdown. Run `./mvnw -q test` → PASS.

- [ ] **Step 3: Failing web test** in `story.test.ts`: a CODE segment with a `NETWORK_BLOCKED` event (`requests: [{host:'evil.com',port:443,count:2},{host:'x.io',port:443,count:1}]`) → `entry.blocked === '拦截了 3 次外联：evil.com、x.io'`; no event → `''`; a requests entry with port ≠ 443 shows `host:port`.

- [ ] **Step 4: Implement** in `story.ts`; `RunStory.vue` renders `<p v-if="e.blocked" class="blocked" data-test="blocked">{{ e.blocked }}</p>` under the entry's lines, styled like `.checklist` with `var(--muted)` and a small shield-free text (no icon). Run `cd web && npx vitest run && npx vue-tsc --noEmit` → PASS.

- [ ] **Step 5: Commit** `feat: refused outbound requests show up in the run story`.

---

### Task 5: Docker integration test, docs, real acceptance

**Files:**
- Create: `backend/src/test/java/dev/playforge/sandbox/docker/EgressIntegrationTest.java` (`@Tag("docker")`, skipped without Docker)
- Modify: `docs/decisions.md`, `docs/runbook.md`, `README.md`, `sandbox/README.md`

- [ ] **Step 1: Integration test** with real `CliDockerOps`, `EgressGateway` (allow `registry.npmmirror.com`) and a pool on `playforge-sandbox:p4`:
  - `allowedHostThroughTheGateway`: in the sandbox, `node -e "fetch('https://registry.npmmirror.com/vite',{method:'HEAD'}).then(r=>console.log(r.status))"` prints `200` (relies on `NODE_USE_ENV_PROXY=1`).
  - `otherHostIsRefusedAndReported`: same against `https://example.com` fails; `sandbox.blockedSince(start)` contains `example.com:443`.
  - `noRouteWithoutTheProxy`: `env -u HTTPS_PROXY -u HTTP_PROXY node -e "fetch('https://1.1.1.1')…"` fails.
  - `hardCheckPlaytestStillLoadsTheLocalGame`: copy `/template` into `/workspace` and run the existing hard-check command the `Playtester` uses; report `passed`.
  - `@AfterAll` removes the gateway (and leaves the network).

- [ ] **Step 2: Run** `./mvnw -q test -Dgroups=docker -Dtest=EgressIntegrationTest` with Docker Desktop running → PASS. If the plain machine can't reach Anthropic directly, set `upstream: http://host.docker.internal:7899` and note it in the runbook.

- [ ] **Step 3: Real end-to-end** with the real-engine profile: new game + one follow-up; check `docker exec playforge-egress cat /tmp/egress.jsonl` for refused **required** hosts, add them to the allow list, repeat until zero. Then demonstrate one refusal (a follow-up asking the game to fetch an external URL, or a playtest script fetch) and confirm the story line in the browser (Edge screenshot).

- [ ] **Step 4: Docs.** decisions.md: new "决策 11（P7）沙箱出口白名单" — why internal network + proxy (not just env vars), why domains not IPs, final allow list, acceptance result; flip "容器网络完全开放" in 风险表 and 面试速答 to the new answer; tick the two v1 completion boxes. runbook: how to view the gateway log, how to add a domain, `upstream`. README 技术要点: one row.

- [ ] **Step 5: Full test run** (`./mvnw -q test`, `npx vitest run`, `node --test sandbox/egress/`) → all PASS. **Commit** `docs: P7 egress allowlist`, merge to master, push.
