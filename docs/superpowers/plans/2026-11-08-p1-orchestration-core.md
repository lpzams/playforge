# P1 编排核心 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搭好 PlayForge 后端的编排核心：用假引擎和本地沙箱，跑通"写 → 审 → 修 → 试玩 → 提交"的可恢复状态机，并通过 REST + SSE 对外暴露。

**Architecture:** 纯函数 `RunPlanner` 根据步骤历史决定下一步；`RunOrchestrator` 负责执行步骤、落库、发事件；`RunExecutor` 用虚拟线程调度 Run，启动时恢复未完成的 Run。引擎、沙箱、试玩都放在接口后面，P1 用假实现，P2/P3 再替换成真实实现。

**Tech Stack:** Java 21, Spring Boot 3.5.6, Maven, MyBatis-Plus 3.5.12（`mybatis-plus-spring-boot3-starter`）, H2（文件模式）, Flyway, JGit 7.1.0.202411261347-r, JUnit 5, AssertJ, Awaitility

**Spec:** [docs/superpowers/specs/2026-11-08-playforge-design.md](../specs/2026-11-08-playforge-design.md)（需求见 [docs/decisions.md](../../decisions.md)）

## Global Constraints

- 所有后端代码在 `backend/`，包根 `dev.playforge`
- Java 21；Spring Boot 3.5.6；源码和资源统一 UTF-8（`project.build.sourceEncoding=UTF-8`）
- 宿主机是 Windows：路径一律用 `java.nio.file.Path`，不手拼 `/` 或 `\`
- 编排默认值：`maxReviewRounds=3`、`maxPlaytestFixes=2`、`stepTimeout=10m`、`runTimeout=30m`、`maxEngineRetries=3`、`retryBaseDelay=30s`
- 审查 JSON：`{"issues":[{"severity":"BLOCKER|SUGGESTION","file":"...","line":42,"message":"..."}]}`；解析失败 = 无阻塞问题 + 发一条 `REVIEW_UNPARSEABLE` 事件
- 事件只追加，`(run_id, seq)` 唯一，`seq` 在每个 Run 内从 1 开始连续递增
- 提示词作为普通字符串原样传给引擎，不做任何 shell 转义（P2 走 stdin）
- `workspaces/`、`backend/data/`、`backend/target/` 加入 `.gitignore`

## Review Focus

1. **审查输出格式不规范**（被 ```json 包裹、前后夹杂说明、完全不是 JSON）→ 不能崩，按 Global Constraints 处理 —— Task 4 测试覆盖
2. **SSE 断线重连带 `Last-Event-ID`** → 补发之后的事件，不重复、不丢失 —— Task 3 测试覆盖
3. **步骤执行中途取消** → 引擎收到取消信号，Run 变为 CANCELLED，不再执行后续步骤 —— Task 6 测试覆盖
4. **进程在某步骤中途崩溃后重启** → 已完成的步骤不重做，中断的步骤重新执行，不产生重复提交 —— Task 7 测试覆盖
5. **提示词包含中文、引号、换行、反斜杠** → 原样落库、原样到达引擎 —— Task 8 测试覆盖

---

## 前置：环境准备（不是代码任务）

本机现状（2026-11-08 检查）：默认 `java` 是 JDK 11，全局 Maven 是 3.6.1，两者都低于 Spring Boot 3.5 的要求（Java 17+、Maven 3.6.3+）。处理方式：

- [ ] **JDK**：构建时设置 `JAVA_HOME=C:\Users\泽泽\.jdks\openjdk-22.0.1`（pom 里 `<java.version>21</java.version>`，用 JDK 22 编译出 21 字节码没有问题）。也可以另装 JDK 21 LTS 再指过去
- [ ] **Maven**：不升级全局 Maven；Task 1 在 `backend/` 下生成 Maven Wrapper，固定 Maven 3.9.9，下文所有构建命令都用 `./mvnw`（PowerShell 下用 `.\mvnw.cmd`）
- [ ] 验证：在 `backend/` 执行 `./mvnw -v`，输出含 `Apache Maven 3.9.9` 和 `Java version: 22`

（Docker、Node、claude / codex CLI 都已安装，P2 才用到；P2 开始前需要启动 Docker Desktop。）

---

### Task 1: 后端骨架 + git 仓库初始化

