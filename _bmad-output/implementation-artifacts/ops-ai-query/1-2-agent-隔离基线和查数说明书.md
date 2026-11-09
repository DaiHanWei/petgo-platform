# Story 1.2: Agent 隔离基线和查数说明书

Status: review

<!-- 主题 ops-ai-query，不归属任何版本。分支 feat/ops-ai-query，基于 main。 -->

## Story

作为系统管理员，
我想要 agent 从第一次部署起就只拿到必要的凭证和数据权限，
以便 stag 试用期间不暴露其他服务的密钥和生产数据。

## Acceptance Criteria

1. **[L0 + L2] env 白名单**：agent 容器中存在白名单之外的变量时，`claude -p` 及其 Bash 子进程读不到；agent 进程 env 中存在 Lark 变量时拒绝启动。
2. **[L2] token 隔离**：按官方文档配置子进程凭证清理（`CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=1`），Bash 子进程读不到 `CLAUDE_CODE_OAUTH_TOKEN`；做不到时记录原因并找 Dai 确认。
3. **[L2] stag 库账号**：`claude_bot_stag` 只能 SELECT `petgo_stag`，不继承 `ops_readonly`，读不到 `petgo`；带 `statement_timeout` 30 秒和连接上限 3。仓库内提供建号 SQL 和核验 SQL。
4. **[L0 + L2] 语言与口径**：任务以 `zh`、`en`、`id` 运行时，系统提示指定对应语言并要求每个答案给出统计口径（L0 校验提示词）；说明书不含飞书卡片格式和“一律中文”规则（L0）。
5. **[L2] 网络前期验证**：在 story 记录中写明 `claude -p` 认证和代码拉取需要的域名清单，以及 internal 网络下一次真实查询的结果。
6. **[L0 构建 + L2 部署] 部署**：提供 Dockerfile 和 stag 部署脚本，服务器用 deploy key 拉取代码，不从本机上传；实际部署等待明确指令。
7. **[L0 静态 + L2] 运行限制**：容器限制 1 核 2G，不挂 docker socket，日志和代码只读挂载，不映射端口。

## Tasks / Subtasks

- [x] env 白名单（AC1、AC2）
  - [x] `runner.buildChildEnv()` 收紧为显式白名单 + 固定项（`CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=1`、`CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1`、`DISABLE_AUTOUPDATER=1`）
  - [x] 库密码不进 env，用 `PGPASSFILE` 指向只读挂载的 `.pgpass`
  - [x] `loadConfig()` 发现 `LARK_*` / `FEISHU_*` 变量时拒绝启动
- [x] 语言提示词（AC4）
  - [x] `src/prompt.mjs`：按 `lang` 生成 `--append-system-prompt`，含语言指令与“统计口径”要求
- [x] 查数说明书（AC4）
  - [x] `ai-query-agent/work/CLAUDE.md`：数据源、安全规则、查询方法、输出格式、统计口径规则；业务口径章节标明“待本地从服务器迁入”
- [x] 部署（AC6、AC7）
  - [x] `ai-query-agent/Dockerfile`（Node 24 + 固定版本 Claude Code + psql，非 root）
  - [x] `scripts/deploy-ai-query-agent-stag.sh`：SSH 到服务器，用 deploy key 拉代码、构建、运行；生产基建变更（接入 `petgo-postgres`）需显式确认
  - [x] `ai-query-agent/.env.example` 只放占位
  - [x] `docs/runbooks/runbook-ai-query-agent.md`：部署说明、域名清单待填、验收清单
- [x] 库账号（AC3）
  - [x] `ai-query-agent/deploy/sql/claude_bot_stag.sql`、`verify_claude_bot_stag.sql`
- [x] L0：`npm test` 覆盖白名单、Lark 拒启、提示词、说明书、部署脚本静态约束；`bash -n` 部署脚本

## Dev Notes

- 不连接任何真实数据库或服务器；SQL 与部署脚本只写不执行。
- 白名单代理（S2）属 Story 3.1；本 story 只准备 internal 网络方案并留出 `HTTPS_PROXY` 透传。
- 导出目录规则属 Story 2.1，说明书留出章节。
- `--bare` 仍不得出现。[Source: spec §2 D1]
- 官方文档（2026-11-09 查阅 env-vars 页）：`CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=1` 从 Bash、hooks、stdio MCP 子进程环境中剥离凭证，按变量名或值识别；是否覆盖 `CLAUDE_CODE_OAUTH_TOKEN` 文档未明说，需 L2 实测。

