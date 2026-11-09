# Story 1.1: Agent 执行一条查询并保存结果

Status: ready-for-dev

<!-- 主题 ops-ai-query，不归属任何版本。分支 feat/ops-ai-query，基于 main。 -->

## Story

作为 ops 后端，
我想要通过内部接口把问题交给 agent 执行，
以便查询在 `petgo-server` 之外运行，结果可以稍后取回。

## Acceptance Criteria

本 story 只有 agent 侧，不改 Java、不改页面，全部验收在 L0 完成，用假的 `claude` 可执行文件测试。

1. **[L0] 提交并执行**：请求带正确的 `X-Internal-Secret` 时，`POST /jobs` 返回 202，并用 `claude -p` 执行，参数中不含 `--bare`。
2. **[L0] 幂等**：同一 `jobToken` 重复提交返回 202，不产生第二个任务，已完成的任务也不重跑。
3. **[L0] 追问**：请求带 `resumeSessionId` 时，以 `--resume <id>` 沿用该 Claude 会话。
4. **[L0] 鉴权**：缺少密钥或密钥错误时，任一接口返回 401，且先于其他校验。
5. **[L0] 并发与排队**：最多 2 个任务同时运行；已有 2 个运行且排队 20 个时，新提交返回 429。
6. **[L0] 结果落盘**：任务完成后写入 `jobs/<token>/result.json`；agent 重启后，`GET /jobs/{jobToken}` 仍返回 `status, answerMd, sessionId, files, secs, turns, costUsd, errorCode`。
7. **[L0] 未知任务**：任务未知，或运行中的任务因 agent 重启丢失时，`GET /jobs/{jobToken}` 返回 404。
8. **[L0] 超时**：任务超过 10 分钟时，结束对应 `claude -p` 及其子进程，释放并发名额，任务标记失败，`errorCode` 为 `AGENT_TIMEOUT`。
9. **[L0] 日志**：agent 日志不包含问题和答案原文，也不输出 `claude` 的 stdout、stderr 内容。
10. **[L0] 错误分类**：`claude -p` 报额度用尽或授权失效时，`errorCode` 分别为 `AGENT_RATE_LIMITED`、`AGENT_AUTH_FAILED`；其他失败为 `AGENT_ERROR`。

## Tasks / Subtasks

- [ ] 建立 `ai-query-agent/` 工程（AC: 全部）
  - [ ] `package.json`：`"type": "module"`，`engines.node >= 24`，`"test": "node --test"`，不加任何运行依赖
  - [ ] `src/config.mjs`：读取 `AGENT_SECRET`（缺失时拒绝启动）、`PORT`、`DATA_DIR`、`CLAUDE_BIN`（默认 `claude`）、`JOB_TIMEOUT_MS`（默认 600000）
  - [ ] 根目录 `.gitignore` 增加 `ai-query-agent/data/`
- [ ] 先写失败的测试和假 `claude`（AC: 1-10）
  - [ ] `test/fake-claude.mjs`：记录收到的参数和 env，按 stdin 内容输出成功、额度用尽、授权失效、挂起
  - [ ] 所有定时值通过配置注入，测试不真实等待 10 分钟
- [ ] 实现 HTTP 层 `src/server.mjs`（AC: 1, 2, 4, 5, 6, 7）
  - [ ] 使用 `node:http`；密钥比较使用 `crypto.timingSafeEqual`，先比较 SHA-256 摘要避免长度差异
  - [ ] `POST /jobs` 校验 `jobToken, conversationToken, question, lang`，`lang` 仅允许 `zh|en|id`，请求体上限 64KB
  - [ ] `jobToken`、`conversationToken` 必须匹配 `^[A-Za-z0-9_-]{8,128}$`，防止路径穿越
- [ ] 实现任务队列 `src/jobs.mjs`（AC: 2, 5, 7）
  - [ ] 内存队列，并发 2，排队上限 20；同一 token 在内存或磁盘已存在时直接返回 202
