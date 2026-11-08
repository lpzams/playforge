# PlayForge 方案设计

> 需求与决策来源：[docs/decisions.md](../../decisions.md)（下文"决策 N"指该文档中的编号）
> 本文定架构、模块边界、数据模型、协议和分阶段路线。标注 **【设计方选择】** 的是需求讨论里没定、由设计方拍板的，可以改。

## 1. 整体架构

```
┌──────────────────────── 宿主机 (Windows) ─────────────────────────┐
│                                                                    │
│  web/ (Vue3, 本地生成平台)  ──REST/SSE──▶  backend/ (Spring Boot)  │
│                                              │                     │
│                     ┌────────────────────────┼──────────────────┐  │
│                     │ 编排层 orchestration   │                  │  │
│                     │  RunPlanner(纯函数) → StepExecutor        │  │
│                     │  事件 event(落库+SSE)  工作区 workspace(git)│  │
│                     └───────┬──────────────────────┬────────────┘  │
│                             │ docker CLI           │               │
│  workspaces/{projectId}/ ◀──┤ tar 同步进/出        │               │
│  (每个项目一个 git 仓库)     ▼                      ▼               │
│                 ┌─────── 预热容器池 sandbox ───────────┐           │
│                 │ 容器: Node + claude CLI + codex CLI   │           │
│                 │       + Playwright + 游戏模板依赖     │           │
│                 └──────────────────────────────────────┘           │
│                                                                    │
│  showcase/ (Vue3 静态站) ◀── 一键导出 ── backend                    │
└───────────────────────────────┬────────────────────────────────────┘
                                │ git push → GitHub Actions
                                ▼
                         GitHub Pages 作品展
```

## 2. 仓库结构（monorepo）【设计方选择】

| 目录 | 职责 |
|---|---|
| `backend/` | Spring Boot 后端：编排、沙箱调度、事件、导出 |
| `sandbox/` | 沙箱镜像 Dockerfile、游戏模板工程（Vite + Phaser + 探针 SDK）、试玩脚本 |
| `web/` | 本地生成平台前端 |
| `showcase/` | 作品展静态站 + 回放播放器 |
| `docs/` | 决策、设计、计划 |
| `workspaces/` | 运行时生成的游戏项目（不入库，.gitignore） |

项目名 **PlayForge**、Java 包名 `dev.playforge` 是【设计方选择】，可改。

## 3. 技术选型

| 项 | 选择 | 说明 |
|---|---|---|
| JDK | 21 | 用虚拟线程跑任务 |
| 框架 | Spring Boot 3.5.6 | |
| 构建 | Maven 3.9+ | |
| ORM | MyBatis-Plus 3.5.12 | 国内 Java 面试常见 【设计方选择】 |
| 数据库 | H2 文件模式 + Flyway | 单用户本地，零运维；Flyway 管迁移，以后换 MySQL/PG 只改配置 【设计方选择】 |
| git 操作 | JGit 7.1 | 纯 Java，跨平台，不依赖宿主机 git 版本 【设计方选择】 |
| Docker 操作 | 调 `docker` 命令行（ProcessBuilder），封装在 `DockerOps` 接口后 | 比 docker-java 在 Windows named pipe 上更省心，可 mock 【设计方选择】 |
| 实时推送 | SSE（`SseEmitter`），支持 `Last-Event-ID` 断线续传 | |
| 前端 | Vue 3 + Vite + Element Plus | |
| 测试 | JUnit 5 + AssertJ + Awaitility + MockMvc | |

## 4. 核心概念与数据模型

- **Project（项目）**：一个游戏。对应 `workspaces/{projectId}/` 一个 git 仓库。
- **Run（运行）**：对项目的一次生成或修改请求（第一次生成、后续每轮修改都是一个 Run）。
- **Step（步骤）**：Run 内的一步：`CODE` / `REVIEW` / `FIX` / `PLAYTEST` / `COMMIT`。
- **RunEvent（事件）**：Run 内按序号递增、只追加的事件流，同时是 SSE 的推送内容和作品展回放的数据源（决策 9）。

```sql
projects  (id VARCHAR PK, title, prompt, created_at)
runs      (id BIGINT PK, project_id, instruction, status, error,
           base_commit, result_commit, created_at, started_at, finished_at)
steps     (id BIGINT PK, run_id, type, round, trigger, status, ok,
           output_json, error, started_at, finished_at)
run_events(id BIGINT PK, run_id, seq, ts, type, source, payload_json,
           UNIQUE(run_id, seq))
```

## 5. 编排设计（决策 3、4、5）

### 5.1 RunPlanner：纯函数决定下一步

`nextStep(已完成的步骤历史, 配置) → 下一步 | 结束(成功/失败)`。不读数据库、不调外部，只看历史记录。这样做的好处：

- **断点续跑很简单**：重启后读出步骤历史，再问 Planner 下一步是什么即可（决策 4 的"可恢复状态机"）
- 规则可以穷举单测