**Files:**
- Create: `.gitignore`、`backend/pom.xml`
- Create: `backend/src/main/java/dev/playforge/PlayforgeApplication.java`
- Create: `backend/src/main/java/dev/playforge/api/HealthController.java`
- Create: `backend/src/main/java/dev/playforge/config/PlayforgeProperties.java`
- Create: `backend/src/main/resources/application.yml`、`backend/src/test/resources/application-test.yml`
- Test: `backend/src/test/java/dev/playforge/api/HealthControllerTest.java`

**Interfaces:**
- Produces:
  ```java
  @ConfigurationProperties("playforge")
  public record PlayforgeProperties(Path workspaceRoot, Orchestration orchestration, Engines engines) {
    public record Orchestration(int maxReviewRounds, int maxPlaytestFixes, Duration stepTimeout,
                                Duration runTimeout, int maxEngineRetries, Duration retryBaseDelay) {}
    public record Engines(String coder, String reviewer) {}   // P1 默认 "demo"
  }
  ```
  `application.yml` 给出 Global Constraints 中的全部默认值，`workspaceRoot: ../workspaces`，H2 URL `jdbc:h2:file:./data/playforge;MODE=MySQL`。
  `application-test.yml`：H2 改成内存库 `jdbc:h2:mem:pf;MODE=MySQL;DB_CLOSE_DELAY=-1`，`retryBaseDelay: 10ms`，`workspaceRoot` 由测试用 `@DynamicPropertySource` 指向临时目录。

- [ ] **Step 1:** 在仓库根执行 `git init`，写 `.gitignore`（包含 Global Constraints 里的三个目录，再加 `.idea/`、`*.iml`、`node_modules/`）
- [ ] **Step 2: 写失败测试** `HealthControllerTest.returnsOk`：`@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")`，`GET /api/health` → 200，body `{"status":"ok"}`
- [ ] **Step 3:** 写最小 pom（只有 parent 和坐标）后，在 `backend/` 执行 `mvn -N wrapper:wrapper -Dmaven=3.9.9`（用全局 3.6.1 生成 wrapper 即可）；再运行 `./mvnw -q test` → 预期编译失败（还没有类）
- [ ] **Step 4:** 补全 pom（依赖：spring-boot-starter-web、validation、mybatis-plus-spring-boot3-starter、h2、flyway-core、org.eclipse.jgit、spring-boot-starter-test、awaitility）、启动类（`@ConfigurationPropertiesScan`）、`HealthController`、配置文件
- [ ] **Step 5:** 运行 `./mvnw -q test` → PASS
- [ ] **Step 6: Commit** `git add -A && git commit -m "chore: backend skeleton with health endpoint"`

---