- [ ] 实现执行器 `src/runner.mjs`（AC: 1, 3, 8, 9, 10）
  - [ ] `spawn` 不使用 shell，问题通过 stdin 传入；`detached: true` 建立独立进程组
  - [ ] 参数：`-p --output-format json --allowedTools Bash,Read,Grep,Glob`，追问时加 `--resume <id>`
  - [ ] 子进程 env 只传 `PATH`、`HOME`、`CLAUDE_CODE_OAUTH_TOKEN`，集中在 `buildChildEnv()`，Story 1.2 在此收紧，不得展开 `process.env`
  - [ ] 超时先向进程组发 `SIGTERM`，5 秒后 `SIGKILL`，等 `close` 事件后才释放名额
  - [ ] 错误分类集中在 `classifyFailure()`
- [ ] 实现结果存储 `src/store.mjs`（AC: 6, 7）
  - [ ] 先写临时文件再 `rename`；`result.json` 不保存问题原文
  - [ ] 本 story 的 `files` 恒为空数组，文件能力在 Story 2.1 实现
- [ ] 运行 `cd ai-query-agent && npm test`，全部通过（AC: 全部）

## Dev Notes

### 范围

- 只实现 `POST /jobs` 和 `GET /jobs/{jobToken}`。`GET /jobs/{token}/files/{name}`、`DELETE /conversations/{token}` 属于 Epic 2，本 story 对这两个路径返回 404（鉴权仍先执行）。
- 查数说明书、语言提示词、统计口径属于 Story 1.2。本 story 接收并校验 `lang`，不生成提示词。
- Dockerfile、部署、数据库账号都属于 Story 1.2，不要在这里连接数据库。

### 必须遵守

- **绝不把 agent 放进 `petgo-server`**，也不要在 `petgo-backend/` 下添加任何文件。[Source: spec-ops-ai-query.md §1 反目标]
- 调用 `claude -p` **不得加 `--bare`**，该模式不读取 `CLAUDE_CODE_OAUTH_TOKEN`。[Source: spec-ops-ai-query.md §2 D1]
- 日志只允许出现 `jobToken`、状态、耗时、`errorCode`。stderr 可能带查询内容，只用于分类，不写日志。[Source: spec-ops-ai-query.md §4.2]
- 不引入框架和新中间件，只用 Node 标准库。[Source: CLAUDE.md 强制护栏]
- 运行中任务重启后丢失是预期行为，由 404 转 `FAILED`；不要做运行中任务的持久化恢复。[Source: spec-ops-ai-query.md §4.2]

### 接口契约（Story 1.5 依赖，不要改字段名）

```
POST /jobs
  { jobToken, conversationToken, question, lang, resumeSessionId? }
  202 | 400 参数错误 | 401 | 413 请求体过大 | 429 排队已满

GET /jobs/{jobToken}
  200 { status: QUEUED|RUNNING|DONE|FAILED, answerMd, sessionId, files: [], secs, turns, costUsd, errorCode }
  401 | 404
```

`errorCode` 取值：`AGENT_RATE_LIMITED`、`AGENT_AUTH_FAILED`、`AGENT_TIMEOUT`、`AGENT_ERROR`，成功时为 `null`。

### `claude` 输出

本机 Claude Code 2.1.319 已确认存在 `-p`、`--output-format`、`--resume`、`--allowedTools`、`--bare`。JSON 输出的字段（预期为 `result`、`session_id`、`num_turns`、`total_cost_usd`、`is_error`）和额度、授权错误的实际文案，开发时用一次真实运行确认，并固定到 `test/fixtures/`。不要凭记忆写匹配规则；无法识别的失败一律归为 `AGENT_ERROR`。

### 项目结构

```
ai-query-agent/
  package.json
  src/{config,server,jobs,runner,store}.mjs
  test/{fake-claude.mjs, *.test.mjs, fixtures/}
```

仓库现有 CI 的路径过滤不包含此目录，本 story 不新增 CI，验收以本地 `npm test` 为准。

### References

- [Source: _bmad-output/implementation-artifacts/specs/spec-ops-ai-query.md §0、§1、§2、§4.2、§6]
- [Source: _bmad-output/planning-artifacts/ops-ai-query/epics.md Story 1.1、Story 1.2、Story 1.5]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
