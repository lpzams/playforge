# P2 真实引擎 + Docker 沙箱 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 P1 的假引擎和本地目录换成真实的东西：Claude Code CLI 写代码、Codex CLI 审查，两者都跑在 Docker 预热容器池里，代码用 tar 流同步进出容器。

**Architecture:** `ProcessRunner` 统一负责起进程、流式读输出、超时和取消时结束整棵进程树；`DockerOps` 在它之上封装 docker 命令行；`ContainerPool` 管理预热容器；`DockerSandbox` 实现 P1 的 `Sandbox` 接口，并新增 `exec`。两个 CLI 引擎通过 `sandbox.exec` 在容器内运行，把各自的 JSONL 输出解析成统一的 `RunEvent`。用 `real` profile 切换到真实实现，测试默认仍用 P1 的假实现。

**Tech Stack:** P1 全部 + Apache Commons Compress 1.27.1（tar）；沙箱镜像基于 `node:22-bookworm-slim`，预装 `@anthropic-ai/claude-code@2.1.303`、`@openai/codex@0.166.0`（与本机版本一致）

**Spec:** [docs/superpowers/specs/2026-11-08-playforge-design.md](../specs/2026-11-08-playforge-design.md) §5.4、§5.5、§6（需求见 [docs/decisions.md](../../decisions.md) 决策 2、4、6）

## Global Constraints

- 所有 P1 的 Global Constraints 继续有效（包根 `dev.playforge`、UTF-8、路径用 `Path`、构建用 `backend/mvnw`）
- **凭证不得出现在**：进程命令行参数、日志、RunEvent、git 仓库。Claude token 只通过环境变量**名**传给 docker（`docker run -e CLAUDE_CODE_OAUTH_TOKEN`，值从后端进程的环境继承）
- 宿主机读取的环境变量：`PLAYFORGE_CLAUDE_TOKEN`（Claude `setup-token` 生成的长期 token）；Codex 凭证文件默认为 `%USERPROFILE%\.codex\auth.json`，可用 `playforge.sandbox.codex-auth-file` 覆盖
- 容器内以非 root 用户 `node` 运行（Claude Code 拒绝以 root 身份使用 `--dangerously-skip-permissions`），工作目录 `/workspace`
- 容器资源限制：`--cpus 2 --memory 4g --pids-limit 512`；网络完全开放（决策 6）
- 预热池默认大小 2；`acquire` 最多等待 60 秒
- tar 同步时排除 `node_modules/`、`dist/`、`.git/`
- 提示词一律通过 **stdin** 传给 CLI，不拼进命令行
- 审查 diff 排除 `package-lock.json`、`pnpm-lock.yaml`、`yarn.lock` 和二进制文件，总长度上限 60,000 字符，超出部分截断并注明 `[diff truncated]`
- 镜像名 `playforge-sandbox:p2`
- 带 `@Tag("docker")` 的测试需要本机 Docker 可用；Docker 不可用时，用 `Assumptions.assumeTrue` 跳过，不判为失败

## Review Focus

1. **容器回传的 tar 包含 `../` 或绝对路径条目** → 拒绝该条目并让同步失败，宿主机工作区以外的文件一个都不能写 —— Task 3 测试
2. **Claude token 泄露**到 `docker run` 参数、日志或事件里 → 一律不允许出现 token 值 —— Task 4 测试
3. **CLI 被取消或超时** → `docker exec` 进程和容器内的 CLI 都要结束，用过的容器要销毁而不是放回池里 —— Task 2、Task 4 测试
4. **CLI 输出了非 JSON 行、空行、超长行，或在一行中途中断** → 跳过坏行、不崩溃，仍能拿到最终结果 —— Task 5、Task 6 测试
5. **后端启动时 Docker 没开** → 服务照常启动，创建 Run 时得到明确的失败原因 "Docker is not available"，不能卡住 —— Task 4 测试

---

## 前置（用户手动操作）

