# P4 本地平台前端 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 做一个 Vue3 本地平台：输入一句话新建游戏，实时看着 Claude 写、Codex 审、AI 试玩一步步发生，旁边直接玩生成的游戏，再用文字继续修改。浏览器打开 `http://localhost:8090/` 就能完成全流程，不再需要 curl。

**Architecture:** `web/` 是独立的 Vite + Vue 3 + TypeScript 工程。开发时由 Vite 把 `/api`、`/preview` 代理到 8090；构建产物 `web/dist` 由后端直接托管（单一地址，非 API 路径回退到 `index.html`）。实时过程走已有的 SSE 接口，前端封装一层：按 seq 去重、收到 `RUN_FINISHED` 后主动关闭、断线后带 `Last-Event-ID` 续传。所有 AI 产生的文本一律按纯文本渲染。

**Tech Stack:** Vue 3.5.13、vue-router 4.5.0、Element Plus 2.9.1、TypeScript 5.6.3、Vite 5.4.11（`@vitejs/plugin-vue` 5.2.1）、Vitest 2.1.8 + `@vue/test-utils` 2.4.6 + jsdom 25.0.1、vue-tsc 2.1.10；后端沿用 P1–P3

**Spec:** [docs/superpowers/specs/2026-11-08-playforge-design.md](../specs/2026-11-08-playforge-design.md) §8（API）、§10 路线图 P4；需求见 [docs/decisions.md](../../decisions.md)（决策 8 多轮修改：P4 先做文字；画面圈选在 P6）

## Global Constraints

- 前端代码全部在 `web/`；界面文字用中文
- Node 使用本机 `C:\nvm4w\nodejs`（v21.7.1），npm 源为 npmmirror；依赖版本按 Tech Stack 精确固定（不写 `^`）
- 开发服务器端口 5173，代理 `/api` 和 `/preview` 到 `http://localhost:8090`
- **AI 产生的任何文本（`AGENT_TEXT`、工具输入输出、审查意见、试玩详情、错误信息）只能以纯文本渲染**：禁止 `v-html`、禁止 Markdown 转 HTML。换行用 CSS `white-space: pre-wrap` 保留
- 事件流在页面上最多保留最近 500 条，更早的折叠为一行"已省略 N 条更早的事件"
- 预览用 `<iframe src="/preview/<id>/" sandbox="allow-scripts allow-pointer-lock">`（与后端 CSP 一致），标注"点击游戏画面后用键盘操作"
- 后端托管前端：配置项 `playforge.web-root`，默认 `../web/dist`；目录不存在时不托管（不影响 API）
- 视觉【设计方选择】：深色主题（与游戏画面一致）；项目页三栏：左侧 Run 时间线、中间实时过程、右侧游戏预览

## Review Focus

1. **AI 文本里带 HTML/脚本**（如 `<img src=x onerror=alert(1)>`）→ 页面原样显示这段文字，不执行、不渲染成标签 —— Task 5 测试
2. **Run 结束后 SSE 被浏览器自动重连** → 收到 `RUN_FINISHED` 后不再重连，不会无限挂着连接，也不会重复显示事件 —— Task 3 测试
3. **后端中途重启**（SSE 断开后恢复）→ 带 `Last-Event-ID` 续传，事件不丢不重 —— Task 3 测试
4. **长时间运行产生几千条事件** → 页面只渲染最近 500 条，不卡顿 —— Task 5 测试
5. **用户连点"生成"按钮** → 只发出一次创建请求 —— Task 4 测试

---

## 前置

- [ ] 后端能启动（演示模式即可）；做 Task 6 的真实验收时需要 Docker Desktop 已启动

---

### Task 1: 后端补充（预览标记、时间字段、托管前端）

**Files:**
- Modify: `backend/src/main/java/dev/playforge/api/dto/ProjectView.java`、`dto/RunView.java`、`ProjectController.java`
- Modify: `backend/src/main/java/dev/playforge/config/PlayforgeProperties.java`（`webRoot` + `webDir()`）、`application.yml`、`application-test.yml`
- Create: `backend/src/main/java/dev/playforge/config/WebUiConfig.java`
- Test: 补充 `ProjectControllerTest`；新建 `backend/src/test/java/dev/playforge/config/WebUiConfigTest.java`