规则：

| 最后一个步骤 | 下一步 |
|---|---|
| 无 | `CODE`（round 0） |
| `CODE` / `FIX(trigger=REVIEW)` 完成 | `REVIEW` |
| `REVIEW` 完成，无阻塞问题 | `PLAYTEST` |
| `REVIEW` 完成，有阻塞问题，已审查次数 < `maxReviewRounds`(3) | `FIX(trigger=REVIEW)` |
| `REVIEW` 完成，有阻塞问题，已审查 3 次 | `PLAYTEST`（未解决问题记入事件，决策 5"以试玩为准"） |
| `PLAYTEST` 通过 | `COMMIT` |
| `PLAYTEST` 失败，试玩修复次数 < `maxPlaytestFixes`(2) | `FIX(trigger=PLAYTEST)` |
| `FIX(trigger=PLAYTEST)` 完成 | `PLAYTEST`（不再走审查，避免和审查上限互相拉扯） |
| `PLAYTEST` 失败，修复次数用完 | 结束：FAILED |
| `COMMIT` 完成 | 结束：SUCCEEDED |
| 某步骤状态为 RUNNING（进程崩溃遗留） | 重新执行该步骤 |
| 某步骤状态为 FAILED | 结束：FAILED |

`maxPlaytestFixes = 2` 是【设计方选择】。

### 5.2 审查结果格式（Codex 输出）

```json
{ "issues": [ { "severity": "BLOCKER|SUGGESTION", "file": "src/player.js", "line": 42, "message": "..." } ] }
```

解析要宽容：允许被 ```json 代码块包裹，或者前后夹杂说明文字。**解析不出来时当作"无阻塞问题"继续，同时记一条事件**。理由是审查只是辅助，最终以试玩为准；不能因为输出格式问题卡死整个流程。【设计方选择】

### 5.3 护栏

- 审查最多 3 次，试玩修复最多 2 次
- 单步超时（默认 10 分钟）、整个 Run 超时（默认 30 分钟）【设计方选择】
- CLI 限速 / 临时错误：引擎抛出可重试异常 → 指数退避重试（默认最多 3 次，30s / 60s / 120s）
- 取消：`CancellationToken` 贯穿各步骤；取消时终止 CLI 进程，Run 置为 CANCELLED

### 5.4 引擎抽象（决策 4.3）

```java
interface AgentEngine {
  String name();
  AgentResult run(AgentRequest req, Sandbox sandbox, AgentEventSink sink, CancellationToken cancel)
      throws EngineException;
}
```

实现：`ClaudeCliEngine`（CODER）、`CodexCliEngine`（REVIEWER）、演示用的假引擎。通过配置 `playforge.engines.coder/reviewer` 选用哪个实现。

### 5.5 CLI 调用方式（第二阶段实现）

- Claude：`claude -p --output-format stream-json --verbose --dangerously-skip-permissions`，提示词从 **stdin** 传入（不拼进命令行，避免中文、引号、换行被转义）。在容器里放开权限是安全的，因为容器本身就是隔离边界。
- Codex：`codex exec --json --sandbox read-only`，同样用 stdin，取最后一条 agent 消息解析成审查 JSON。
- 两者的 JSONL 输出都翻译成统一的 `RunEvent`（AGENT_TEXT / TOOL_CALL / TOOL_RESULT / USAGE），供 SSE 和回放使用（决策 4.2）。
- 凭证：Claude 用 `claude setup-token` 生成长期 token，以环境变量 `CLAUDE_CODE_OAUTH_TOKEN` 注入容器；Codex 把宿主机 `~/.codex/auth.json` 只读挂载到 `/mnt/creds`，容器启动时复制到 `~/.codex/`。【设计方选择，第二阶段先验证可行性】

## 6. 沙箱设计（决策 6）

- **镜像** `playforge-sandbox`：Node 22、claude CLI、codex CLI、Playwright + Chromium、`/template`（Vite + Phaser + 探针 SDK，依赖已装好）
- **预热池**：启动时拉起 N 个（默认 2）空闲容器；任务 `acquire()` 领一个，用完**销毁**，后台异步补充；定期健康检查，坏的销毁重建
- **代码同步（不挂载宿主机目录）**：
  - `syncIn`：宿主机工作区打成 tar（排除 `node_modules`、`dist`、`.git`）→ `docker exec -i ... tar -x` 解到 `/workspace`，再把 `/template/node_modules` 软链过来
  - `syncOut`：容器内 tar（同样排除）→ 解回宿主机工作区
  - 好处：容器看不到其他项目，也看不到宿主机文件系统；一个池化容器可以服务任意项目
- **资源限制**：`--cpus 2 --memory 4g --pids-limit 512`
- **网络**：完全开放（决策 6，已知取舍）
- 第一阶段用 `LocalSandbox`（直接用宿主机目录、同步为空操作）跑通编排，第二阶段换成 Docker 实现

## 7. 试玩设计（决策 7）

### 7.1 探针协议 v1 `window.__GAME_PROBE__`

```ts
interface GameProbe {
  version: 1;
  getState(): {
    status: 'loading' | 'ready' | 'playing' | 'paused' | 'gameover';
    score: number;
    entities: { id: string; type: string; x: number; y: number }[];
    [k: string]: unknown;          // 游戏自定义字段
  };
  actions: { name: string; keys: string[] }[];   // 例：{name:'left', keys:['ArrowLeft']}
  events: { t: number; type: string; data?: unknown }[]; // 游戏内事件日志（得分、死亡...）
  start(): void;      // 从 ready 进入 playing（等价于点"开始"）
  restart(): void;
}
```

模板工程自带 `probe-sdk`，提示词要求 Claude 必须接入。

### 7.2 试玩流程

1. **硬性检查（脚本，不消耗模型额度，决定是否通过）**：
   - 页面加载，10 秒内 `__GAME_PROBE__` 出现且状态为 `ready`/`playing`
   - 运行期间控制台没有 error、页面没有未捕获异常
   - canvas 不是纯色（截图像素方差 > 阈值）
   - 依次按每个 action 的按键，状态（实体坐标 / 分数 / 状态）有变化
   - `restart()` 后状态回到 `ready`/`playing`
2. **AI 自由玩（第五阶段之后）**：Claude 写 Playwright 玩法脚本去玩并断言，结果计入硬性检查
3. **好玩度评分（第五阶段之后，只报告）**：截图交给视觉模型，按 契合度 / 难度 / 视觉 / 反馈感 四个维度打 1–5 分，附建议

试玩脚本是 `sandbox/playtest/run.mjs`，在容器里执行，向 stdout 输出 `PlaytestReport` JSON。

## 8. API（本地平台）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/projects` `{prompt}` | 新建项目并启动首次 Run → `{projectId, runId}` |
| POST | `/api/projects/{id}/runs` `{instruction, annotations?}` | 多轮修改 |
| GET | `/api/projects` / `/api/projects/{id}` | 项目列表 / 详情（含 Run 历史） |
| GET | `/api/runs/{runId}` | Run + 步骤 |
| POST | `/api/runs/{runId}/cancel` | 取消 |
| GET | `/api/runs/{runId}/events` | SSE，支持 `Last-Event-ID` |
| GET | `/preview/{projectId}/**` | 预览最新 build |
| POST | `/api/projects/{id}/publish` | 导出到作品展 |