- [ ] 启动 Docker Desktop，确认 `docker version` 能输出 Server 版本
- [ ] 在终端执行 `claude setup-token`，按提示在浏览器里登录，把得到的 token 设为**用户级**环境变量 `PLAYFORGE_CLAUDE_TOKEN`（之后重开终端 / IDE 才会生效）
- [ ] 确认 `%USERPROFILE%\.codex\auth.json` 存在（执行过 `codex login` 就会有）

---

### Task 1: 沙箱镜像 + 凭证可行性验证（关卡）

这是整个 P2 风险最大的一步。**如果凭证在容器里用不了，就停下来和用户商量替代方案**（例如让 CLI 跑在宿主机上，只把构建和试玩放进容器），不要继续做后面的任务。

**Files:**
- Create: `sandbox/Dockerfile`、`sandbox/entrypoint.sh`、`sandbox/README.md`
- Create: `sandbox/scripts/verify-creds.sh`（在宿主机上运行的验证脚本）
- Create: `backend/src/test/resources/fixtures/claude-stream.jsonl`、`backend/src/test/resources/fixtures/codex-exec.jsonl`、`backend/src/test/resources/fixtures/README.md`

**Interfaces:**
- Produces:
  - 镜像 `playforge-sandbox:p2`，`ENTRYPOINT ["/entrypoint.sh"]`，默认 `CMD ["sleep","infinity"]`，`USER node`，`WORKDIR /workspace`，预装 `git`、`ca-certificates`、`tar`
  - `entrypoint.sh`：若 `/mnt/creds/codex/auth.json` 存在，则复制到 `/home/node/.codex/auth.json`（权限 600），然后 `exec "$@"`
  - 两份真实输出夹具：Task 5、Task 6 的解析器以它们为准
  - `fixtures/README.md` 记录：录制时的 CLI 版本、录制命令、实际验证过可用的参数（尤其是 Codex 从 stdin 读提示词的写法）

- [ ] **Step 1:** 写 Dockerfile 和 entrypoint，构建：`docker build -t playforge-sandbox:p2 sandbox` → 预期构建成功
- [ ] **Step 2:** 写 `verify-creds.sh`，依次执行（容器用完即删）：
  ```bash
  docker run --rm -e CLAUDE_CODE_OAUTH_TOKEN -v "$CODEX_AUTH:/mnt/creds/codex/auth.json:ro" playforge-sandbox:p2 \
    sh -c 'echo "Reply with exactly: pong" | claude -p --output-format stream-json --verbose --dangerously-skip-permissions'
  docker run --rm -v "$CODEX_AUTH:/mnt/creds/codex/auth.json:ro" playforge-sandbox:p2 \
    sh -c 'cd /workspace && git init -q && echo "Reply with exactly: pong" | codex exec --json --sandbox read-only --skip-git-repo-check -'
  ```
  其中 `CLAUDE_CODE_OAUTH_TOKEN` 取自 `PLAYFORGE_CLAUDE_TOKEN`。预期：两条命令都输出 JSONL，最终结果文本包含 `pong`
- [ ] **Step 3: 关卡判断**：两条都成功 → 继续；任意一条认证失败 → **停止执行计划**，把错误输出告诉用户并讨论替代方案
- [ ] **Step 4:** 录制夹具：让 Claude 在容器里完成一个小任务（"创建 hello.js，内容为 console.log('hi')"），把 stream-json 输出存为 `claude-stream.jsonl`；让 Codex 审查这个文件，把 `--json` 输出存为 `codex-exec.jsonl`。**存档前检查夹具里不含 token 或账号邮箱**，有就替换成 `REDACTED`
- [ ] **Step 5:** 在 `fixtures/README.md` 里记录版本、命令，以及每种事件类型的一行示例
- [ ] **Step 6: Commit** `feat(sandbox): sandbox image and recorded CLI fixtures`

---

### Task 2: ProcessRunner（进程执行、流式输出、超时、取消）