**Interfaces:**
- Produces:
  ```java
  record ProjectView(String id, String title, String prompt, Instant createdAt, boolean previewAvailable, List<RunView> runs)
  // previewAvailable = previewDir/<id>/index.html 存在
  record RunView(long id, String projectId, String instruction, RunStatus status, String error, String baseCommit,
                 String resultCommit, Instant createdAt, Instant startedAt, Instant finishedAt, List<StepView> steps)
  // PlayforgeProperties 新增组件 String webRoot；Path webDir()
  ```
  `WebUiConfig`：`webDir()` 存在时，`/**` 从该目录提供静态文件；对于不以 `/api/`、`/preview/` 开头、且找不到对应文件的 GET 请求，返回 `index.html`（前端路由回退）

- [ ] **Step 1: 写失败测试**：
  - `ProjectControllerTest.projectViewHasPreviewFlagAndTimes`：新建项目并跑完（演示引擎）→ `previewAvailable == false`、`createdAt` 非空、Run 的 `startedAt`/`finishedAt` 非空；在预览目录手工放一个 `<id>/index.html` → 再取，`previewAvailable == true`
  - `WebUiConfigTest`（`@DynamicPropertySource` 把 `web-root` 指向临时目录，内含 `index.html` 和 `assets/app.js`）：
    - `servesIndexAtRoot`：`GET /` → 200，内容为 index.html
    - `servesAsset`：`GET /assets/app.js` → 200
    - `spaFallback`：`GET /projects/abc` → 200，内容为 index.html
    - `apiNotShadowed`：`GET /api/health` → `{"status":"ok"}`；`GET /api/nope` → 404（不是 index.html）
- [ ] **Step 2:** `./mvnw -q test -Dtest='ProjectControllerTest,WebUiConfigTest'` → FAIL
- [ ] **Step 3:** 实现
- [ ] **Step 4:** 运行同上 → PASS；`./mvnw -q test`（全量）→ PASS
- [ ] **Step 5: Commit** `feat(api): preview flag and timestamps in views; serve the web UI`

---

### Task 2: 前端工程骨架 + API 客户端

**Files:**
- Create: `web/package.json`、`web/vite.config.ts`、`web/tsconfig.json`、`web/index.html`、`web/src/main.ts`、`web/src/App.vue`、`web/src/router.ts`、`web/src/styles.css`
- Create: `web/src/api/types.ts`、`web/src/api/client.ts`
- Test: `web/src/api/client.test.ts`
- Modify: 根目录 `.gitignore`（加 `web/dist/`；`node_modules/` 已存在）

**Interfaces:**
- Produces:
  ```ts
  // types.ts：与后端 DTO 一一对应
  export type RunStatus = 'QUEUED'|'RUNNING'|'SUCCEEDED'|'FAILED'|'CANCELLED'
  export type StepType = 'CODE'|'REVIEW'|'FIX'|'PLAYTEST'|'COMMIT'
  export interface StepView { id: number; type: StepType; round: number; trigger: 'NONE'|'REVIEW'|'PLAYTEST'; status: 'RUNNING'|'DONE'|'FAILED'; ok: boolean|null }
  export interface RunView { id: number; projectId: string; instruction: string; status: RunStatus; error: string|null; baseCommit: string|null; resultCommit: string|null; createdAt: string; startedAt: string|null; finishedAt: string|null; steps: StepView[] }
  export interface ProjectView { id: string; title: string; prompt: string; createdAt: string; previewAvailable: boolean; runs: RunView[] }
  export interface RunEvent { runId: number; seq: number; ts: string; type: string; source: string; payload: Record<string, unknown> }
  export class ApiError extends Error { status: number }
  // client.ts
  export const api = {
    listProjects(): Promise<ProjectView[]>,
    getProject(id: string): Promise<ProjectView>,
    createProject(prompt: string): Promise<{ projectId: string; runId: number }>,
    createRun(projectId: string, instruction: string): Promise<{ projectId: string; runId: number }>,
    getRun(runId: number): Promise<RunView>,
    cancelRun(runId: number): Promise<void>,
  }
  export const isActive = (s: RunStatus) => s === 'QUEUED' || s === 'RUNNING'
  ```
  非 2xx 时抛 `ApiError`，`message` 取后端 `{"error": "..."}` 的内容，`status` 为 HTTP 状态码。
  路由：`/` → `HomePage`，`/projects/:id` → `ProjectPage`（Task 4、5 实现，这里先放占位组件）。

