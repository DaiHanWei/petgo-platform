# claude -p 输出 fixture

云端开发环境无法真实运行 `claude -p`，以下 fixture 依据 2026-11-09 查阅的官方文档编写，**claude 输出字段和错误文案待本地实测确认**：

| 文件 | 依据 | 待确认 |
|---|---|---|
| `success.json` | headless 文档明确的字段：`result`、`session_id`、`total_cost_usd`；`type`、`subtype`、`is_error`、`num_turns`、`duration_ms` 文档未列出 | 本地跑一次 `claude -p --output-format json`，用真实输出替换（去掉查询内容） |
| `rate-limited.json` | 文档：运行内失败时把失败作为 `result` 打到 stdout，退出码非 0；文案片段取自 errors 页 `You've hit your session limit` | 订阅额度用尽时的实际字段和完整文案 |
| `auth-failed.json` | 同上；文案片段取自 errors 页 `Not logged in · Please run /login` | token 失效（非未登录）时的实际文案 |

实测后同步更新 `src/classify.mjs` 的匹配规则。