**Files:**
- Create: `backend/src/main/java/dev/playforge/process/ProcessRunner.java`、`ExecResult.java`、`ProcessTimeoutException.java`
- Test: `backend/src/test/java/dev/playforge/process/ProcessRunnerTest.java`

**Interfaces:**
- Consumes: `CancellationToken`、`CancelledException`（P1）
- Produces:
  ```java
  public record ExecResult(int exitCode, String stderrTail) {}   // stderr 只保留最后 8 KB
  public class ProcessTimeoutException extends RuntimeException {}
  @Component public class ProcessRunner {
    /** 运行 cmd，把 stdin（可为 null）写入后关闭；stdout 按 UTF-8 逐行回调；
        超时抛 ProcessTimeoutException，取消抛 CancelledException；两种情况都要先结束整棵进程树。 */
    ExecResult run(List<String> cmd, Map<String,String> extraEnv, byte[] stdin, Duration timeout,
                   CancellationToken cancel, Consumer<String> stdoutLine);
    /** 二进制版本：stdin 从 InputStream 复制，stdout 原样写入 OutputStream（给 tar 用）。 */
    ExecResult runBinary(List<String> cmd, InputStream stdin, OutputStream stdout, Duration timeout, CancellationToken cancel);
  }
  ```
  测试用 `java` 本身当子进程（`ProcessHandle.current().info().command()` 拿到 java 路径），执行测试 classpath 里的一个小辅助类 `EchoMain`（放在 test 源码里：回显 stdin、输出 N 行、按参数 sleep 或以指定退出码退出），这样测试不依赖 shell。

- [ ] **Step 1: 写失败测试** `ProcessRunnerTest`：
  - `streamsLinesInOrder`：子进程输出 3 行 → 回调依次收到 `"l1","l2","l3"`，exitCode 0
  - `stdinIsDelivered`：stdin = `"中文\"引号\"\n第二行 $HOME"` 的 UTF-8 字节，子进程原样回显 → 回调收到的内容拼起来与原文完全一致
  - `nonZeroExitAndStderrTail`：子进程往 stderr 写 `"boom"` 并以 3 退出 → `exitCode==3`，`stderrTail` 包含 `"boom"`
  - `timeoutKillsProcess`：子进程 sleep 30s，timeout 300ms → 抛 `ProcessTimeoutException`，1 秒内返回，且那个子进程已不再存活（用子进程打印出的 pid 检查 `ProcessHandle.of(pid)`）
  - `cancelKillsProcess`：子进程 sleep 30s，200ms 后 `cancel()` → 抛 `CancelledException`，子进程已不再存活
  - `longLineDoesNotCrash`：子进程输出一行 2 MB 的内容 → 回调收到 1 行，长度为 2,000,000
- [ ] **Step 2:** `./mvnw -q test -Dtest=ProcessRunnerTest` → FAIL（类不存在）
- [ ] **Step 3:** 实现。stdout、stderr 各用一个虚拟线程读取；超时或取消时 `process.descendants().forEach(ProcessHandle::destroyForcibly)`，再对进程本身调用 `destroyForcibly`
- [ ] **Step 4:** 运行同上 → PASS
- [ ] **Step 5: Commit** `feat(process): process runner with streaming, timeout and cancellation`

---

### Task 3: TarSync（打包 / 镜像式解包，防路径穿越）

**Files:**
- Modify: `backend/pom.xml`（加 `org.apache.commons:commons-compress:1.27.1`）
- Create: `backend/src/main/java/dev/playforge/sandbox/docker/TarSync.java`
- Test: `backend/src/test/java/dev/playforge/sandbox/docker/TarSyncTest.java`

