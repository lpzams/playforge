# PlayForge 沙箱镜像

每个 Run 在一个独立容器里执行。容器内装有 Claude Code CLI（写代码）、Codex CLI（审查）、游戏模板 `/template`（Vite + 探针 SDK，依赖已预装，断网可构建）和试玩脚本 `/opt/playtest`（Playwright + Chromium）。以非 root 用户 `node` 运行，工作目录为 `/workspace`。镜像约 3.6 GB。

## 构建

```bash
docker build -t playforge-sandbox:p3 sandbox
# 国内网络慢时可换 npm 源：
docker build --build-arg NPM_REGISTRY=https://registry.npmmirror.com/ -t playforge-sandbox:p3 sandbox
```

## 凭证

| CLI | 来源 | 进入容器的方式 |
|---|---|---|
| Claude Code | `claude setup-token` 生成的一年期 token，存在用户环境变量 `PLAYFORGE_CLAUDE_TOKEN` | `docker run -e CLAUDE_CODE_OAUTH_TOKEN`（只在命令行里写变量名，值从进程环境继承） |
| Codex | `%USERPROFILE%\.codex\auth.json` | 只读挂载到 `/mnt/creds/codex/auth.json`，`entrypoint.sh` 启动时复制到 `~/.codex/` |

验证两边是否都能用：`bash sandbox/scripts/verify-creds.sh`（两边都应回复 `pong`）。

## 已知限制

- **Codex 自带的 bwrap 沙箱在容器内无法启动**（容器内不允许非特权用户命名空间），所以以 `--sandbox danger-full-access` 运行，隔离交给 Docker 容器负责。为了保证"审查员不能改代码"，编排器在 REVIEW 步骤结束后会用宿主机的版本把 `/workspace` 整个覆盖回去。
- Claude Code 以 `--dangerously-skip-permissions` 运行，同样依靠容器隔离。
- 容器网络完全开放（决策 6 的已知取舍）。
