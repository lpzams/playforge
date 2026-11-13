# 运行手册：PlayForge 后端（真实引擎 + Docker 沙箱 + AI 试玩）

## 前置条件

| 项 | 检查方法 |
|---|---|
| Docker Desktop 已启动 | `docker version` 能输出 Server 版本 |
| 已构建沙箱镜像 | `docker build --build-arg NPM_REGISTRY=https://registry.npmmirror.com/ -t playforge-sandbox:p3 sandbox`（约 3.6 GB，首次构建要下载 Chromium，十几分钟） |
| Claude 凭证 | 用户环境变量 `PLAYFORGE_CLAUDE_TOKEN`（由 `claude setup-token` 生成），**启动后端的终端或 IDE 必须是在设置变量之后打开的** |
| Codex 凭证 | `%USERPROFILE%\.codex\auth.json` 存在（执行过 `codex login`） |
| 凭证能在容器内使用 | `bash sandbox/scripts/verify-creds.sh`，两边都回复 `pong` |
| JDK 17+ | 本机用 `C:\Users\泽泽\.jdks\openjdk-22.0.1`；默认 JDK 11 不够用 |

## 启动

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=real
```

- 端口 **8090**（8080 常被本机其他 Java 项目占用）
- 启动约 15 秒后，`docker ps` 能看到 2 个 `playforge-sandbox:p3` 容器（预热池），它们带有标签 `playforge.pool=app`
- 不加 `real` profile 时使用演示引擎和本地目录，不需要 Docker

## 使用

```bash
curl -X POST localhost:8090/api/projects -H 'Content-Type: application/json' -d '{"prompt":"一个躲陨石的太空飞船小游戏"}'
curl -N localhost:8090/api/runs/<runId>/events          # 实时过程（SSE）
curl localhost:8090/api/runs/<runId>                    # 状态和步骤
curl -X POST localhost:8090/api/runs/<runId>/cancel     # 取消
```

生成的项目在 `workspaces/<projectId>/`，每个项目是一个 git 仓库。

**在浏览器里玩**：试玩通过后，构建产物会导出到 `previews/<projectId>/`，打开 `http://localhost:8090/preview/<projectId>/` 即可。

**试玩报告怎么看**：SSE 里的 `PLAYTEST_RESULT` 事件带有 `checks` 列表，每项包含 `name`、`passed` 和 `detail`：

| 检查项 | 含义 | 常见失败原因 |
|---|---|---|
| `build` | `npm run build` 成功 | 语法错误、import 路径写错 |
| `load` | 页面和所有资源加载成功（游戏挂在 `/game/` 子路径下） | 资源写成了绝对路径（`vite.config.js` 的 `base` 被改掉） |
| `probe` | 10 秒内出现 `window.__GAME_PROBE__`，初始状态为 `ready`/`playing` | 没调用 `installProbe()` |
| `not_blank` | 截图不是纯色 | 画布没画东西、渲染出错 |
| `action:<名称>` | 按住这个按键 400 毫秒后，实体或状态有变化 | 按键没接上，或探针状态没同步 |
| `restart` | `restart()` 后回到 `ready`/`playing` | 重开逻辑缺失 |
| `no_errors` | 全程没有页面异常和 `console.error` | 运行时报错 |

任何一项失败，都会把失败原因交给 Claude 修复（最多 2 次），再重新试玩。

## 清理容器

- **正常停止**（终端 Ctrl+C）：后端会自己删除所有沙箱容器。
- **强制停止**（任务管理器结束进程、IDE 强停）：会留下预热容器。**下次启动时会自动删除**（日志里出现 `removed N sandbox container(s) left by a previous run`）。
- 如果之后不打算再启动，手动清理：
  ```bash
  docker rm -f $(docker ps -aq --filter label=playforge.pool=app)
  ```

## 已知限制

- Codex 自带的 bwrap 沙箱在容器内起不来，所以以 `danger-full-access` 运行。审查结束后，编排器会用宿主机的版本把容器整个覆盖回去，审查员的任何改动都会被丢弃。
- Claude 有时用 Bash 的 heredoc 写文件，这种情况下不会产生 `FILE_CHANGED` 事件。以后计划改为从 git diff 推算。
- 试玩只做硬性检查（能构建、能加载、有探针、无报错、不是空白、按键有反应、能重开），"好不好玩"的评分在 P6。试玩截图目前只留在容器内。
- **Codex 登录可能失效（未验证）**：每个容器拿到的是 `auth.json` 的一份只读快照。登录大约一周后，Codex 会在容器里刷新 token，刷新结果不会写回宿主机。如果 OpenAI 的刷新 token 是一次性的，宿主机上那份就会作废。表现为：审查步骤失败，报认证错误，同时你自己在终端里用 `codex` 也提示要重新登录。**处理方法：在宿主机重新执行 `codex login`，然后重启后端。**之所以没有把容器里刷新后的凭证自动拷回宿主机，是因为容器里运行着不受信任的代码，让它往宿主机写凭证文件，风险比这个问题本身更大。
- 容器内的 Agent 能读到凭证（环境变量和 `auth.json`）。凭证不会出现在事件流和回放里（统一替换为 `[REDACTED]`），但容器网络是开放的，这是决策 6 的已知取舍。

