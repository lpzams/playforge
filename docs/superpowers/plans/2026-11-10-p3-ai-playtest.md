# P3 AI 试玩 v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把"试玩固定通过"换成真实试玩：在沙箱容器里构建游戏，用 Playwright 打开它，通过探针读状态、模拟按键、抓报错、看画面，不通过就交给 Claude 修；通过的游戏可以在浏览器里直接玩。

**Architecture:** 沙箱镜像升级为 `p3`，预装游戏模板（Vite + 探针 SDK，依赖已装好）和试玩脚本 `run.mjs`（Playwright + Chromium）。新项目同步进容器时自动铺上模板。Java 端的 `DockerPlaytester` 依次执行 `npm run build` 和 `run.mjs`，把它打印的报告 JSON 转成 `PlaytestReport`。试玩通过后，编排器把 `dist/` 导出到宿主机 `previews/<projectId>/`，由 `/preview/<projectId>/` 提供在线游玩。

**Tech Stack:** P2 全部 + Playwright 1.49.1（Chromium）、pngjs 7.0.0（截图像素分析）、模板依赖 vite 5.4.11、phaser 3.87.0（预装，生成的游戏可直接 import）

**Spec:** [docs/superpowers/specs/2026-11-08-playforge-design.md](../specs/2026-11-08-playforge-design.md) §7（探针协议、试玩流程）、§8（预览接口）；需求见 [docs/decisions.md](../../decisions.md) 决策 7

## Global Constraints

- P1、P2 的 Global Constraints 继续有效
- 镜像 `playforge-sandbox:p3`，`application.yml` 的 `playforge.sandbox.image` 改为它
- 模板位于容器内 `/template`（含 `node_modules`），试玩脚本位于 `/opt/playtest`（含 `node_modules`），浏览器位于 `/ms-playwright`（`PLAYWRIGHT_BROWSERS_PATH`）
- 模板的 `vite.config.js` 必须是 `base: './'`（预览挂在子路径下，绝对路径的资源会加载失败）
- 探针协议 v1 以 spec §7.1 为准；模板提供 `src/probe-sdk.js`，导出 `installProbe(options)` 和 `probeEvent(type, data)`
- 试玩脚本一次运行的总时限 90 秒；等探针出现最多 10 秒；每个 action 按住 400 毫秒
- 试玩报告：`run.mjs` 在 stdout **最后一行**输出一个 JSON：`{"playtest":1,"passed":bool,"failures":[string],"checks":[{"name","passed","detail"}],"durationMs":n}`
- 硬性检查的名称固定为：`build`、`load`、`probe`、`no_errors`、`not_blank`、`action:<name>`（每个 action 一项）、`restart`
- 判为画面空白的标准：截图灰度标准差 < 2
- 预览目录 `playforge.preview-root`，默认 `../previews`，**不在任何项目的 git 仓库里**
- 截图只保存在容器内，P3 不导出（回放用的截图留到 P5 再处理）

## Review Focus

1. **生成的游戏卡死**（死循环、无限 `alert`、跳转离开页面）→ 试玩在 90 秒总时限内结束，报告"页面无响应"或类似失败原因，不能卡住整个 Run —— Task 2 测试（`hang` 夹具）
2. **预览路径穿越**（`/preview/x/../../secret`、`%2e%2e`、反斜杠）→ 一律 404，绝不返回 `previews/<projectId>/` 以外的文件 —— Task 5 测试
3. **游戏用了绝对路径的资源**（`base: '/'`）→ 试玩时游戏挂在子路径 `/game/` 下，资源加载失败会被报告为 `load` 或 `no_errors` 失败，而不是等到用户预览时才发现 —— Task 2 测试（`absolute-assets` 夹具）
4. **Claude 删掉或改坏了探针** → `probe` 检查失败，失败原因写明"window.__GAME_PROBE__ not found within 10s" —— Task 2 测试（`noprobe` 夹具）
5. **试玩脚本的 stdout 里混有其他输出**（npm 警告、游戏的 `console.log` 转发）→ Java 只认最后一个以 `{"playtest":` 开头的行；找不到时报告"playtest harness produced no report"，并附上 stderr 的末尾内容 —— Task 4 测试

---

## 前置

- [ ] Docker Desktop 已启动（`docker version` 能看到 Server 版本）

---

### Task 1: 游戏模板 + 探针 SDK + p3 镜像