**Interfaces:**
- Produces:
  ```java
  public final class TarSync {
    static final Set<String> EXCLUDED_DIRS = Set.of("node_modules", "dist", ".git");
    /** 把 dir 打成 tar 写入 out，只排除顶层的 EXCLUDED_DIRS，条目名用正斜杠的相对路径。 */
    static void pack(Path dir, OutputStream out);
    /** 让 dir 与 tar 内容一致：先删除 dir 中除顶层 EXCLUDED_DIRS 以外的全部内容，再解包。
        条目名为绝对路径、含 ".." 或解析后不在 dir 内时抛 SecurityException，此时不得写入 dir 以外的任何东西。 */
    static void unpackMirror(InputStream tar, Path dir);
  }
  ```

- [ ] **Step 1: 写失败测试** `TarSyncTest`（`@TempDir`）：
  - `roundTripPreservesFilesAndChinese`：源目录有 `src/main.js`、`中文.txt`、空子目录 `assets/` → pack 后 unpackMirror 到另一个目录，文件内容一致
  - `packExcludesTopLevelDirs`：源目录有 `node_modules/a.js`、`dist/b.js`、`.git/config`、`src/node_modules_note.js` → tar 条目里只有 `src/node_modules_note.js`
  - `mirrorDeletesStaleFilesButKeepsExcluded`：目标目录原有 `old.js`、`node_modules/dep.js`、`.git/HEAD` → 解包只含 `new.js` 的 tar 后：`old.js` 不存在，`node_modules/dep.js`、`.git/HEAD` 仍在，`new.js` 存在
  - `rejectsTraversalEntry`：手工构造一个条目名为 `../evil.txt` 的 tar → 抛 `SecurityException`，`dir.getParent().resolve("evil.txt")` 不存在
  - `rejectsAbsoluteEntry`：条目名 `/etc/evil` → 抛 `SecurityException`
- [ ] **Step 2:** `./mvnw -q test -Dtest=TarSyncTest` → FAIL
- [ ] **Step 3:** 实现。**先完整校验所有条目名，再开始删除和写入**（把 tar 读进临时文件后做两遍扫描），保证遇到坏条目时目标目录保持原样
- [ ] **Step 4:** 运行同上 → PASS
- [ ] **Step 5: Commit** `feat(sandbox): tar sync with traversal protection`

---

### Task 4: DockerOps + ContainerPool + DockerSandbox

**Files:**
- Modify: `backend/src/main/java/dev/playforge/sandbox/Sandbox.java`（新增 `exec`，`workdir()` 的 Javadoc 写明 Docker 实现不支持）
- Modify: `backend/src/main/java/dev/playforge/sandbox/local/LocalSandboxProvider.java`（实现 `exec`：在宿主机目录用 ProcessRunner 运行，供本地调试）
- Modify: `backend/src/main/java/dev/playforge/config/PlayforgeProperties.java`（新增 `SandboxConfig sandbox` 配置段）
- Create: `backend/src/main/java/dev/playforge/sandbox/docker/` 下：`DockerOps.java`、`CliDockerOps.java`、`ContainerPool.java`、`DockerSandbox.java`、`DockerSandboxProvider.java`、`DockerUnavailableException.java`
- Create: `backend/src/main/resources/application-real.yml`
- Test: `backend/src/test/java/dev/playforge/sandbox/docker/CliDockerOpsTest.java`、`ContainerPoolTest.java`、`DockerSandboxIT.java`（`@Tag("docker")`）