## 验收记录（2026-11-09）

**端到端生成**（Run 33）：需求"一个躲陨石的太空飞船小游戏：方向键左右移动飞船，陨石从上往下落，撞到陨石游戏结束，显示得分，按空格重新开始"

| 步骤 | 耗时 | 说明 |
|---|---|---|
| CODE（Claude） | 75 s | 一条 Bash 命令写出 `package.json`、`index.html` 和 `src/{config,game,input,render,probe,main}.js`，并在容器内跑通 `vite build` |
| REVIEW（Codex） | 24 s | `{"issues":[]}` |
| PLAYTEST | <1 s | 固定通过（P3 实现） |
| COMMIT | <1 s | 1 个提交 |
| **合计** | **约 99 s** | 结果 SUCCEEDED |

- Token：Claude 输入 53,950 / 输出 4,328；Codex 输入 17,302 / 输出 45
- 生成的游戏已按协议实现 `window.__GAME_PROBE__`（`getState`、`actions`、`restart`）
- `node_modules`、`dist` 没有同步回宿主机

**取消**（Run 34，"一个打砖块小游戏"）：在 CODE 步骤运行约 20 秒时取消，接口返回 202，Run 变为 CANCELLED。该任务使用的容器被销毁，池中补回新容器，仍保持 2 个空闲。

**停服清理**：
- 启动失败时（端口被占用），已预热的容器被正确删除。
- 强制停止后会残留 2 个预热容器，下次启动时自动删除，实测通过。

**途中发现并修复的问题**：
- Codex 的 read-only 沙箱在容器内不可用 → 改为 `danger-full-access` + 审查后重新同步
- 关闭池子时中断进行中的 `docker run` 会泄漏容器 → 改为等待其完成后再删除
- 强制停止会残留容器 → 给容器加标签，启动时清理
- 8080 端口冲突 → 默认端口改为 8090

## 验收记录（2026-11-12，P3）

**端到端生成 + 试玩**（Run 97）：需求同 Run 33（躲陨石的太空飞船小游戏）

| 步骤 | 耗时 | 说明 |
|---|---|---|
| CODE（Claude） | 36 s | 基于模板改写成躲陨石游戏 |
| REVIEW（Codex） | 29 s | **发现 1 个阻塞问题**："游戏结束后按空格只重置到 ready，陨石和计分不会继续，需求要求按一次空格就重新开始" |
| FIX（Claude） | 18 s | 按审查意见修复 |
| REVIEW（Codex） | 32 s | `{"issues":[]}` |
| PLAYTEST | 4 s | 9 项检查全部通过：build、load、probe、not_blank、action:left、action:right、action:restart、restart、no_errors |
| COMMIT | <1 s | 1 个提交 |
| **合计** | **约 2 分钟** | 结果 SUCCEEDED；预览已导出，`/preview/<projectId>/` 返回 200 |

**试玩脚本的失败样本**（`PlaytestHarnessIntegrationTest`，7 个样本）：抛异常、没有探针、按键无效、黑屏、一开始就卡死、资源绝对路径，都能被准确识别。卡死的游戏在约 16 秒内就给出"页面无响应"的报告。

**审查修复后的复验（2026-11-13）**：审查指出试玩有几处会误判（画面检查被页面样式骗过、有东西自己动时按键检查失灵、跳跃类短动作被误判、`loading` 状态被误判），修复后又用真实引擎跑了两次：

- **Run 129（接金币）**：暴露出一个新问题。Claude 把"重新开始"（空格）也登记成了游戏操作，而游戏进行中按空格本来就不该有反应，于是试玩两次判它失败，Claude 只好把游戏改成"游戏中按空格也重开"才过关。修复：开始、重开、暂停这类流程控制不再当作游戏操作来测，提示词也说明只登记游戏中的操作。
- **Run 161（打砖块）**：Codex 审查发现真实的物理 bug（帧率低时球会穿进砖块、不反弹），Claude 修复后复审通过。**试玩一次通过，8 项检查全部 OK**，左右键的检查结果是"挡板位置变化"。