- [ ] **Step 1:** 写 `package.json`（scripts：`dev`、`build`（`vue-tsc --noEmit && vite build`）、`test`（`vitest run`））、配置文件；`npm install` → 成功
- [ ] **Step 2: 写失败测试** `client.test.ts`（用 `vi.stubGlobal('fetch', ...)` 模拟）：
  - `createProjectPostsJson`：调用 `createProject('躲陨石')` → fetch 收到 `POST /api/projects`，`Content-Type: application/json`，body `{"prompt":"躲陨石"}`
  - `errorBodyBecomesApiError`：响应 409 `{"error":"project x already has an active run"}` → 抛出 `ApiError`，`status === 409`，`message` 包含 `already has an active run`
  - `cancelRunPosts`：`cancelRun(7)` → `POST /api/runs/7/cancel`
- [ ] **Step 3:** `npm test` → FAIL
- [ ] **Step 4:** 实现
- [ ] **Step 5:** `npm test` → PASS；`npm run build` → 成功
- [ ] **Step 6: Commit** `feat(web): Vue 3 app skeleton and API client`

---

### Task 3: 实时事件流

**Files:**
- Create: `web/src/api/events.ts`
- Test: `web/src/api/events.test.ts`（含一个可控的 `FakeEventSource`）

**Interfaces:**
- Produces:
  ```ts
  export interface RunStream { close(): void }
  /**
   * 订阅 /api/runs/{runId}/events。按 seq 去重并保证升序回调；收到 RUN_FINISHED 后回调 onFinished 并关闭连接。
   * 连接出错时由 EventSource 自动重连（浏览器会带 Last-Event-ID），本函数不重复建连。
   */
  export function openRunStream(runId: number, onEvent: (e: RunEvent) => void, onFinished?: () => void,
                                factory?: (url: string) => EventSource): RunStream
  export const FEED_LIMIT = 500
  ```
  后端事件的 SSE `event` 名是事件类型（如 `AGENT_TEXT`），data 是完整的 `RunEvent` JSON；需要为每种 `RunEventType` 注册监听（列表见后端 `RunEventType`）。

- [ ] **Step 1: 写失败测试** `events.test.ts`：
  - `deliversEventsInOrder`：依次推送 seq 1、2、3 → 回调收到 `[1,2,3]`
  - `deduplicatesBySeq`：模拟断线重连后服务端补发了 seq 2、3、4 → 回调只新增 4
  - `closesOnRunFinished`：推送 RUN_FINISHED → `onFinished` 被调用 1 次，`FakeEventSource.close` 被调用，之后再推送的事件不会回调
  - `closeStopsDelivery`：调用 `stream.close()` 后推送的事件不回调
  - `ignoresMalformedData`：data 不是 JSON → 不回调、不抛异常
- [ ] **Step 2:** `npm test` → FAIL
- [ ] **Step 3:** 实现
- [ ] **Step 4:** `npm test` → PASS
- [ ] **Step 5: Commit** `feat(web): resumable run event stream`

---

### Task 4: 首页（新建游戏 + 项目列表）

**Files:**
- Create: `web/src/pages/HomePage.vue`、`web/src/components/ProjectCard.vue`、`web/src/components/StatusTag.vue`
- Test: `web/src/pages/HomePage.test.ts`

**Interfaces:**
- Consumes: `api.listProjects`、`api.createProject`、`isActive`（Task 2）
- Produces:
  - `HomePage`：顶部大号输入框（占位文字"描述你想要的小游戏，例如：一个躲陨石的太空飞船游戏，方向键移动，撞到就结束"）+ "生成"按钮 + 3 个示例需求（点击填入输入框）；下方是项目卡片网格，按创建时间倒序
  - 提交后跳转到 `/projects/<projectId>`；提交过程中按钮禁用并显示加载状态；输入为空时按钮禁用；超过 4000 字提示过长
  - `ProjectCard`：标题、最新一次 Run 的状态（`StatusTag`）、创建时间、有预览时显示"可试玩"标记
  - `StatusTag`：QUEUED→排队中、RUNNING→生成中、SUCCEEDED→已完成、FAILED→失败、CANCELLED→已取消

