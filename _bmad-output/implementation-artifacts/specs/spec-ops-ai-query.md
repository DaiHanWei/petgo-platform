---
title: "ops 后台 AI 查数（替代 Lark 机器人「TT数据管理大师」）"
status: draft
created: 2026-11-09
version: 不归属任何版本（内部工具，2026-11-09 Dai）
ux: _bmad-output/planning-artifacts/ops-ai-query/ux-designs/ux-petgo-platform-2026-11-09/
---

# ops 后台 AI 查数

## 0. 背景与目的

服务器上的 `claude-bot` 容器通过 Lark 机器人替各部门查生产数据、日志和代码实现。本 spec 把入口从 Lark 群搬到 ops 后台，走我们自己的鉴权、权限和审计。

**现状（2026-11-09 只读核实）**

- 链路：Lark 群 @机器人 → 长连接事件 → `~/claude-bot/home/bridge/index.mjs`（178 行 Node）→ `claude -p`（`Bash,Read,Grep,Glob`，并发 2，超时 10 分钟）→ `psql` 只读账号 / 只读挂载日志 / `/repo` → 回卡片，`out/<消息ID>/` 新文件发回群。
- 容器 2026-11-06 起已停止，不存在线上服务的平滑迁移问题。桥接代码只在服务器上，未入库。
- 用量（10/05～11/06）：351 问，9 个提问人，单日最多 41 问；耗时 P50 28 秒、P90 68 秒、最长 427 秒；Claude Code 记录费用合计 $49.5（平均 $0.14/问）；错误 2 次；追问 108 次；74% 来自印尼群。
- 查数说明书在 `~/claude-bot/home/work/CLAUDE.md`，含业务口径（付费成功口径、排除账号、WIB 时间等），需迁入仓库。

**ops 可复用的现有能力**：`AdminPermissions` 权限码 + `@PreAuthorize`、`AdminAuditService.record` 哈希链审计、htmx 1.9.12 + `admin-core.js` 的 CSRF 注入、`@Scheduled` 扫描器（调度池 4 线程）、商家平台的「jbp-net 内网端口 + 共享密钥」模式、`messages_zh_CN / en / id` 三语资源。

## 1. 目标 / 不做 / 反目标

**目标**

1. 有 `ai.query` 权限的后台账号能在 ops 用中文或印尼语提问、追问、下载导出文件。
2. 每次提问和下载都可追溯到后台账号。
3. 查询途中 ops 发版重启不丢结果。
4. 问答与导出文件 30 天后自动清除。
5. 收口现有机器人的安全缺口（§6）。
6. 查询不受 Lark API 额度影响（登录环节的边界见 §3）。

**不做（第一期）**

- 取消进行中的查询、对话分享、多人协作、看他人对话（含超管）。
- 手机适配：只做电脑端。
- 对话的手动删除和重命名。
- 按角色或字段分权、数据脱敏：沿用 2026-10-05 决策，获授权者可查全部数据。权限码是唯一的准入闸，因此必须逐人开通。
- 自研 SQL 工具或改用 Messages API：第一期沿用 `claude -p`，保留查库 + 查日志 + 读代码的完整能力。

**反目标**

- **绝不把 agent 跑进 `petgo-server`**：ops 与 App 同一 JVM，容器内有读写库账号和支付密钥，运行镜像也只有 JRE。
- 不引入 MQ、缓存层或新的 Spring 中间件（架构护栏）。
- 不开放任何写操作能力。

## 2. 已定决策（按 2026-11-09 推荐方案）