**Interfaces:**
- Consumes: `ProcessRunner`、`ExecResult`（Task 2），`TarSync`（Task 3）
- Produces:
  ```java
  // Sandbox 新增：
  ExecResult exec(List<String> cmd, String stdin, Duration timeout, CancellationToken cancel, Consumer<String> stdoutLine);

  // PlayforgeProperties 新增组件 sandbox（类型名避开与 Sandbox 接口重名）：
  record SandboxConfig(String provider /* local|docker */, String image, int poolSize, Duration acquireTimeout, String codexAuthFile) {}

  public interface DockerOps {
    boolean available();                                   // `docker version` 能返回 Server 版本
    String runDetached(DockerRunSpec spec);                // 返回容器 id
    ExecResult exec(String containerId, List<String> cmd, byte[] stdin, Duration timeout,
                    CancellationToken cancel, Consumer<String> stdoutLine);   // 等价于 docker exec -i -w /workspace
    void copyIn(String containerId, Path hostDir);         // TarSync.pack | docker exec -i c tar -x -C /workspace
    void copyOut(String containerId, Path hostDir);        // docker exec c tar -c -C /workspace --exclude=... | TarSync.unpackMirror
    void remove(String containerId);                       // docker rm -f，容器不存在时不报错
    boolean healthy(String containerId);                   // docker exec c true 的退出码为 0
  }
  public record DockerRunSpec(String image, Map<String,String> envFromHost /* 只放变量名 → 宿主机变量名 */,
                              List<String> roMounts /* "host:container" */, String cpus, String memory, int pidsLimit) {}
  public class ContainerPool { ContainerPool(DockerOps ops, DockerRunSpec spec, int size);
    void start();                           // 异步预热 size 个容器；Docker 不可用时只记日志，不抛异常
    String acquire(Duration timeout);       // Docker 不可用 → DockerUnavailableException("Docker is not available")；超时 → 同类异常，消息写明等待时长
    void discard(String containerId);       // remove + 异步补一个新容器
    void healthCheck();                     // 删除不健康的空闲容器并补足
    void shutdown(); }                      // 删除全部容器
  ```
  `CliDockerOps.runDetached` 生成的命令形如 `docker run -d --cpus 2 --memory 4g --pids-limit 512 -e CLAUDE_CODE_OAUTH_TOKEN -v <auth>:/mnt/creds/codex/auth.json:ro playforge-sandbox:p2`，并把 `CLAUDE_CODE_OAUTH_TOKEN=<PLAYFORGE_CLAUDE_TOKEN 的值>` 放进**子进程环境**，而不是参数。
  `DockerSandbox.close()` 一律 `pool.discard(id)`（用过的容器不复用）。
  `application-real.yml`：`playforge.sandbox.provider: docker`、`engines.coder: claude`、`engines.reviewer: codex`。默认配置和 test 配置都保持 `provider: local`。
  `DockerSandboxProvider` 用 `@ConditionalOnProperty(name="playforge.sandbox.provider", havingValue="docker")` 注册；`LocalSandboxProvider` 用 `havingValue="local", matchIfMissing=true`。`healthCheck` 每 60 秒由 `@Scheduled` 调用一次（需要 `@EnableScheduling`）。

- [ ] **Step 1: 写失败测试** `CliDockerOpsTest`（给 CliDockerOps 注入一个记录调用的假 ProcessRunner，不真跑 docker）：
  - `runCommandPassesTokenByNameOnly`：宿主环境里 `PLAYFORGE_CLAUDE_TOKEN=secret-token-123` → 记录下来的命令参数里任何一项都不包含 `secret-token-123`，参数里有 `-e` 和 `CLAUDE_CODE_OAUTH_TOKEN`；extraEnv 里 `CLAUDE_CODE_OAUTH_TOKEN` 的值为 `secret-token-123`
  - `runCommandHasResourceLimits`：参数包含 `--cpus 2`、`--memory 4g`、`--pids-limit 512`
  - `execUsesWorkspaceAndStdin`：参数以 `docker exec -i -w /workspace <id>` 开头，stdin 原样传给 ProcessRunner
- [ ] **Step 2: 写失败测试** `ContainerPoolTest`（用内存版 `FakeDockerOps`：可以设置 available、让 runDetached 失败、标记某个容器不健康）：
  - `startWarmsUpToSize`：size 2 → Awaitility 等到 runDetached 被调用 2 次
  - `acquireReturnsWarmContainerAndReplenishes`：acquire 返回一个 id，之后池里仍会补足到 2 个空闲容器
  - `discardRemovesContainer`：discard 后 FakeDockerOps 记录到 remove(id)
  - `unavailableDockerFailsFastWithClearMessage`：available=false → `start()` 不抛异常；`acquire(60s)` 在 1 秒内抛 `DockerUnavailableException`，消息为 "Docker is not available"
  - `healthCheckReplacesDeadContainer`：标记一个空闲容器不健康 → healthCheck 后它被 remove，并新建了一个
  - `shutdownRemovesAll`
