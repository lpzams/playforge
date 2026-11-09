# CLI 输出夹具

2026-11-09 在 `playforge-sandbox:p2` 容器里录制的真实输出，解析器测试以此为准。录制前已检查，不含 token、邮箱等凭证信息。

| 文件 | CLI 版本 | 命令（提示词都走 stdin） |
|---|---|---|
| `claude-stream.jsonl` | Claude Code 2.1.303 | `claude -p --output-format stream-json --verbose --dangerously-skip-permissions` |
| `codex-exec.jsonl` | codex-cli 0.166.0 | `codex exec --json --sandbox danger-full-access --skip-git-repo-check -` |
| `codex-exec-bwrap-failure.jsonl` | codex-cli 0.166.0 | 同上，但用 `--sandbox read-only`：容器内 bwrap 启动失败的样本 |

录制任务：Claude 创建 `hello.js`（内容 `console.log('hi');`），再让 Codex 审查它。

## Claude stream-json 事件（每行一个 JSON）

| `type` | 用途 | 示例字段 |
|---|---|---|
| `system` / `subtype=init` | 会话开始 | `cwd`、`session_id`、`tools`、`model` |
| `assistant` | 模型输出 | `message.content[]`：`{type:"text",text}` 或 `{type:"tool_use",name,input}`；`message.usage` |
| `user` | 工具结果 | `message.content[]`：`{type:"tool_result",tool_use_id,content,is_error?}` |
| `rate_limit_event` | 额度状态 | `rate_limit_info.status`（`allowed` 等）、`resetsAt` |
| `result` | 最终结果 | `subtype`（`success`/错误类）、`is_error`、`result`（最终文本）、`usage.input_tokens`/`output_tokens` |

写文件的工具名：`Write`（`input.file_path`）、`Edit`、`MultiEdit`、`NotebookEdit`。

## Codex `exec --json` 事件

| `type` | 用途 | 示例字段 |
|---|---|---|
| `thread.started` | 会话开始 | `thread_id` |
| `turn.started` | 一轮开始 | — |
| `item.started` / `item.completed` | 条目 | `item.type`：`agent_message`（`text`）、`command_execution`（`command`、`aggregated_output`、`exit_code`、`status`） |
| `turn.completed` | 一轮结束 | `usage.input_tokens`、`output_tokens` |

**最终文本 = 最后一条 `item.type == "agent_message"` 的 `text`。**

**未录到的失败事件**：Codex 的限额 / 错误事件没有实际录到。解析器按 `type` 为 `error`（字段 `message`）或 `turn.failed`（字段 `error.message`）处理，这个格式**尚未验证**，遇到真实样本后要补充夹具。