| # | 决策 | 结论 |
|---|---|---|
| D1 | Claude 凭证 | 暂时沿用订阅 token（`CLAUDE_CODE_OAUTH_TOKEN`，`claude setup-token` 生成，2026-11-09 Dai 拍板）；调用 `claude -p` 时不得加 `--bare`（该模式不读此变量）。agent 的凭证读取保持只靠 env，日后换 API Key 只改 env |
| D2 | 开放范围 | `ai.query` 不预授给任何角色，由管理员在后台账号页面按需开通 |
| D3 | 回答语言 | 中文 / English / 印尼语三种，默认跟随后台界面语言，只在新建对话时选择，按对话保存 |
| D4 | 群内协作 | 接受失去；结果仅提问人可见，需要共享时下载文件转发 |
| D5 | Lark 入口 | 只暂停，不下线：`claude-bot` 容器保持停止，飞书应用、凭证和历史数据保留 |
| D6 | 保留期 | 问答、导出文件、Claude 会话记录保留 30 天；审计记录长期保留，且不含问题原文 |
| D7 | 异步方式 | ops 定时拉取，页面 htmx 轮询，不用 SSE / WebSocket |
| D8 | 统计口径 | 每个答案固定带统计口径，写入查数说明书 |
| D9 | 导航位置 | “概览”下方的顶层单链接，导航目录需支持这种分组 |
| D10 | 答案版式 | 报告式，占满右栏，不用聊天气泡 |

## 3. 迁移动机与 Lark 依赖边界

**Lark 额度主要被其他功能消耗，额度不足时连带影响查询（Dai 2026-11-09 确认）。** 本需求的目的是让查询不再依赖 Lark 额度，不是降低 Lark 总用量，因此无需前置核实各应用用量。

- 查询的提问、执行、取结果、下载全链路**不调用任何 Lark API**，agent 不持有 Lark 凭证。
- 剩余依赖是后台登录：普通后台账号（STAFF）只能走 Lark OAuth，服务端调用 `auth/v3/app_access_token/internal`、`authen/v1/oidc/access_token`、`authen/v1/user_info`；账密入口仅预置的超管可用。
- 飞书官方公告：「认证及授权」等业务 API 不计入自建应用调用量，额度用尽后仍可调用。上述三个接口大概率属于此类，因此**预计额度耗尽不影响 ops 登录**。公告未逐接口列出，也未提及 Lark 国际版，下次额度耗尽时实测一次 Lark 登录即可闭环。
- 仅当实测登录被拦时，才为持有 `ai.query` 的账号补不依赖 Lark 的登录方式，属认证变更，单独评审，不在第一期范围。

## 4. 总体架构

```
浏览器 /admin/ai-query（htmx 每 3 秒轮询，仅在有未完成任务时）
   │
petgo-server（鉴权 / 审计 / 落库 / 定时扫描器）
   │  内网 HTTP + 共享密钥，不映射端口、不接 Cloudflare
ai-query-agent 容器（Node 任务服务 + claude -p）
   │
只读库账号 / 只读日志 / 只读代码
```

### 4.1 任务状态机（ops 侧，权威状态）

```
PENDING ──提交成功──▶ RUNNING ──▶ DONE
   │                    ├──▶ FAILED（agent 报错 / agent 不认识该任务）
   └──超时──▶ TIMEOUT ◀─┘
```

- 用户提交时只在事务内写 `PENDING`，提交后立刻返回。
- 单个 `@Scheduled` 扫描器（fixedDelay 3 秒）负责两件事：把 `PENDING` 提交给 agent，轮询 `RUNNING` 的结果。不另开 `@Async`。
- agent 的提交接口按任务 token 幂等，ops 重启后重复提交安全。
- 状态变更一律用带旧状态条件的 UPDATE，防止重复处理。
- 超过 15 分钟未结束的任务置 `TIMEOUT`（agent 自身 10 分钟超时 + 排队余量）。
- `AI_QUERY_MODE=off`（默认）时扫描器不工作，页面提示未启用。

### 4.2 agent 内部接口

全部要求请求头 `X-Internal-Secret`，密钥经 env 注入，不匹配返回 401。

| 接口 | 说明 |
|---|---|
| `POST /jobs` | 入参 `jobToken, conversationToken, question, lang, resumeSessionId?`。返回 202，按 `jobToken` 幂等；排队超过 20 个返回 429，ops 保持 `PENDING` 下轮重试 |
| `GET /jobs/{jobToken}` | 返回 `status, answerMd, sessionId, files[{name,size}], secs, turns, costUsd, errorCode`；未知任务返回 404，ops 置 `FAILED` |
| `GET /jobs/{jobToken}/files/{name}` | 下载文件；`name` 只允许匹配该任务的文件清单，拒绝路径穿越 |
| `DELETE /conversations/{token}` | 删除该对话的导出目录和 Claude 会话记录，幂等 |