### Task 2: 数据模型与持久化

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__init.sql`
- Create: `backend/src/main/java/dev/playforge/run/` 下：`Project.java`、`Run.java`、`Step.java`、`RunStatus.java`、`StepType.java`、`StepTrigger.java`、`StepStatus.java`、`ProjectMapper.java`、`RunMapper.java`、`StepMapper.java`、`RunStore.java`
- Test: `backend/src/test/java/dev/playforge/run/RunStoreTest.java`

**Interfaces:**
- Produces:
  ```java
  enum RunStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
  enum StepType { CODE, REVIEW, FIX, PLAYTEST, COMMIT }
  enum StepTrigger { NONE, REVIEW, PLAYTEST }
  enum StepStatus { RUNNING, DONE, FAILED }
  // 实体字段与 spec §4 表结构一一对应；Step.ok: REVIEW=无阻塞, PLAYTEST=通过, 其他=null

  @Service class RunStore {
    Project createProject(String title, String prompt);          // id = 12 位小写字母数字随机串
    Run createRun(String projectId, String instruction);          // status=QUEUED
    Run getRun(long runId);
    List<Run> findUnfinishedRuns();                               // QUEUED 或 RUNNING，按 id 升序
    void markRunStarted(long runId, String baseCommit);           // RUNNING + started_at
    void finishRun(long runId, RunStatus status, String error, String resultCommit);
    Step startStep(long runId, StepType type, int round, StepTrigger trigger); // status=RUNNING
    void completeStep(long stepId, Boolean ok, String outputJson);           // status=DONE
    void failStep(long stepId, String error);                                 // status=FAILED
    List<Step> listSteps(long runId);                             // 按 id 升序
  }
  ```

- [ ] **Step 1: 写失败测试** `RunStoreTest`（`@SpringBootTest @ActiveProfiles("test") @Transactional`）：
  - `createRunIsQueued`：建项目 + 建 Run → `getRun().getStatus()==QUEUED`
  - `stepsListedInOrder`：依次 start CODE、complete、start REVIEW → `listSteps` 类型顺序为 `[CODE, REVIEW]`，状态为 `[DONE, RUNNING]`
  - `unfinishedRunsExcludeTerminal`：3 个 Run 分别设为 RUNNING / SUCCEEDED / QUEUED → `findUnfinishedRuns` 只返回第 1 个和第 3 个
- [ ] **Step 2:** `./mvnw -q test -Dtest=RunStoreTest` → FAIL
- [ ] **Step 3:** 写迁移脚本（含 spec §4 的 `run_events` 表与唯一约束）、实体（`@TableName`）、Mapper（继承 `BaseMapper`）、`RunStore`
- [ ] **Step 4:** `./mvnw -q test -Dtest=RunStoreTest` → PASS
- [ ] **Step 5: Commit** `feat: persistence for projects, runs, steps`

---

### Task 3: 事件流（落库 + 订阅 + SSE 断线续传）

**Files:**
- Create: `backend/src/main/java/dev/playforge/event/` 下：`RunEvent.java`、`RunEventType.java`、`RunEventMapper.java`、`RunEventPublisher.java`、`RunEventController.java`
- Test: `backend/src/test/java/dev/playforge/event/RunEventPublisherTest.java`、`RunEventControllerTest.java`

**Interfaces:**
- Produces:
  ```java
  enum RunEventType { RUN_STARTED, STEP_STARTED, AGENT_TEXT, TOOL_CALL, TOOL_RESULT, USAGE,
                      FILE_CHANGED, REVIEW_RESULT, REVIEW_UNPARSEABLE, PLAYTEST_RESULT,
                      RETRY_SCHEDULED, STEP_FINISHED, RUN_FINISHED, LOG }
  record RunEvent(long runId, long seq, Instant ts, RunEventType type, String source, Map<String,Object> payload) {}

  @Service class RunEventPublisher {
    RunEvent publish(long runId, RunEventType type, String source, Map<String,Object> payload); // 分配 seq、落库、推给订阅者
    List<RunEvent> history(long runId, long afterSeq);           // seq > afterSeq，升序
    AutoCloseable subscribe(long runId, Consumer<RunEvent> listener);
  }
  // GET /api/runs/{runId}/events：SSE，event id = seq，event name = type
  // 先发 history(Last-Event-ID 或 0)，再订阅实时事件；收到 RUN_FINISHED 后 complete
  ```
  `seq` 分配：每个 runId 一把锁，第一次使用时从库里读 `max(seq)` 初始化。

- [ ] **Step 1: 写失败测试** `RunEventPublisherTest`：
  - `seqIsContiguousPerRun`：Run A 发 3 条、Run B 发 2 条 → A 的 seq 为 `[1,2,3]`，B 为 `[1,2]`
  - `concurrentPublishNoGaps`：10 个虚拟线程各对同一 Run 发 50 条 → `history(run,0)` 共 500 条，seq 恰好为 1..500
  - `historyAfterSeq`：发 5 条，`history(run,3)` 的 seq 为 `[4,5]`
  - `subscriberReceivesLive`：先订阅，再发 2 条 → 监听器收到 2 条；close 订阅后再发 → 不再收到
- [ ] **Step 2: 写失败测试** `RunEventControllerTest`（启动随机端口，用 `java.net.http.HttpClient` 读流）：
  - `replaysFromLastEventId`：预先发 seq 1..4，最后一条为 RUN_FINISHED；请求头 `Last-Event-ID: 2` → 流里的 id 依次为 `3,4`，然后连接关闭
  - `streamsLiveThenCompletes`：先连上，再发 LOG、RUN_FINISHED → 收到 id `1,2` 后连接关闭
- [ ] **Step 3:** `./mvnw -q test -Dtest='RunEvent*'` → FAIL
- [ ] **Step 4:** 实现。注意先订阅、再补发历史，用 seq 去重，避免两者之间的空窗丢事件
- [ ] **Step 5:** `./mvnw -q test -Dtest='RunEvent*'` → PASS
- [ ] **Step 6: Commit** `feat: run event stream with SSE resume`

---

### Task 4: 引擎 / 沙箱 / 试玩接口 + 审查解析 + 演示实现

**Files:**
- Create: `backend/src/main/java/dev/playforge/common/CancellationToken.java`
- Create: `backend/src/main/java/dev/playforge/engine/` 下：`AgentEngine.java`、`AgentRequest.java`、`AgentRole.java`、`AgentResult.java`、`AgentEventSink.java`、`EngineException.java`、`EngineRegistry.java`、`demo/DemoCoderEngine.java`、`demo/DemoReviewerEngine.java`
- Create: `backend/src/main/java/dev/playforge/sandbox/` 下：`Sandbox.java`、`SandboxProvider.java`、`local/LocalSandboxProvider.java`
- Create: `backend/src/main/java/dev/playforge/review/` 下：`Severity.java`、`ReviewIssue.java`、`ReviewReport.java`、`ReviewParser.java`
- Create: `backend/src/main/java/dev/playforge/playtest/` 下：`Playtester.java`、`PlaytestReport.java`、`AlwaysPassPlaytester.java`
- Test: `backend/src/test/java/dev/playforge/review/ReviewParserTest.java`、`backend/src/test/java/dev/playforge/common/CancellationTokenTest.java`

**Interfaces:**
- Produces:
  ```java
  final class CancellationToken { void cancel(); boolean isCancelled(); void onCancel(Runnable r); // 已取消时立即执行 r
                                  void throwIfCancelled() throws CancelledException; }
  class CancelledException extends RuntimeException {}

  enum AgentRole { CODER, REVIEWER }
  record AgentRequest(AgentRole role, String prompt, Duration timeout) {}
  record AgentResult(String finalText, long inputTokens, long outputTokens, Duration elapsed) {}
  interface AgentEventSink { void emit(RunEventType type, Map<String,Object> payload); }
  class EngineException extends Exception { EngineException(String msg, boolean retryable, Duration retryAfter /*nullable*/);
                                            boolean retryable(); Optional<Duration> retryAfter(); }
  interface AgentEngine { String name();
    AgentResult run(AgentRequest req, Sandbox sandbox, AgentEventSink sink, CancellationToken cancel) throws EngineException; }
  @Component class EngineRegistry { AgentEngine coder(); AgentEngine reviewer(); } // 按 PlayforgeProperties.engines 的名字从所有 AgentEngine bean 中选

  interface Sandbox extends AutoCloseable { Path workdir(); void syncIn(Path hostDir); void syncOut(Path hostDir); @Override void close(); }
  interface SandboxProvider { Sandbox acquire(String projectId); }
  // LocalSandboxProvider: workdir = workspaceRoot/projectId，syncIn/syncOut 为空操作

  enum Severity { BLOCKER, SUGGESTION }
  record ReviewIssue(Severity severity, String file, Integer line, String message) {}
  record ReviewReport(boolean parsed, List<ReviewIssue> issues) { boolean hasBlockers(); }
  final class ReviewParser { static ReviewReport parse(String text); }

  record PlaytestReport(boolean passed, List<String> failures, Map<String,Object> details) {}
  interface Playtester { PlaytestReport run(Sandbox sandbox, CancellationToken cancel); }
  // AlwaysPassPlaytester (@Component, P3 替换): passed=true, failures=[]
  ```
  `DemoCoderEngine`（name `demo`, CODER）：在 `sandbox.workdir()` 写 `index.html`（内容包含提示词首行），发一条 `FILE_CHANGED` 事件。`DemoReviewerEngine`（name `demo`, REVIEWER）：返回 `{"issues":[]}`。EngineRegistry 按 (名字, 角色) 二元组来选引擎。

- [ ] **Step 1: 写失败测试** `ReviewParserTest`：
  - `parsesPlainJson`：`{"issues":[{"severity":"BLOCKER","file":"a.js","line":3,"message":"x"}]}` → parsed=true，1 条，hasBlockers=true
  - `parsesFencedJsonWithProse`：`"看完了：\n```json\n{\"issues\":[{\"severity\":\"SUGGESTION\",\"file\":\"b.js\",\"message\":\"y\"}]}\n```\n以上"` → parsed=true，hasBlockers=false，line=null
  - `severityCaseInsensitive`：`"severity":"blocker"` → BLOCKER
  - `garbageIsUnparsed`：`"LGTM!"` → parsed=false，issues 为空，hasBlockers=false
  - `unknownSeverityTreatedAsSuggestion`：`"severity":"MAJOR"` → SUGGESTION
- [ ] **Step 2: 写失败测试** `CancellationTokenTest`：
  - `onCancelRunsOnce`：注册回调 → cancel 两次 → 回调只执行 1 次
  - `onCancelAfterCancelledRunsImmediately`
  - `throwIfCancelled`：cancel 后调用抛出 `CancelledException`
- [ ] **Step 3:** `./mvnw -q test -Dtest='ReviewParserTest,CancellationTokenTest'` → FAIL
- [ ] **Step 4:** 实现以上全部类型。解析策略：先试整段文本，再找第一个 `{` 到最后一个 `}` 的子串；都失败就返回 `parsed=false`
- [ ] **Step 5:** 运行同上 → PASS
- [ ] **Step 6: Commit** `feat: engine/sandbox/playtest abstractions and review parser`

---

### Task 5: RunPlanner（纯函数状态机）

**Files:**
- Create: `backend/src/main/java/dev/playforge/orchestration/RunPlanner.java`、`PlanDecision.java`
- Test: `backend/src/test/java/dev/playforge/orchestration/RunPlannerTest.java`

**Interfaces:**
- Consumes: `Step`、`StepType`、`StepTrigger`、`StepStatus`（Task 2）
- Produces:
  ```java
  sealed interface PlanDecision {
    record Execute(StepType type, int round, StepTrigger trigger, boolean resume) implements PlanDecision {} // resume=true 表示重跑中断的步骤
    record Finish(RunStatus status, String reason) implements PlanDecision {}
  }
  final class RunPlanner { RunPlanner(int maxReviewRounds, int maxPlaytestFixes);
                           PlanDecision next(List<Step> history); }
  ```
  规则严格按 spec §5.1 表格。`round`：CODE=0；每个 FIX 的 round = 已有 FIX 数 + 1；REVIEW / PLAYTEST / COMMIT 沿用最近一个 CODE/FIX 的 round。

- [ ] **Step 1: 写失败测试** `RunPlannerTest`（`new RunPlanner(3, 2)`，用一个小工具方法 `step(type, trigger, status, ok)` 构造历史）：
  - `emptyStartsWithCode` → `Execute(CODE,0,NONE,false)`
  - `codeDoneThenReview` → `Execute(REVIEW,0,NONE,false)`
  - `cleanReviewThenPlaytest`
  - `blockingReviewThenFix` → `Execute(FIX,1,REVIEW,false)`
  - `reviewFixDoneThenReviewAgain`：CODE, REVIEW(✗), FIX(REVIEW) → `Execute(REVIEW,1,…)`
  - `thirdBlockingReviewGoesToPlaytest`：CODE, R✗, FIX, R✗, FIX, R✗ → `Execute(PLAYTEST,2,…)`
  - `playtestPassThenCommit`
  - `playtestFailThenFix` → `Execute(FIX, n, PLAYTEST, false)`
  - `playtestFixThenPlaytestSkippingReview`
  - `playtestFixesExhaustedFails`：PLAYTEST✗, FIX(P), PLAYTEST✗, FIX(P), PLAYTEST✗ → `Finish(FAILED, …)`，reason 包含 "playtest"
  - `commitDoneSucceeds` → `Finish(SUCCEEDED, …)`
  - `runningStepIsResumed`：CODE(DONE), REVIEW(RUNNING) → `Execute(REVIEW,0,NONE,true)`
  - `failedStepFailsRun` → `Finish(FAILED, …)`
- [ ] **Step 2:** `./mvnw -q test -Dtest=RunPlannerTest` → FAIL
- [ ] **Step 3:** 实现 `RunPlanner`
- [ ] **Step 4:** 运行同上 → PASS
- [ ] **Step 5: Commit** `feat: pure run planner state machine`

---

### Task 6: RunOrchestrator（执行步骤、重试、超时、取消、git 提交）

**Files:**
- Create: `backend/src/main/java/dev/playforge/workspace/GitWorkspace.java`
- Create: `backend/src/main/java/dev/playforge/orchestration/` 下：`PromptTemplates.java`、`RunOrchestrator.java`
- Test: `backend/src/test/java/dev/playforge/workspace/GitWorkspaceTest.java`、`backend/src/test/java/dev/playforge/orchestration/RunOrchestratorTest.java`、`backend/src/test/java/dev/playforge/orchestration/ScriptedEngine.java`（测试工具）

**Interfaces:**
- Consumes: Task 2 的 `RunStore`，Task 3 的 `RunEventPublisher`，Task 4 的全部接口，Task 5 的 `RunPlanner`
- Produces:
  ```java
  final class GitWorkspace {
    static GitWorkspace openOrInit(Path dir);
    Optional<String> head();
    String diffWorkingTree(String baseCommit /*nullable = 与空树比较*/); // 包含未跟踪文件
    String commitAll(String message);   // 返回 commit hash；没有改动时返回当前 head，不新建提交
  }
  final class PromptTemplates {
    static String code(String instruction);
    static String fixFromReview(String instruction, List<ReviewIssue> blockers);
    static String fixFromPlaytest(String instruction, List<String> failures);
    static String review(String instruction, String diff);   // 必须包含审查 JSON 格式说明
  }
  @Service class RunOrchestrator {
    void execute(long runId, CancellationToken cancel);  // 阻塞执行直到 Run 进入终态
  }
  ```
  `execute` 流程：
  1. `markRunStarted`（`baseCommit` = 当前 head，续跑时保留原值）→ 发 `RUN_STARTED`
  2. `sandboxProvider.acquire(projectId)` → `syncIn(hostDir)`
  3. 循环：`planner.next(listSteps)` → 若为 `Finish` 则 `finishRun` + 发 `RUN_FINISHED{status, reason}` 并退出；若为 `Execute` 且 resume=true，**复用**那条状态为 RUNNING 的步骤记录（沿用它的 id，不新建步骤）直接重新执行
  4. 执行步骤（每步前后发 `STEP_STARTED` / `STEP_FINISHED`）：
     - CODE / FIX：coder 引擎 → `syncOut`
     - REVIEW：`diffWorkingTree(baseCommit)` → reviewer 引擎 → `ReviewParser.parse` → `completeStep(ok=!hasBlockers)`，发 `REVIEW_RESULT`；`parsed=false` 时额外发 `REVIEW_UNPARSEABLE`
     - PLAYTEST：`playtester.run` → `ok=passed`，发 `PLAYTEST_RESULT`
     - COMMIT：`commitAll("run {runId}: {instruction 首行，截到 72 字符}")` → 记录为 `result_commit`
  5. FIX 的提示词：trigger=REVIEW 时取上一个 REVIEW 步骤 `output_json` 里的阻塞问题；trigger=PLAYTEST 时取上一个 PLAYTEST 的 failures
  6. 引擎抛出 retryable 异常：发 `RETRY_SCHEDULED{attempt, delayMs}`，等 `retryAfter` 或 `retryBaseDelay * 2^(attempt-1)`，最多 `maxEngineRetries` 次；仍失败 → `failStep` → 规划器返回 FAILED
  7. 每步开始前检查 `runTimeout`（从 `started_at` 算起），超时 → `finishRun(FAILED, "run timeout")`
  8. `CancelledException`，或某步完成后 `cancel.isCancelled()` → `finishRun(CANCELLED)`，发 `RUN_FINISHED`
  9. 无论结果如何都要 `sandbox.close()`
  引擎调用时传入 `AgentRequest.timeout = stepTimeout`，`AgentEventSink` 转发到 `publisher.publish(runId, type, engine.name(), payload)`。

- [ ] **Step 1: 写失败测试** `GitWorkspaceTest`（`@TempDir`）：
  - `initAndCommit`：新目录写 `a.txt` → `commitAll` 返回 40 位 hash，`head()` 等于它
  - `diffIncludesUntracked`：提交后新增 `b.txt` → `diffWorkingTree(head)` 包含 `b.txt`
  - `commitWithoutChangesReturnsHead`：连续两次 `commitAll` → 返回同一个 hash
- [ ] **Step 2: 写测试工具** `ScriptedEngine implements AgentEngine`：构造参数为 name 和一个 `Queue<ThrowingFunction<AgentRequest, AgentResult>>`；每次调用取出一个行为执行，并把收到的请求记录到 `List<AgentRequest> calls`
- [ ] **Step 3: 写失败测试** `RunOrchestratorTest`（`@SpringBootTest @ActiveProfiles("test")`，用 `@TestConfiguration` 把 coder/reviewer 替换成 ScriptedEngine、playtester 替换成可编排的假实现）：
  - `happyPath`：coder 写文件、reviewer 返回无问题、试玩通过 → Run 为 SUCCEEDED，步骤类型为 `[CODE, REVIEW, PLAYTEST, COMMIT]`，`result_commit` 非空且等于 git head，事件以 RUN_STARTED 开头、RUN_FINISHED 结尾
  - `reviewBlockerThenFixed`：第 1 次审查有 BLOCKER（message "missing restart"）、第 2 次无问题 → 步骤为 `[CODE, REVIEW, FIX, REVIEW, PLAYTEST, COMMIT]`；FIX 请求的 prompt 包含 "missing restart"
  - `reviewNeverClearsStillCommitsIfPlaytestPasses`：审查 3 次都有 BLOCKER → SUCCEEDED，REVIEW 步骤恰好 3 个
  - `unparseableReviewProceeds`：reviewer 返回 "LGTM" → 发出 `REVIEW_UNPARSEABLE` 事件，Run 为 SUCCEEDED
  - `retryableErrorRetriesThenSucceeds`：coder 第 1 次抛 `EngineException(retryable=true)`、第 2 次成功 → SUCCEEDED，有 1 条 RETRY_SCHEDULED 事件
  - `nonRetryableErrorFailsRun`：coder 抛 `EngineException(retryable=false)` → FAILED，error 包含异常消息
  - `cancelDuringStep`：coder 行为里阻塞等待 token 取消，测试在另一个线程中调用 `cancel()` → Run 为 CANCELLED，reviewer 从未被调用
  - `playtestFailuresReachFixPrompt`：试玩第 1 次失败（failures ["no canvas"]），第 2 次通过 → FIX(trigger=PLAYTEST) 的 prompt 包含 "no canvas"
- [ ] **Step 4:** `./mvnw -q test -Dtest='GitWorkspaceTest,RunOrchestratorTest'` → FAIL
- [ ] **Step 5:** 实现 `GitWorkspace`、`PromptTemplates`、`RunOrchestrator`。PromptTemplates 的文字内容由实现者决定，但 `code`/`fix*` 必须要求"使用 Vite 多文件工程，并接入 `window.__GAME_PROBE__`（见 spec §7.1）"，`review` 必须包含审查 JSON 格式和 BLOCKER/SUGGESTION 的含义
- [ ] **Step 6:** 运行同上 → PASS
- [ ] **Step 7: Commit** `feat: run orchestrator with review loop, retry, timeout, cancel`

---

### Task 7: RunExecutor（虚拟线程调度 + 启动恢复）

**Files:**
- Create: `backend/src/main/java/dev/playforge/orchestration/RunExecutor.java`、`RunRecovery.java`
- Test: `backend/src/test/java/dev/playforge/orchestration/RunRecoveryTest.java`、`RunExecutorTest.java`

**Interfaces:**
- Consumes: `RunOrchestrator.execute`（Task 6），`RunStore.findUnfinishedRuns`（Task 2）
- Produces:
  ```java
  @Service class RunExecutor {
    void submit(long runId);            // 同一 runId 已在运行则忽略
    boolean cancel(long runId);         // 正在运行：触发 token 并返回 true；QUEUED 未开始：直接 finishRun(CANCELLED)，返回 true；其余 false
    boolean isActive(long runId);
  }
  @Component class RunRecovery { @EventListener(ApplicationReadyEvent.class) void recover(); } // 对每个未完成的 Run 调用 submit
  ```
  `playforge.recovery.enabled`（默认 true；在其他测试的 test 配置里设为 false，避免互相干扰）。

- [ ] **Step 1: 写失败测试** `RunRecoveryTest`：手工构造"崩溃现场"：Run 状态为 RUNNING，步骤为 CODE(DONE)、REVIEW(RUNNING)，工作区里有 CODE 写出的文件 → 调用 `recover()` → Awaitility 等到 SUCCEEDED；coder 引擎**没有**被再次调用；REVIEW 步骤总数为 1（复用了中断的那一条）；git 只有 1 个提交
- [ ] **Step 2: 写失败测试** `RunExecutorTest`：
  - `duplicateSubmitIgnored`：连续 submit 两次 → orchestrator 只被执行 1 次
  - `cancelQueuedRun`：Run 为 QUEUED 且未 submit → `cancel` 返回 true，状态为 CANCELLED
  - `cancelUnknownRunReturnsFalse`
- [ ] **Step 3:** `./mvnw -q test -Dtest='RunRecoveryTest,RunExecutorTest'` → FAIL
- [ ] **Step 4:** 实现：`Executors.newVirtualThreadPerTaskExecutor()`、`ConcurrentHashMap<Long, CancellationToken>`，任务结束后移除；`@PreDestroy` 时关闭 executor
- [ ] **Step 5:** 运行同上 → PASS
- [ ] **Step 6: Commit** `feat: virtual-thread run executor with startup recovery`

---

### Task 8: REST API

**Files:**
- Create: `backend/src/main/java/dev/playforge/api/` 下：`ProjectController.java`、`RunController.java`、`ApiExceptionHandler.java`、`dto/CreateProjectRequest.java`、`dto/CreateRunRequest.java`、`dto/CreatedResponse.java`、`dto/RunView.java`、`dto/ProjectView.java`
- Test: `backend/src/test/java/dev/playforge/api/ProjectControllerTest.java`、`RunControllerTest.java`

**Interfaces:**
- Consumes: `RunStore`、`RunExecutor`
- Produces（路径按 spec §8，P1 不含 preview / publish）：
  ```java
  record CreateProjectRequest(@NotBlank @Size(max = 4000) String prompt) {}
  record CreateRunRequest(@NotBlank @Size(max = 4000) String instruction) {}
  record CreatedResponse(String projectId, long runId) {}
  record RunView(long id, String projectId, String instruction, RunStatus status, String error,
                 String baseCommit, String resultCommit, List<StepView> steps) {
    record StepView(long id, StepType type, int round, StepTrigger trigger, StepStatus status, Boolean ok) {} }
  record ProjectView(String id, String title, String prompt, List<RunView> runs) {}
  ```
  - `POST /api/projects` → 201；title = prompt 首行截到 40 字符；创建首个 Run 并 `submit`
  - `POST /api/projects/{id}/runs` → 201；该项目已有 QUEUED/RUNNING 的 Run 时返回 409
  - `GET /api/projects`、`GET /api/projects/{id}`、`GET /api/runs/{runId}`；不存在 → 404
  - `POST /api/runs/{runId}/cancel` → 202（`cancel` 返回 true）或 409（已结束）
  - 校验失败 → 400，错误响应统一为 `{"error": "..."}`

- [ ] **Step 1: 写失败测试** `ProjectControllerTest`（MockMvc，使用 demo 引擎）：
  - `createProjectRunsToSuccess`：POST → 201 → Awaitility 等 `GET /api/runs/{runId}` 的 status 为 SUCCEEDED，steps 类型为 `[CODE, REVIEW, PLAYTEST, COMMIT]`
  - `promptPreservedExactly`：prompt = `"做一个\"躲陨石\"游戏\n第二行 C:\\path 'x'"` → `GET /api/projects/{id}` 返回的 prompt 完全相同；demo coder 收到的 `AgentRequest.prompt` 包含这段原文（用 `@SpyBean` 或在 DemoCoderEngine 上记录最后一次请求来断言）
  - `blankPromptIs400`
  - `secondRunWhileActiveIs409`：用一个会阻塞的 coder 让首个 Run 停在 RUNNING → 再 POST runs → 409
- [ ] **Step 2: 写失败测试** `RunControllerTest`：
  - `unknownRunIs404`
  - `cancelFinishedRunIs409`
- [ ] **Step 3:** `./mvnw -q test -Dtest='ProjectControllerTest,RunControllerTest'` → FAIL
- [ ] **Step 4:** 实现 controller、DTO 和统一异常处理
- [ ] **Step 5:** `./mvnw -q test`（全量）→ 全部 PASS
- [ ] **Step 6: 手工验收**：`./mvnw spring-boot:run`，然后在另一个终端执行
  ```bash
  curl -s -X POST localhost:8080/api/projects -H 'Content-Type: application/json' -d '{"prompt":"躲陨石的太空飞船游戏"}'
  curl -N localhost:8080/api/runs/1/events
  ```
  预期：SSE 依次输出 RUN_STARTED → STEP_STARTED(CODE) → FILE_CHANGED → … → RUN_FINISHED(SUCCEEDED)；`workspaces/<projectId>/index.html` 存在，且该目录里有 1 个 git 提交
- [ ] **Step 7: Commit** `feat: REST API for projects and runs`

---

## P1 完成后

- 全量测试通过 + 手工验收通过 = P1 完成
- 下一步：写 P2 计划（沙箱镜像、Docker 预热池、tar 同步、Claude/Codex CLI 引擎）。P2 的第一个任务是**验证订阅凭证能在容器里使用**（spec §11 风险一）