**Files:**
- Create: `sandbox/template/package.json`、`sandbox/template/vite.config.js`、`sandbox/template/index.html`、`sandbox/template/.gitignore`、`sandbox/template/src/probe-sdk.js`、`sandbox/template/src/main.js`
- Create: `sandbox/playtest/package.json`（依赖 `playwright@1.49.1`、`pngjs@7.0.0`）
- Modify: `sandbox/Dockerfile`（安装 Chromium 和依赖、复制模板和 playtest 目录并分别 `npm ci`/`npm install`、设置 `PLAYWRIGHT_BROWSERS_PATH=/ms-playwright`），镜像打 `p3` 标签
- Modify: `sandbox/README.md`

**Interfaces:**
- Produces:
  - `/template`：`package.json` 的 scripts 包含 `"build": "vite build"`；依赖 `vite@5.4.11`、`phaser@3.87.0`；`.gitignore` 包含 `node_modules/` 和 `dist/`
  - `src/probe-sdk.js`：
    ```js
    export function installProbe({ getState, actions, start, restart }) // 设置 window.__GAME_PROBE__ = { version: 1, getState, actions, events, start, restart }
    export function probeEvent(type, data)                              // 往 events 追加 { t: performance.now(), type, data }
    ```
  - `src/main.js`：一个能玩的最小游戏，同时也是"好游戏"样本：方块用 `ArrowLeft`/`ArrowRight` 移动，`Space` 开始，碰到墙后 status 变为 `gameover`，`restart()` 回到 `ready`。完整接入探针
  - 模板的 `node_modules` 在构建镜像时就装好；`/opt/playtest/node_modules` 同理

- [ ] **Step 1:** 写模板和 Dockerfile，`docker build -t playforge-sandbox:p3 sandbox` → 预期成功
- [ ] **Step 2: 离线可构建验证**：`docker run --rm --network none playforge-sandbox:p3 sh -c 'cp -a /template /tmp/t && cd /tmp/t && npm run build && ls dist'` → 预期输出里有 `index.html` 和 `assets`（证明不联网也能构建）
- [ ] **Step 3: 浏览器可用验证**：`docker run --rm playforge-sandbox:p3 node -e "require('/opt/playtest/node_modules/playwright').chromium.launch().then(b=>b.close()).then(()=>console.log('ok'))"` → 预期输出 `ok`
- [ ] **Step 4: Commit** `feat(sandbox): p3 image with game template, probe SDK and Playwright`

---

### Task 2: 试玩脚本 run.mjs + 静态夹具 + 集成测试

**Files:**
- Create: `sandbox/playtest/run.mjs`
- Create: `sandbox/playtest/fixtures/{good,broken,noprobe,frozen,blank,hang,absolute-assets}/index.html`（纯静态页面，不需要构建；`good` 是一个内联的最小探针游戏）
- Modify: `sandbox/Dockerfile`（把 `run.mjs` 和 `fixtures/` 复制进 `/opt/playtest`），重新构建 `p3`
- Test: `backend/src/test/java/dev/playforge/playtest/PlaytestHarnessIntegrationTest.java`（`@Tag("docker")`）

**Interfaces:**
- Produces: `node /opt/playtest/run.mjs --dist <dir>`
  - 用 Node 内置 `http` 把 `<dir>` 挂在 `http://127.0.0.1:<随机端口>/game/` 下（只提供 `<dir>` 内的文件）
  - 依次执行 Global Constraints 里的检查（`build` 由 Java 端负责，这里不出现），任一检查失败都继续做后面能做的检查，最后统一输出报告
  - 总时限 90 秒，到时强制关闭浏览器并输出已有结果，同时加一条失败："playtest timed out (page unresponsive?)"
  - 检查内容：
    - `load`：页面能打开，HTTP 状态码 200，没有资源返回 404
    - `probe`：10 秒内出现 `window.__GAME_PROBE__`，`version === 1`，状态为 `ready` 或 `playing`
    - `no_errors`：整个过程中没有 `pageerror`，也没有 `console.error`
    - `not_blank`：截图的灰度标准差 ≥ 2
    - `action:<name>`：对每个 action，若状态是 `ready` 先调用 `start()`；记录状态快照 → 按住它的按键 400 毫秒 → 再取快照；`score`、`status`、实体坐标三者中至少有一项变化才算通过
    - `restart`：调用 `restart()` 后 1 秒内，状态回到 `ready` 或 `playing`
  - 屏蔽 `alert`/`confirm`/`prompt` 弹窗（自动关闭），并禁止跳转到其他源