- [ ] **Step 3: 写失败测试** `DockerSandboxIT`（`@Tag("docker")`，Docker 不可用时跳过；用真实镜像，不需要凭证）：
  - `syncRoundTripAndExec`：宿主目录有 `a.txt`（"你好"）→ acquire + syncIn → exec `cat a.txt` 收到 "你好" → exec `sh -c "echo x > b.txt"` → syncOut → 宿主目录出现 `b.txt` → close 之后 `docker ps -a` 中查不到该容器
  - `cancelKillsCliInsideContainer`：exec `sleep 30`，200ms 后取消 → 抛 `CancelledException`；close 后容器已删除
- [ ] **Step 4:** `./mvnw -q test -Dtest='CliDockerOpsTest,ContainerPoolTest,DockerSandboxIT'` → FAIL
- [ ] **Step 5:** 实现以上全部类
- [ ] **Step 6:** 运行同上 → PASS（Docker 已启动时 IT 也必须实际运行并通过；在输出里确认 IT 不是 Skipped）
- [ ] **Step 7:** `./mvnw -q test`（全量）→ PASS，P1 的测试不受影响
- [ ] **Step 8: Commit** `feat(sandbox): docker container pool and docker sandbox`

---

### Task 5: Claude 流解析 + ClaudeCliEngine

**Files:**
- Create: `backend/src/main/java/dev/playforge/engine/claude/ClaudeStreamParser.java`、`ClaudeCliEngine.java`
- Create: `backend/src/main/java/dev/playforge/engine/StreamEvent.java`（两个解析器共用）
- Test: `backend/src/test/java/dev/playforge/engine/claude/ClaudeStreamParserTest.java`、`ClaudeCliEngineTest.java`、`backend/src/test/java/dev/playforge/engine/FakeExecSandbox.java`（测试工具：exec 时按行回放夹具，并记录收到的 cmd 和 stdin）

**Interfaces:**
- Consumes: `Sandbox.exec`（Task 4）、夹具 `claude-stream.jsonl`（Task 1）、`AgentEngine` 等 P1 接口
- Produces:
  ```java
  public sealed interface StreamEvent {
    record Text(String text) implements StreamEvent {}
    record ToolCall(String name, String inputSummary) implements StreamEvent {}   // inputSummary ≤ 500 字符
    record ToolResult(String summary, boolean isError) implements StreamEvent {}  // summary ≤ 500 字符
    record Usage(long inputTokens, long outputTokens) implements StreamEvent {}
    record Final(String text, boolean isError) implements StreamEvent {}
  }
  public final class ClaudeStreamParser { List<StreamEvent> parse(String line); }  // 坏行、未知类型 → 空列表
  @Component public class ClaudeCliEngine implements AgentEngine { /* name "claude", role CODER */ }
  ```
  映射：`Text`→`AGENT_TEXT{text}`，`ToolCall`→`TOOL_CALL{name,input}`，`ToolResult`→`TOOL_RESULT{summary,isError}`，`Usage`→`USAGE{inputTokens,outputTokens}`。Claude 调用写文件类工具（`Write`/`Edit`/`MultiEdit`）时，额外发 `FILE_CHANGED{path}`。
  命令：`["claude","-p","--output-format","stream-json","--verbose","--dangerously-skip-permissions"]`，提示词走 stdin，超时用 `req.timeout()`。
  错误归类：`Final.isError` 且文本包含 `rate limit`、`usage limit`、`overloaded` 或 `429`（不区分大小写）→ `EngineException(retryable=true)`；其他 isError → 不可重试；进程非 0 退出且没有收到 `Final` → 不可重试，消息带上 `stderrTail`。