- 并发固定 2（库账号连接上限 3，留 1 个余量）。
- 完成结果落盘到 `jobs/<token>/result.json`，agent 重启后仍可取回；运行中的任务重启后丢失，由 404 转 `FAILED`。
- agent 日志不记录问题和答案原文。

## 5. ops 后台设计

### 5.1 数据模型

迁移号按 `V<yyyyMMdd_HHmm>__create_ai_query.sql` 在创建时取。整数列用 `integer`，不用 `smallint`。

**`ai_query_conversations`**：`id`、`token`（唯一、不可枚举）、`admin_account_id`（FK）、`title`（问题前 80 字）、`language`、`agent_session_id`、`created_at`、`updated_at`。

**`ai_query_messages`**：`id`、`token`（唯一）、`conversation_id`（FK，ON DELETE CASCADE）、`question`（text，上限 2000 字）、`answer_md`（text）、`status`（varchar + CHECK）、`error_code`、`files`（jsonb）、`agent_secs`、`agent_turns`、`cost_usd`（numeric(10,4)）、`submitted_at`、`finished_at`、`created_at`、`updated_at`。

索引：`status` 未完成部分索引；`(admin_account_id, updated_at desc)`；`updated_at` 供清理。

### 5.2 权限与入口

- 新增 `AdminPermissions.AI_QUERY = "ai.query"`，加入权限分组并补三语文案。
- 不加入任何 `AdminRole` 预设。`SUPER_ADMIN` 按现有规则隐式拥有。
- 控制器统一 `@PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('ai.query')")`。
- 导航按 `fragments/nav.html` 现有的入口门机制登记，不手写 `sec:authorize`。
- 对话归属强校验，只能访问自己的对话（含超管）。

### 5.3 页面

路由 `/admin/ai-query`，对话深链 `/admin/ai-query/{conversationToken}`：复用 `tpl-a-workbench`，左侧本人对话列表，右侧消息流和输入框。轮询同时更新左侧列表状态；用户离开期间完成时，浏览器标签页提示已完成。逐屏规格见 frontmatter 的 `ux` 目录。

| 场景 | 表现 |
|---|---|
| 无权限 | 走现有 denied 页，导航不显示入口 |
| `AI_QUERY_MODE=off` | 提示未启用，输入框禁用 |
| 无历史对话 | 空态 + 示例问题 |
| `PENDING` / `RUNNING` | 显示查询中和已用时长，每 3 秒轮询；结束后返回的片段不再带轮询属性 |
| 同一对话有未完成任务 | 禁止追问（同一 Claude 会话不可并发） |
| 单人未完成任务达 3 个 | 拒绝提交并提示稍后再试 |
| `FAILED` / `TIMEOUT` | 显示可理解的原因，提供用原问题重试 |
| agent 不可达 | 任务保持 `PENDING`，到时转 `TIMEOUT` |
| 订阅额度用尽或 token 失效 | 任务转 `FAILED`，`error_code` 区分为 `AGENT_RATE_LIMITED` / `AGENT_AUTH_FAILED`，页面提示稍后重试或联系管理员 |
| 对话不存在 / 属于他人 / 已过期 | 统一 404，不暴露是否存在 |
| 文件已过期 | 提示已清除，请重新导出 |
| 问题为空或超长 | 表单校验提示 |
| 页面轮询时连接中断 | 提示查询仍在后台运行，恢复后自动继续 |
| 对话列表过长 | 沿用工作台滚动加载 |

### 5.4 答案渲染

- 仓库内无 Markdown 渲染和净化依赖，新增前端 vendor：`marked` + `DOMPurify`（固定版本，放 `/admin/vendor/`）。
- 必须净化后再写入页面；禁用原始 HTML；**禁止 `img`**（防止通过图片 URL 外带数据）；链接加 `rel="noopener noreferrer"`。

### 5.5 审计

