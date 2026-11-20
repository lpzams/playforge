# P7 设计：沙箱网络白名单（出口网关）

日期：2026-11-20 · 状态：草案，待用户确认

## 1. 为什么做

沙箱容器现在网络完全开放（`docker run` 只限了 CPU、内存、进程数）。容器里同时有：

- Claude 的 OAuth token（环境变量）和 Codex 的 auth.json（拷进了 home 目录）；
- AI 写的代码、AI 写的试玩脚本、npm 装进来的第三方包。

其中任何一段代码都能把凭证发到任意地址。decisions.md 一直把这条写成"已知取舍"，面试被问"安全怎么做"时只能说"以后可以加白名单"。P7 把它做出来，要能回答：

> 容器只能访问模型 API 和 npm 镜像这几个域名，别的一律拒绝；被拦下的请求会记下来，在生成过程里能看到。

## 2. 目标 / 不做 / 反目标

**目标**
1. 沙箱容器在网络层面**没有直连外网的路由**，只能通过一个出口网关上网。
2. 网关按**域名白名单**放行（Claude API、Codex/ChatGPT、npm 镜像），其余拒绝。
3. 每次放行/拒绝都记日志；被拒绝的请求作为事件出现在这一版的生成故事里（"拦截了 1 次外联：xxx.com"）。
4. 白名单在 `application.yml` 里配置，不改代码就能加域名。
5. 真实引擎端到端生成一个游戏，**没有误拦**（不影响正常生成）。

**不做**
- 不做 TLS 解密 / 按 URL 路径过滤（只看 CONNECT 的域名，够用且不碰证书）。
- 不做 DNS 层过滤（容器在内部网络里本来就出不去，DNS 解不解析无所谓）。
- 不换基础镜像，不重建沙箱镜像（之前镜像源 403，重建成本高）。
- 凭证轮换、只读挂载等其他 P7 项不在这次范围。

**反目标（做了也算失败）**
- 正常生成变慢明显或偶发失败（白名单漏了某个必需域名）。
- 关掉网关后行为和现在不一致（`local` 沙箱、单元测试不受影响）。
- 网关本身成为新的暴露面（例如把端口发布到宿主机局域网）。

## 3. 怎么做

### 3.1 网络结构

```
            ┌─────────── Docker 内部网络 playforge-sandbox（--internal，无外网路由）──────────┐
 沙箱容器 ──┤ HTTPS_PROXY=http://egress:3128                                                   │
 沙箱容器 ──┤                                                                                  │
            └──── playforge-egress（同时接在内部网络和默认 bridge 上，别名 egress）──────────────┘
                                         │ 只放行白名单域名
                                         ▼
                                       外网（可选：再经宿主机代理 host.docker.internal:7899）
```

- 后端启动 docker 沙箱时，幂等地确保：网络 `playforge-sandbox` 存在（`docker network create --internal`），网关容器 `playforge-egress` 在跑。
- 沙箱容器 `--network playforge-sandbox`，并设置环境变量：
  `HTTPS_PROXY` / `HTTP_PROXY` = `http://egress:3128`，`NO_PROXY=localhost,127.0.0.1`，
  `NODE_USE_ENV_PROXY=1`（让 Node 自带的 fetch 也走代理），
  `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1`（关掉 Claude Code 的遥测/错误上报等非必需请求，减少要放行的域名）。
- 就算容器里的代码不理代理环境变量、直接连 IP，也因为内部网络没有路由而失败——**代理是唯一出口，而不是"君子协定"**。

### 3.2 网关

- **镜像**：直接用现有沙箱镜像（里面有 Node），不拉新镜像。
- **代码**：`sandbox/egress/egress.mjs`，只用 Node 标准库，宿主机只读挂载进去，`node /egress/egress.mjs` 启动。
- **行为**：
  - `CONNECT host:443`：域名在白名单内 → 建隧道；否则回 403。只允许 443 端口。
  - 普通 HTTP 请求（`GET http://host/...`）：一律 403 并记日志（模型 API 和 npm 都是 HTTPS，不需要明文 HTTP）。
  - 白名单规则：精确域名，或 `*.example.com`（匹配子域名，不匹配 `example.com` 本身），大小写不敏感，去掉末尾的点。
  - 可选上游代理（`EGRESS_UPSTREAM`）：配置后，放行的连接经上游代理出去（国内访问 Anthropic 需要时用）。
  - 每个决定写一行 JSON 日志到 `/var/log/egress/egress.jsonl`（同时打到 stdout）：时间、客户端 IP、域名、端口、放行/拒绝。