- [ ] **Step 1: 写失败测试** `ClaudeStreamParserTest`：
  - `parsesFixtureEndToEnd`：逐行解析 `claude-stream.jsonl` → 至少 1 个 `ToolCall`，最后一个事件是 `Final(isError=false)`，有 1 个 `Usage` 且 token 数 > 0
  - `garbageLinesIgnored`：`""`、`"not json"`、`"{\"type\":\"unknown\"}"`、半截 JSON `"{\"type\":\"assi"` → 都返回空列表
  - `toolInputSummaryTruncated`：工具 input 为 5000 字符 → `inputSummary.length() <= 500`
- [ ] **Step 2: 写失败测试** `ClaudeCliEngineTest`（用 FakeExecSandbox）：
  - `replaysFixtureIntoEvents`：回放夹具 → 返回的 `AgentResult.finalText` 等于夹具里 result 的文本；sink 依次收到 `AGENT_TEXT`/`TOOL_CALL`/`FILE_CHANGED`/`USAGE` 中的若干条；`inputTokens`、`outputTokens` 与夹具一致
  - `promptGoesToStdinNotArgs`：prompt = `"做一个\"游戏\"\n$HOME"` → FakeExecSandbox 收到的 stdin 与之完全相同，cmd 里任何一项都不包含 "游戏"
  - `rateLimitIsRetryable`：回放一行 `{"type":"result","is_error":true,"result":"Claude usage limit reached"}` → 抛 `EngineException`，`retryable()==true`
  - `crashWithoutResultIsNotRetryable`：exitCode 1、stderrTail "segfault"、没有 result 行 → `retryable()==false`，消息包含 "segfault"
- [ ] **Step 3:** `./mvnw -q test -Dtest='ClaudeStreamParserTest,ClaudeCliEngineTest'` → FAIL
- [ ] **Step 4:** 实现。字段名以夹具为准（录制时记录在 fixtures/README.md 里）
- [ ] **Step 5:** 运行同上 → PASS
- [ ] **Step 6: Commit** `feat(engine): Claude Code CLI engine with stream-json parsing`

---

### Task 6: Codex 解析 + CodexCliEngine + 审查 diff 瘦身

**Files:**
- Create: `backend/src/main/java/dev/playforge/engine/codex/CodexEventParser.java`、`CodexCliEngine.java`
- Modify: `backend/src/main/java/dev/playforge/workspace/GitWorkspace.java`（`diffWorkingTree` 排除 lockfile 和二进制文件）
- Modify: `backend/src/main/java/dev/playforge/orchestration/PromptTemplates.java`（`review` 截断 diff）
- Test: `backend/src/test/java/dev/playforge/engine/codex/CodexEventParserTest.java`、`CodexCliEngineTest.java`；补充 `GitWorkspaceTest`、新建 `PromptTemplatesTest`

**Interfaces:**
- Consumes: `StreamEvent`、`FakeExecSandbox`（Task 5），夹具 `codex-exec.jsonl`（Task 1）
- Produces:
  ```java
  public final class CodexEventParser { List<StreamEvent> parse(String line); }
  @Component public class CodexCliEngine implements AgentEngine { /* name "codex", role REVIEWER */ }
  // PromptTemplates 新增常量：
  static final int MAX_REVIEW_DIFF_CHARS = 60_000;
  ```
  命令按 Task 1 实际验证过的写法（预期为 `["codex","exec","--json","--sandbox","read-only","--skip-git-repo-check","-"]`），提示词走 stdin。`finalText` = 最后一条 agent 消息的文本。错误归类规则与 Task 5 相同。

- [ ] **Step 1: 写失败测试** `CodexEventParserTest`：
  - `parsesFixtureFinalMessage`：逐行解析 `codex-exec.jsonl` → 最后得到一个 `Final(isError=false)`，文本非空；有 `Usage`
  - `garbageLinesIgnored`（同 Task 5 的四种坏行）