### References

- [Source: _bmad-output/implementation-artifacts/specs/spec-ops-ai-query.md §0、§6、§7]
- [Source: _bmad-output/planning-artifacts/ops-ai-query/epics.md Story 1.2]

## Dev Agent Record

### Agent Model Used

Claude（云端 headless session）

### Debug Log References

- 云端无 Docker daemon（`/var/run/docker.sock` 不存在），镜像未构建。

### Completion Notes List

**L1/L2 待本地验收。** L0：`cd ai-query-agent && npm test` 33/33 通过；`bash -n scripts/deploy-ai-query-agent-stag.sh` 通过。

未验证的 AC（逐条）：

- **AC1 [L2]** 真实容器内 `claude -p` 的 Bash 读不到白名单外变量：L0 已用假 claude 验证子进程 env；真实容器待部署后按 runbook §4 第 4 项实测。
- **AC2 [L2] token 隔离（待本地）**：已按官方文档设 `CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=1`。文档未明说是否覆盖 `CLAUDE_CODE_OAUTH_TOKEN`；且 claude 进程自身持有 token，与其 Bash 同 uid，Bash 理论上可读 `/proc/<claude pid>/environ`。**若实测可读，即本 AC 的「做不到」情形，须停下找 Dai 确认**（runbook §7 已写明选项）。
- **AC3 [L2] stag 库账号（待本地）**：建号 SQL 与核验 SQL 已写（`ai-query-agent/deploy/sql/`），未执行。角色级 `statement_timeout` 是会话默认值，账号可自行覆盖，runbook §7 已记录。
- **AC4 [L2]**：三种语言回答与统计口径的实际效果待 stag 实测。**业务口径待本地从服务器迁入**（`~/claude-bot/home/work/CLAUDE.md` → `ai-query-agent/work/CLAUDE.md` §5），说明书结构、安全规则、输出格式与统计口径规则已写好。
- **AC5 [L2] 网络前期验证（待本地）**：域名清单与 internal 网络下真实查询结果未测，runbook §5 留表待填。
- **AC6 [L0 构建 + L2 部署]**：Dockerfile 未构建（无 Docker daemon）；部署脚本未执行（等明确指令）。
- **AC7 [L2]**：运行限制写在部署脚本并有 L0 静态测试；`docker inspect` 核验待部署后执行。

**复审（code-review）修复项：**
- claude 与 agent 同 uid，Bash 可经 `/proc/<pid>/environ` 读到 `AGENT_SECRET`，并能读 `/data` 下他人结果 → agent 以容器内 root（`--cap-drop ALL` 后只加回 SETUID/SETGID/KILL）运行，claude 以 uid 10002 运行；`/data` 归 root 700。
- `~/.claude` 持久卷可被一次注入任务写入 hooks，影响以后所有任务 → 调用加 `--setting-sources project`，只读 root 所有的工作目录设置。
- `.pgpass` 属主与容器 uid 不符导致 psql 读不到 → 部署脚本校验属主为 10002，runbook 写明 chown。
- `/repo` 挂载的是会被下次部署 `checkout --force` 改写的工作区且含 `.git` → 改为挂载 `git archive` 生成的 `repo-<sha>` 快照。
- 建号 SQL 不可重入、漏 `-v owner` 时半途失败 → 先检查变量，角色存在时只校正属性。
- `buildSystemPrompt` 对 `constructor` 等继承键不报错 → `Object.hasOwn`；`runClaude` 在参数构建异常时 reject → 统一 resolve `AGENT_ERROR`。
- 部署脚本 Lark 检查可被前导空格 / `export` 绕过 → 正则放宽。
- 未改：临时出网网络（Story 3.1 前 stag 可任意出网）已在 runbook 标注为仅限 stag 的临时措施。

### File List

- `ai-query-agent/src/{runner,config,prompt}.mjs`（`prompt.mjs` 新增）
- `ai-query-agent/work/CLAUDE.md`（查数说明书，新增）
- `ai-query-agent/{Dockerfile,.dockerignore,.env.example}`（新增）
- `ai-query-agent/deploy/sql/{claude_bot_stag,verify_claude_bot_stag}.sql`（新增）
- `ai-query-agent/test/isolation.test.mjs`（新增）、`test/jobs.test.mjs`
- `scripts/deploy-ai-query-agent-stag.sh`（新增）
- `docs/runbooks/runbook-ai-query-agent.md`（新增）