- [ ] **Step 1: 写失败测试** `PlaytestHarnessIntegrationTest`（Docker 不可用时跳过）：每个测试用 `docker run --rm playforge-sandbox:p3 node /opt/playtest/run.mjs --dist /opt/playtest/fixtures/<name>`，解析最后一行 JSON：
  - `goodFixturePasses`：`passed == true`，checks 里包含 `load`、`probe`、`no_errors`、`not_blank`、`restart` 和至少一个 `action:` 项，全部通过
  - `brokenFixtureReportsError`（页面里有 `throw new Error('boom')`）：`no_errors` 失败，detail 包含 `boom`
  - `noProbeFixture`：`probe` 失败，detail 包含 `window.__GAME_PROBE__ not found within 10s`
  - `frozenFixture`（探针存在，但按键对状态没有影响）：`action:left` 失败
  - `blankFixture`（纯黑 canvas）：`not_blank` 失败
  - `hangFixture`（加载 3 秒后进入 `while(true){}`）：整个命令在 100 秒内结束，`passed == false`
  - `absoluteAssetsFixture`（`<script src="/assets/main.js">`）：`load` 或 `no_errors` 失败
- [ ] **Step 2:** 重新构建镜像后运行 `./mvnw -q test -Dtest=PlaytestHarnessIntegrationTest` → FAIL（`run.mjs` 还不存在）
- [ ] **Step 3:** 实现 `run.mjs`
- [ ] **Step 4:** 重新构建 `p3`，运行同上 → PASS（确认测试实际运行，没有被跳过）
- [ ] **Step 5: Commit** `feat(playtest): Playwright harness with probe-driven hard checks`

---

### Task 3: 新项目铺模板 + 导出目录

**Files:**
- Modify: `backend/src/main/java/dev/playforge/sandbox/docker/CliDockerOps.java`（`copyIn` 解包后补充：工作区没有 `package.json` 就复制整个模板；没有 `node_modules` 就从模板复制一份）
- Modify: `backend/src/main/java/dev/playforge/sandbox/docker/DockerOps.java`、`Sandbox.java`、`DockerSandbox.java`、`local/LocalSandboxProvider.java`（新增 `exportDir`）
- Modify: `backend/src/main/resources/application.yml`（镜像改为 `p3`）
- Test: 补充 `DockerSandboxIntegrationTest`、`CliDockerOpsTest`

**Interfaces:**
- Produces:
  ```java
  // Sandbox 新增：把沙箱内的目录（相对 /workspace）镜像到宿主机目录；目录不存在时抛 IllegalStateException
  void exportDir(String relativePath, Path hostDir);
  // DockerOps 新增：
  void copyDirOut(String containerId, String containerPath, Path hostDir); // docker exec tar -c -C <path> . | TarSync.unpackMirror
  ```
  `copyIn` 在容器内追加执行的脚本：
  ```sh
  [ -f package.json ] || cp -a /template/. /workspace/
  [ -d node_modules ] || cp -a /template/node_modules /workspace/node_modules
  ```
  `LocalSandbox.exportDir`：如果 `workdir/<relativePath>` 存在就复制过去，否则抛异常。

- [ ] **Step 1: 写失败测试**：
  - `CliDockerOpsTest.copyInSeedsTemplate`：记录到的 `copyIn` 脚本包含 `cp -a /template/. /workspace/` 和 `/template/node_modules`
  - `DockerSandboxIntegrationTest.emptyWorkspaceGetsTemplate`（用 `p3` 镜像）：宿主目录为空 → syncIn → exec `test -f package.json && test -d node_modules && test -f src/probe-sdk.js` 退出码为 0
  - `DockerSandboxIntegrationTest.existingProjectKeepsItsFilesButGetsNodeModules`：宿主目录有自己的 `package.json`（内容为 `{"name":"mine"}`）→ syncIn → `cat package.json` 包含 `mine`，并且 `node_modules` 存在
  - `DockerSandboxIntegrationTest.exportDirMirrorsDist`：syncIn 后执行 `npm run build`，再 `exportDir("dist", host/out)` → `host/out/index.html` 存在
  - `DockerSandboxIntegrationTest.exportMissingDirFails`：`exportDir("nope", …)` 抛 `IllegalStateException`
- [ ] **Step 2:** `./mvnw -q test -Dtest='CliDockerOpsTest,DockerSandboxIntegrationTest'` → FAIL
- [ ] **Step 3:** 实现
- [ ] **Step 4:** 运行同上 → PASS（IT 实际运行）
- [ ] **Step 5: Commit** `feat(sandbox): seed new projects from template; export directories from sandbox`