- [ ] **Step 2: 写失败测试** `CodexCliEngineTest`：
  - `returnsLastAgentMessage`：回放夹具 → `finalText` 等于夹具里最后一条 agent 消息的文本
  - `promptGoesToStdinNotArgs`
  - `rateLimitIsRetryable`：回放一行表示失败且消息含 "rate limit" 的事件（具体结构以夹具 README 中记录的错误事件格式为准）→ `retryable()==true`
- [ ] **Step 3: 写失败测试**：
  - `GitWorkspaceTest.diffExcludesLockfilesAndBinaries`：工作区有 `package-lock.json`、`logo.png`（含 0 字节的二进制内容）、`main.js` → diff 只包含 `main.js`
  - `PromptTemplatesTest.reviewTruncatesHugeDiff`：diff 为 200,000 字符 → `review(...)` 的结果长度 < 70,000，并包含 `[diff truncated]`
  - `PromptTemplatesTest.reviewKeepsSmallDiffIntact`：diff 为 `"+console.log('hi')"` → 结果包含原文，不包含 `[diff truncated]`
- [ ] **Step 4:** `./mvnw -q test -Dtest='CodexEventParserTest,CodexCliEngineTest,GitWorkspaceTest,PromptTemplatesTest'` → FAIL
- [ ] **Step 5:** 实现
- [ ] **Step 6:** 运行同上 → PASS
- [ ] **Step 7: Commit** `feat(engine): Codex CLI reviewer engine; trim review diff`

---

### Task 7: 真实端到端验收

**Files:**
- Modify: `backend/src/main/java/dev/playforge/PlayforgeApplication.java`（`@EnableScheduling`）
- Create: `docs/p2-runbook.md`（怎么用 `real` profile 启动、怎么看日志、怎么清理残留容器）

- [ ] **Step 1:** `./mvnw -q test`（全量）→ PASS
- [ ] **Step 2:** 用 `real` profile 启动：`./mvnw spring-boot:run -Dspring-boot.run.profiles=real`；日志里能看到预热了 2 个容器（`docker ps` 能看到 2 个 `playforge-sandbox:p2` 容器）
- [ ] **Step 3:** 创建项目并观察 SSE：
  ```bash
  curl -s -X POST localhost:8080/api/projects -H 'Content-Type: application/json' -d '{"prompt":"一个躲陨石的太空飞船小游戏，方向键移动，撞到陨石游戏结束"}'
  curl -N localhost:8080/api/runs/<runId>/events
  ```
  预期：出现 `source=claude` 的 `TOOL_CALL`/`FILE_CHANGED` 事件、`source=codex` 的审查，最终 `RUN_FINISHED`。成功或因审查/试玩以外的原因失败都要记录下来；`workspaces/<projectId>/` 下有 `package.json`、`index.html`、`src/`，git 有 1 个提交
- [ ] **Step 4:** 取消验收：再创建一个项目，在 CODE 步骤中途 `POST /api/runs/<id>/cancel` → Run 为 CANCELLED，`docker ps` 中该任务用的容器已消失，池中仍保持 2 个空闲容器
- [ ] **Step 5:** 停止服务后 `docker ps -a --filter ancestor=playforge-sandbox:p2` → 没有残留容器
- [ ] **Step 6:** 把实际结果（耗时、token、生成的文件列表、遇到的问题）写进 `docs/p2-runbook.md` 的"验收记录"一节
- [ ] **Step 7: Commit** `docs: P2 runbook and end-to-end acceptance record`

---

## P2 完成后

- 全量测试通过（Docker 已启动时 IT 实际运行）+ 真实端到端验收完成 = P2 完成
- 下一步：P3 计划（游戏模板 + 探针 SDK、Playwright 试玩、预热 `node_modules`、预览接口）。届时把镜像升级为 `playforge-sandbox:p3`，加入 Playwright 和 `/template`