- **资源**：限 0.5 CPU、128 MB 内存；不发布任何端口到宿主机。
- **生命周期**：带标签 `playforge.egress=app`；启动时如果配置（白名单、上游）变了就重建；后端关闭时移除。

### 3.3 白名单（默认值，放在 application.yml）

```yaml
playforge:
  sandbox:
    egress:
      enabled: true
      upstream: ""          # 例如 http://host.docker.internal:7899
      allow:
        - api.anthropic.com
        - chatgpt.com
        - auth.openai.com
        - api.openai.com
        - registry.npmjs.org
        - registry.npmmirror.com
        - cdn.npmmirror.com
```

真实验收时看网关日志，把被误拦的必需域名补进来，最终列表写进 decisions.md。

### 3.4 被拦截的请求怎么出现在生成故事里

- 沙箱拿到容器后记下它在内部网络里的 IP（`docker inspect`）。
- 每个步骤（写代码 / 审查 / 修复 / 试玩）结束后，后端读网关日志里**这个 IP、这个步骤时间段内**被拒绝的记录，按域名去重，发 `NETWORK_BLOCKED` 事件（payload：域名、端口、次数）。
- 前端故事在该步骤下显示一行："拦截了 1 次外联：xxx.com"。原始日志里也能看到。
- 读不到网关日志不影响生成（只记一条 warn）。

### 3.5 开关和兼容

- `egress.enabled: false` → 完全是现在的行为（默认 bridge 网络、不设代理）。
- `local` 沙箱、假引擎、单元测试都不受影响。
- 只有需要 Docker 的集成测试才会真的建网络、起网关（和现有 `DockerSandboxIntegrationTest` 一样，没有 Docker 就跳过）。

## 4. 怎么验证

1. **网关单元测试**（Node 自带 test runner）：白名单匹配规则；CONNECT 放行/拒绝；非 443 端口拒绝；明文 HTTP 拒绝；上游代理转发和超时；日志格式。
2. **后端单元测试**：`docker run` 参数里有 `--network` 和代理环境变量；关掉开关时没有；网络/网关幂等创建；日志解析和按 IP + 时间过滤；`NETWORK_BLOCKED` 事件。
3. **Docker 集成测试**：在沙箱容器里
   - 经代理访问白名单域名 → 成功；
   - 经代理访问非白名单域名 → 403，网关日志有记录；
   - 绕过代理直连 → 失败（没有路由）。
4. **真实端到端**：开着白名单用真实引擎新建一个游戏 + 一次追加修改，全程无误拦；再演示一次拦截（例如让试玩脚本里 fetch 一个外部地址），故事里能看到拦截记录。

## 5. 留给实现者决定的

- 网关日志用 `docker exec cat` 读还是 `docker logs --since` 读（倾向前者，可按时间过滤且不受日志驱动影响）。
- 前端"拦截"那一行的具体样式（沿用故事里现有的次要信息样式）。
- 网关如何感知配置变化（倾向：配置哈希写进容器标签，不一致就重建）。

## 6. 面试怎么讲

- **为什么是"内部网络 + 代理"，而不是只设 HTTPS_PROXY**：只设环境变量是君子协定，恶意代码不走代理就绕过了；内部网络让容器根本没有出网路由，代理成了唯一出口。
- **为什么按域名而不是按 IP**：模型 API 走 CDN，IP 会变；HTTPS 的 CONNECT 请求里本来就带域名，不用解密。
- **可观测**：被拦截的请求按步骤出现在生成过程里，能说清"是哪一步、哪段代码想连哪里"。