---

### Task 4: DockerPlaytester + 编排器接入 + 提示词更新

**Files:**
- Create: `backend/src/main/java/dev/playforge/playtest/DockerPlaytester.java`、`PlaytestReportParser.java`
- Modify: `backend/src/main/java/dev/playforge/playtest/AlwaysPassPlaytester.java`（仅在 `provider=local` 时注册）
- Modify: `backend/src/main/java/dev/playforge/orchestration/RunOrchestrator.java`（PLAYTEST 通过后导出 `dist` 到预览目录；`PLAYTEST_RESULT` 事件带上 checks）
- Modify: `backend/src/main/java/dev/playforge/orchestration/PromptTemplates.java`（告诉 Claude 工作区已有模板、必须使用 `probe-sdk.js`、`base: './'` 不能改、每个 action 必须真的改变状态）
- Modify: `backend/src/main/java/dev/playforge/config/PlayforgeProperties.java`（`previewRoot` + `previewDir()`）、`application.yml`、`application-test.yml`
- Test: `backend/src/test/java/dev/playforge/playtest/DockerPlaytesterTest.java`、`PlaytestReportParserTest.java`；补充 `ReviewResyncTest`（或新建 `PreviewExportTest`）、`PromptTemplatesTest`

**Interfaces:**
- Consumes: `Sandbox.exec`、`Sandbox.exportDir`（Task 3），`run.mjs` 的报告格式（Task 2）
- Produces:
  ```java
  final class PlaytestReportParser {
    /** 取最后一个以 {"playtest": 开头的行；没有则返回 failed 报告，failures = ["playtest harness produced no report: " + stderrTail]。 */
    static PlaytestReport parse(List<String> stdoutLines, String stderrTail);
  }
  @Component @ConditionalOnProperty(name="playforge.sandbox.provider", havingValue="docker")
  public class DockerPlaytester implements Playtester {
    // 1. sandbox.exec(["npm","run","build"], null, 3min) → 退出码非 0：返回 failed，failures = ["build failed: " + 输出末尾 2000 字符]，checks 里 build=false
    // 2. sandbox.exec(["node","/opt/playtest/run.mjs","--dist","/workspace/dist"], null, 2min) → PlaytestReportParser.parse
    // 返回的 details 包含 "checks"（列表）和 "durationMs"
  }
  // PlayforgeProperties 新增组件 String previewRoot；方法 Path previewDir()（绝对、规范化路径）
  ```
  编排器：PLAYTEST 通过后调用 `ctx.sandbox().exportDir("dist", props.previewDir().resolve(projectId))`。导出失败只发一条 `LOG` 事件，不影响 Run 的结果。`PLAYTEST_RESULT` 的 payload 改为 `{passed, failures, checks}`。

- [ ] **Step 1: 写失败测试** `PlaytestReportParserTest`：
  - `takesLastReportLine`：行为 `["npm warn x", "{\"playtest\":1,\"passed\":false,...}", "log", "{\"playtest\":1,\"passed\":true,\"failures\":[],\"checks\":[],\"durationMs\":5}"]` → `passed == true`
  - `noReportLineIsFailure`：行为 `["Error: Cannot find module"]`，stderr 为 `"stack..."` → `passed == false`，`failures.get(0)` 以 `playtest harness produced no report` 开头并包含 `stack...`
  - `malformedReportIsFailure`：`{"playtest":1,"passed":tr` → 同上
- [ ] **Step 2: 写失败测试** `DockerPlaytesterTest`（用 `FakeExecSandbox` 的扩展版本：可以按调用顺序给出不同的输出和退出码）：
  - `buildFailureShortCircuits`：第 1 次 exec 退出码 1、输出 `"error TS..."` → `passed == false`，failures[0] 以 `build failed:` 开头，`run.mjs` 没有被调用
  - `runsHarnessAfterBuild`：第 2 次 exec 的命令是 `["node","/opt/playtest/run.mjs","--dist","/workspace/dist"]`，报告透传
- [ ] **Step 3: 写失败测试**（编排层，使用记录调用的沙箱）：
  - `passingPlaytestExportsDistToPreviewDir`：试玩通过 → 沙箱的 `exportDir("dist", <previewDir>/<projectId>)` 被调用 1 次
  - `failingPlaytestDoesNotExport`
  - `exportFailureDoesNotFailRun`：`exportDir` 抛异常 → Run 仍为 SUCCEEDED，并有一条 `LOG` 事件
  - `PromptTemplatesTest.codePromptMentionsTemplateAndProbeSdk`：`code("x")` 包含 `src/probe-sdk.js`、`installProbe` 和 `base: './'`