- 提交和下载分别调用 `AdminAuditService.record`，动作 `AI_QUERY_SUBMIT`、`AI_QUERY_DOWNLOAD`。
- `summary` 只写对话 token、消息 token、语言、问题长度或文件名，**不写问题原文**（可能含手机号等 PII）。
- 后端应用日志不记录问题和答案。

### 5.6 配置

`AI_QUERY_MODE`、`AI_QUERY_AGENT_URL`、`AI_QUERY_AGENT_SECRET`、`AI_QUERY_RETENTION_DAYS`（默认 30）。全部 env 注入，`.env.example` 只放占位。

### 5.7 数据清理

每日定时任务：取 `updated_at` 超过 30 天的对话，先调 agent 的 `DELETE /conversations/{token}`，成功后再删库。agent 不可达则跳过，下轮重试。注销用户的数据快照随 30 天保留期清除。

## 6. 安全要求（上生产前必须完成）

| # | 问题（现状） | 要求 |
|---|---|---|
| S1 | `claude` 继承容器全部 env，Bash 可读 token、数据库密码、Lark secret | 按白名单传入 env，彻底移除 Lark 变量；订阅 token 对 Bash 子进程的隔离方式按实施时官方文档确认（含子进程凭证清理选项） |
| S2 | 帖子、评论等用户内容可能带提示注入，容器可任意出网 | 容器放进 Docker internal 网络，仅经白名单代理访问 Anthropic API 和 GitHub。删除 `curl` 不算有效措施 |
| S3 | stag 与生产共用同一 bot | 见 §7 分环境隔离 |
| S4 | `out/` 从未清理，且「重发历史文件」规则能跨用户取文件 | 按对话隔离目录，30 天清理，删除该规则 |
| S5 | Claude 会话记录（`~/.claude/projects`）含完整查询结果，无清理 | 纳入 §5.7 清理范围 |
| S6 | 说明书面向飞书卡片 | 重写格式规则，移除「一律中文」，由系统提示按 `lang`（zh / en / id）指定语言，并要求每个答案带统计口径 |

沿用现有约束：独立容器（1 核 2G）、不挂 docker socket、库账号只读 + `statement_timeout` 30 秒 + 连接上限 3、日志只读挂载。

## 7. 环境与部署

| | staging | 生产 |
|---|---|---|
| 容器 | `ai-query-agent-stag` | `ai-query-agent` |
| 网络 | 独立 internal 网络（stag） | 独立 internal 网络（prod） |
| 默认库 | `petgo_stag` | `petgo` |
| 库账号 | 新建 `claude_bot_stag` | 沿用 `claude_bot` |
| 日志挂载 | 仅 stag | 生产 + stag |
| ops | `petgo-server-stag` | `petgo-server` |

- `claude_bot_stag` **不继承 `ops_readonly`**，只在 `petgo_stag` 内单独授 SELECT（配 `ALTER DEFAULT PRIVILEGES`），否则 stag 后台能查到生产数据。
- 把 `petgo-server(-stag)` 和 `petgo-postgres` 接入 internal 网络使用 `docker network connect`，无需重启，但属生产基建变更，**执行前须 Dai 确认**。
- 生产和 stag 的 agent 使用同一个订阅 token，写入各自的 env 文件（权限 600），不入库。白名单代理需放行该 token 实际访问的 Anthropic 域名，实施时在 stag 实测确认。
- agent 代码入库（目录 `ai-query-agent/`），服务器用 deploy key 拉取后构建，不从本机上传。
- 分支：`feat/ops-ai-query`，不带版本号，基于 `main`（2026-11-09 Dai）；上线路径开工前确认。部署各环境均等明确指令。
- agent 与 ops 须同批上线；`AI_QUERY_MODE` 保持 `off`，验证通过后再开启。

## 8. Story 拆分

正式拆分以 `_bmad-output/planning-artifacts/ops-ai-query/epics.md` 为准（3 个 epic、14 个 story），下表仅保留原估算，预计 5～7 个工作日。