- [ ] **Step 1: 写失败测试** `HomePage.test.ts`（mock `api` 和 router）：
  - `listsProjectsNewestFirst`：接口返回 2 个项目 → 渲染 2 张卡片，顺序为创建时间倒序，状态文字正确
  - `submitCreatesProjectAndNavigates`：输入需求点击"生成" → `createProject` 被调用 1 次，参数为输入内容，然后 `router.push('/projects/<id>')`
  - `doubleClickCreatesOnce`：`createProject` 返回一个未完成的 Promise，连点两次 → 只调用 1 次
  - `emptyPromptDisablesSubmit`
  - `createErrorIsShown`：`createProject` 抛 `ApiError('prompt must not be blank')` → 页面显示这条错误文字
- [ ] **Step 2:** `npm test` → FAIL
- [ ] **Step 3:** 实现
- [ ] **Step 4:** `npm test` → PASS
- [ ] **Step 5: Commit** `feat(web): home page with prompt box and project list`

---

### Task 5: 项目页（时间线 + 实时过程 + 预览 + 继续修改）

**Files:**
- Create: `web/src/pages/ProjectPage.vue`
- Create: `web/src/components/RunTimeline.vue`、`EventFeed.vue`、`ReviewCard.vue`、`PlaytestChecklist.vue`、`PreviewPane.vue`、`FollowUpBox.vue`
- Test: `web/src/components/EventFeed.test.ts`、`PlaytestChecklist.test.ts`、`web/src/pages/ProjectPage.test.ts`

**Interfaces:**
- Consumes: `api.*`、`openRunStream`、`FEED_LIMIT`
- Produces:
  - `RunTimeline`：每个 Run 一组，显示修改说明（instruction 首行）、状态、步骤徽标（写代码 / 审查 / 修复 / 试玩 / 提交，通过绿色、失败红色、进行中闪烁）；点击某个 Run 后，中间栏显示它的事件
  - `EventFeed`（props：`events: RunEvent[]`）按类型渲染，全部纯文本：
    - `AGENT_TEXT` → 气泡，标注来源（Claude / Codex）
    - `TOOL_CALL` / `TOOL_RESULT` → 一行紧凑记录（工具名 + 摘要），出错标红
    - `FILE_CHANGED` → "修改了 <path>"
    - `REVIEW_RESULT` → `ReviewCard`（阻塞问题红色、建议灰色，含文件和行号）
    - `PLAYTEST_RESULT` → `PlaytestChecklist`
    - `STEP_STARTED` → 分隔线（"第 N 轮 · 审查"等）
    - `RETRY_SCHEDULED`、`LOG`、`REVIEW_UNPARSEABLE` → 灰色提示
    - `RUN_FINISHED` → 结果横幅（成功 / 失败原因 / 已取消）
    - 超过 `FEED_LIMIT` 时只渲染最近 500 条，顶部显示"已省略 N 条更早的事件"
  - `PlaytestChecklist`（props：`checks`）：每项一行 ✓/✗ + 中文名（build→构建、load→加载、probe→探针、not_blank→画面非空白、no_errors→无报错、restart→重新开始、action:x→按键 x）+ detail
  - `PreviewPane`（props：`projectId`、`available`、`version`）：available 时显示 iframe；`version` 变化时刷新 iframe；否则显示"试玩通过后这里会出现游戏"
  - `FollowUpBox`：文本框 + "继续修改"按钮；有进行中的 Run 时禁用，并显示"取消本次生成"按钮
  - `ProjectPage`：加载项目 → 默认选中最新的 Run；最新 Run 进行中则订阅事件流，每收到 `STEP_FINISHED`/`RUN_FINISHED` 就刷新项目详情（更新时间线和预览标记）；Run 结束后 `PreviewPane` 的 `version` 加 1；"继续修改"成功后订阅新 Run；离开页面时关闭事件流