## 9. 作品展（决策 9）

- `showcase/public/games/index.json`：游戏列表
- `showcase/public/games/{slug}/play/`：游戏 build 产物
- `showcase/public/games/{slug}/replay.json`：所有 Run 的事件 + 每轮 diff + 截图路径
- 回放播放器：按事件时间戳加速自动播放（可暂停 / 拖动 / 调倍速）
- 发布：后端把以上文件写入 `showcase/` → 在 `showcase` 目录执行 git commit + push → GitHub Actions 构建并部署 Pages

## 10. 分阶段路线图

每个阶段单独出一份实施计划，做完都能独立运行和测试。

| 阶段 | 内容 | 做完能看到什么 |
|---|---|---|
| **P1 编排核心** | 后端骨架、数据模型、事件流 + SSE、引擎抽象、RunPlanner、审查循环、护栏、断点续跑、REST API（用假引擎 + 本地沙箱） | curl 创建项目 → SSE 实时看到 写→审→修→试玩→提交 全过程；杀进程重启后能续跑 |
| **P2 真实引擎 + 沙箱** | 沙箱镜像、Docker 预热池、tar 同步、ClaudeCliEngine、CodexCliEngine、stream-json 解析 | 一句话真的生成一个 Vite 游戏工程，Codex 真的审查 |
| **P3 试玩 v1** | 游戏模板 + 探针 SDK、`run.mjs` 硬性检查、接入编排、构建 dist、预览接口 | 坏游戏被试玩打回并自动修好 |
| **P4 本地平台前端** | 新建、实时过程视图、游戏预览、项目 / Run 历史、多轮修改（先做文字） | 不用 curl 了，浏览器里完成全流程 |
| **P5 作品展** | 导出、showcase 站点、回放播放器、Actions 部署 | **v1 完成标准：作品展上 3 个能玩、能看回放的游戏** |
| P6 增强 | AI 自由玩、视觉评分、画面圈选修改 | 面试加分项 |
| P7 加固 | 白名单网络代理、凭证轮换等 | 安全改进 |

## 11. 已知风险

- Claude 订阅 token / Codex auth 在容器里能否稳定使用 → P2 第一件事先做验证
- CLI 输出格式随版本变化 → 解析器单独成类并配合测试夹具（录一段真实输出存到 `src/test/resources`）
- Windows 下 Docker 文件同步性能 → 已经用 tar 流代替绑定挂载