| Story | 内容 | 预估 |
|---|---|---|
| 1 | agent 任务服务 + 说明书迁入 + 入库 | 1 天 |
| 2 | 安全隔离：env 白名单、internal 网络、白名单代理、stag 库账号 | 1 天 |
| 3 | 迁移、权限码、状态机、扫描器、审计 | 1 天 |
| 4 | ops 页面、轮询、渲染净化、三语文案 | 1～1.5 天 |
| 5 | 文件下载、数据清理 | 0.5 天 |
| 6 | stag 验收 | 1 天 |
| 7 | 生产上线 | 0.5 天 |

## 9. 验收标准

| # | 验收项 | 层级 |
|---|---|---|
| 1 | 迁移通过 `check-flyway-versions.sh`，后端 `mvn -B clean package` 通过 | L0 |
| 2 | 状态机单测：提交、完成、失败、超时、幂等重复提交、状态条件更新 | L0 |
| 3 | 无 `ai.query` 的账号访问所有路由均被拒；他人对话 token 返回 404 | L1 |
| 4 | 文件下载校验归属，路径穿越请求被拒 | L1 |
| 5 | 含 `<script>`、`<img>` 的答案不执行、不加载 | L1 |
| 6 | 审计有提交和下载记录，且不含问题原文；应用日志无问答内容 | L1 |
| 7 | 超过保留期的对话，库、导出文件、会话记录均被清除 | L1 |
| 8 | stag 真实问答：中文、印尼语、追问、导出文件下载 | L2 |
| 9 | 查询途中重启 `petgo-server-stag`，启动后仍拿到结果 | L2 |
| 10 | 查询途中重启 agent，任务转失败且页面可重试 | L2 |
| 11 | 并发 3 个以上查询时排队正常，库连接不超限 | L2 |
| 12 | 容器内 Bash 无法访问白名单外地址，读不到 Lark secret | L2 |
| 13 | stag agent 查不到生产库数据，读不到生产日志 | L2 |
| 14 | 用历史高频问题比对新旧回答的数字与口径 | L2 |

## 10. 上线与回退

1. stag 验收通过后，生产先以 `AI_QUERY_MODE=off` 发布后端，再起 agent 容器。
2. 给 Dai 开权限试跑，确认后由管理员按需开通。

回退：`AI_QUERY_MODE=off` 并停止 agent 容器，不影响后台其他功能。旧容器和 Lark 应用保留，可 `docker start claude-bot` 恢复（需先还原其配置）。

## 11. 风险

| 风险 | 应对 |
|---|---|
| Lark 登录接口未按预期豁免额度（概率低），普通账号无法重新登录 ops | 额度耗尽时实测，见 §3；已登录会话默认 8 小时内不受影响 |
| 印尼同事不习惯切换到后台，使用量下降 | 提前告知入口，界面和回答支持印尼语 |
| 订阅 token 由多人共用，官方条款要求最终用户使用各自凭证，并建议给他人用的工具走 API Key | 已知风险，Dai 2026-11-09 决定暂时接受；凭证只靠 env，可随时切换 API Key |
| 与 token 所属账号的日常使用共享订阅限额，高峰时查询可能失败 | 失败原因单独标记；统计 `AGENT_RATE_LIMITED` 次数，频繁出现时再切 API Key |
| 订阅 token 约一年到期（现有 token 2026-10-05 生成），到期后全部查询失败 | 到期前重新生成并更新两个环境的 env，重建容器生效；`AGENT_AUTH_FAILED` 记 error 日志 |
| `cost_usd` 在订阅方式下只是估算值，不是实际扣费 | 仅用于观察用量，不作账单依据 |
| agent 查询占用共享数据库资源 | 沿用连接上限 3 和 30 秒语句超时 |
| Bash 子进程能读到订阅 token：凭证剥离依赖 bubblewrap，当前服务器内核不允许容器内创建命名空间（2026-11-10 stag 实测） | Dai 2026-11-10 确认关闭剥离（`SUBPROCESS_ENV_SCRUB=0`）并接受风险；靠说明书安全规则和 Story 3.1 出网白名单降低泄露可能；token 泄露时重新生成 |
| 权限一旦授予即可查全部数据 | 逐人开通，定期复核名单，审计可追溯 |