- [ ] **Step 1: 写失败测试** `EventFeed.test.ts`：
  - `rendersAgentTextAsPlainText`：`AGENT_TEXT` 的 text 为 `<img src=x onerror="window.__xss=1">你好` → 渲染出的 DOM 里没有 `img` 元素，`textContent` 包含这段原文，`window.__xss` 未定义
  - `reviewIssuesShowSeverity`：`REVIEW_RESULT` 带 1 个 BLOCKER、1 个 SUGGESTION → 两条都显示，BLOCKER 带"阻塞"标记
  - `capsAtFeedLimit`：传入 2000 条 `LOG` 事件 → 渲染的事件条目数为 500，并显示"已省略 1500 条更早的事件"
  - `runFinishedBanner`：`RUN_FINISHED` 状态 FAILED、reason "playtest still failing after 2 fix attempts" → 显示失败横幅和原因文字
- [ ] **Step 2: 写失败测试** `PlaytestChecklist.test.ts`：
  - `labelsAndMarks`：checks 为 `[{name:'build',passed:true},{name:'action:left',passed:false,detail:'no change'}]` → 显示"构建 ✓"和"按键 left ✗ no change"
- [ ] **Step 3: 写失败测试** `ProjectPage.test.ts`（mock `api` 和 `openRunStream`）：
  - `subscribesToActiveRun`：最新 Run 为 RUNNING → `openRunStream` 用该 runId 调用；推送的事件出现在页面上
  - `followUpDisabledWhileActive`：最新 Run 为 RUNNING → "继续修改"禁用，"取消本次生成"可见；点击取消 → `api.cancelRun(runId)`
  - `followUpCreatesRunAndSubscribes`：最新 Run 为 SUCCEEDED → 输入"敌人再快一点"点"继续修改" → `createRun(projectId, '敌人再快一点')`，随后用新 runId 订阅
  - `previewShownWhenAvailable`：`previewAvailable` 为 true → iframe 的 src 为 `/preview/<id>/`，`sandbox` 属性为 `allow-scripts allow-pointer-lock`
  - `closesStreamOnUnmount`
- [ ] **Step 4:** `npm test` → FAIL
- [ ] **Step 5:** 实现
- [ ] **Step 6:** `npm test` → PASS；`npm run build` → 成功（含类型检查）
- [ ] **Step 7: Commit** `feat(web): project page with live run feed, preview and follow-up edits`

---

### Task 6: 集成与真实验收

**Files:**
- Modify: `docs/runbook.md`（新增"本地平台"一节：开发模式 `npm run dev`、构建后由后端托管、验收记录和截图说明）
- Create: `docs/screenshots/p4-*.png`（验收截图）

- [ ] **Step 1:** `cd web && npm test && npm run build` → 全部通过；`cd backend && ./mvnw -q test` → 全部通过
- [ ] **Step 2:** 用 `real` profile 启动后端，浏览器打开 `http://localhost:8090/`（后端托管的构建产物）
- [ ] **Step 3:** 在首页输入一个**键盘操作**的需求（试玩目前只测键盘，鼠标操作类游戏留到以后）："一个贪吃蛇小游戏，方向键控制，吃到食物变长，撞墙或撞到自己结束，空格重新开始"；确认实时过程逐步出现、时间线状态正确、试玩通过后右侧出现游戏且可玩
- [ ] **Step 4:** 继续修改："蛇的速度随长度变快"；确认生成新 Run、过程实时显示、完成后预览刷新为新版本
- [ ] **Step 5:** 取消验收：再次继续修改，在"写代码"阶段点击取消 → 时间线显示已取消
- [ ] **Step 6:** 用容器内的 Chromium 截取首页、生成中的项目页、完成后的项目页三张图，存到 `docs/screenshots/`（**请用户在自己的浏览器里也确认一遍**）
- [ ] **Step 7:** 停服后确认没有残留容器；更新 runbook
- [ ] **Step 8: Commit** `docs: P4 runbook, screenshots and acceptance record`

---

## P4 完成后

- 前后端测试全部通过 + 真实验收（含用户在浏览器里确认）= P4 完成
- 下一步：P5 作品展（导出静态站、回放播放器、GitHub Pages 部署）——完成即达到 v1 标准