- [ ] **Step 4:** 运行以上测试 → FAIL
- [ ] **Step 5:** 实现
- [ ] **Step 6:** `./mvnw -q test`（全量）→ PASS
- [ ] **Step 7: Commit** `feat(playtest): docker playtester wired into orchestration; export builds for preview`

---

### Task 5: 预览接口

**Files:**
- Create: `backend/src/main/java/dev/playforge/api/PreviewController.java`
- Test: `backend/src/test/java/dev/playforge/api/PreviewControllerTest.java`

**Interfaces:**
- Consumes: `PlayforgeProperties.previewDir()`（Task 4）
- Produces: `GET /preview/{projectId}/**`
  - 路径为空或以 `/` 结尾时返回 `index.html`；`GET /preview/{projectId}`（没有结尾斜杠）重定向（302）到 `/preview/{projectId}/`，否则游戏里的相对路径会解析错
  - `projectId` 必须匹配 `[a-z0-9]{12}`，否则返回 404
  - 解析出的文件路径规范化后必须位于 `previewDir/<projectId>` 之内，并且是普通文件，否则返回 404
  - `Content-Type` 按扩展名推断（`.html`、`.js`、`.css`、`.png`、`.json`、`.wasm` 等）；`Cache-Control: no-store`

- [ ] **Step 1: 写失败测试** `PreviewControllerTest`（MockMvc；在 `@TempDir` 的预览目录里放好 `abcdefghijkl/index.html`、`abcdefghijkl/assets/main.js`，并在预览目录**旁边**放一个 `secret.txt`）：
  - `servesIndexForTrailingSlash`：`/preview/abcdefghijkl/` → 200，`text/html`，内容正确
  - `redirectsWithoutTrailingSlash`：`/preview/abcdefghijkl` → 302，`Location` 为 `/preview/abcdefghijkl/`
  - `servesAssetWithContentType`：`/preview/abcdefghijkl/assets/main.js` → 200，内容类型包含 `javascript`
  - `missingFileIs404`
  - `traversalIs404`：`/preview/abcdefghijkl/../secret.txt`、`/preview/abcdefghijkl/%2e%2e/secret.txt`、`/preview/abcdefghijkl/..%5csecret.txt` → 全部 404，响应体里不包含 `secret.txt` 的内容
  - `badProjectIdIs404`：`/preview/..%2f/secret.txt`、`/preview/ABC/` → 404
- [ ] **Step 2:** `./mvnw -q test -Dtest=PreviewControllerTest` → FAIL
- [ ] **Step 3:** 实现
- [ ] **Step 4:** 运行同上 → PASS
- [ ] **Step 5: Commit** `feat(api): preview endpoint for built games`

---

### Task 6: 真实端到端验收

**Files:**
- Modify: `docs/p2-runbook.md` → 重命名为 `docs/runbook.md`，补充 P3 内容（镜像改为 p3、预览地址、试玩报告怎么看、验收记录）

- [ ] **Step 1:** `./mvnw -q test`（全量，Docker 已启动，所有 IT 实际运行）→ PASS
- [ ] **Step 2:** 用 `real` profile 启动，创建项目"一个躲陨石的太空飞船小游戏：方向键左右移动飞船，陨石从上往下落，撞到陨石游戏结束，显示得分，按空格重新开始"
- [ ] **Step 3:** 观察 SSE：`PLAYTEST_RESULT` 带 checks；如果第一次试玩失败，能看到 `FIX(trigger=PLAYTEST)` 和第二次试玩。记录最终状态
- [ ] **Step 4:** 浏览器打开 `http://localhost:8090/preview/<projectId>/`，游戏能玩（这一步请用户确认）
- [ ] **Step 5:** 停服后确认没有残留容器
- [ ] **Step 6:** 在 runbook 里写验收记录：各步骤耗时、试玩各项检查的结果、是否触发了修复
- [ ] **Step 7: Commit** `docs: runbook for P3 and acceptance record`

---

## P3 完成后

- 全量测试通过（所有 Docker IT 实际运行）+ 真实端到端验收（含用户在浏览器里试玩确认）= P3 完成
- 下一步：P4 本地平台前端（新建、实时过程视图、预览内嵌、项目历史、多轮修改）
