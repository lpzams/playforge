# P2 运行手册：真实引擎 + Docker 沙箱

## 前置条件

| 项 | 检查方法 |
|---|---|
| Docker Desktop 已启动 | `docker version` 能输出 Server 版本 |
| 已构建沙箱镜像 | `docker build -t playforge-sandbox:p2 sandbox`（国内网络慢可加 `--build-arg NPM_REGISTRY=https://registry.npmmirror.com/`） |
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
- 启动约 15 秒后，`docker ps` 能看到 2 个 `playforge-sandbox:p2` 容器（预热池），它们带有标签 `playforge.pool=app`
- 不加 `real` profile 时使用演示引擎和本地目录，不需要 Docker

## 使用

```bash
curl -X POST localhost:8090/api/projects -H 'Content-Type: application/json' -d '{"prompt":"一个躲陨石的太空飞船小游戏"}'
curl -N localhost:8090/api/runs/<runId>/events          # 实时过程（SSE）
curl localhost:8090/api/runs/<runId>                    # 状态和步骤
curl -X POST localhost:8090/api/runs/<runId>/cancel     # 取消
```

生成的项目在 `workspaces/<projectId>/`，每个项目是一个 git 仓库。

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
- 试玩目前固定判为通过（P3 再实现）。

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
