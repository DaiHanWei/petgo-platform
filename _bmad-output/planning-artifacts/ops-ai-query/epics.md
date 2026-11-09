---
stepsCompleted: [1, 2, 3, 4]
inputDocuments:
  - _bmad-output/implementation-artifacts/specs/spec-ops-ai-query.md
  - _bmad-output/planning-artifacts/ops-ai-query/ux-designs/ux-petgo-platform-2026-11-09/DESIGN.md
  - _bmad-output/planning-artifacts/ops-ai-query/ux-designs/ux-petgo-platform-2026-11-09/EXPERIENCE.md
---
# ops AI 查数 - Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for ops AI 查数, decomposing the requirements from the PRD, UX Design if it exists, and Architecture requirements into implementable stories.

## Requirements Inventory

### Functional Requirements

FR1: 持有 `ai.query` 权限的后台账号可在 `/admin/ai-query` 用中文、英文或印尼语提问，问题上限 2000 字。
FR2: 用户可在同一对话内追问，追问沿用同一 Claude 会话。
FR3: 提交后只在事务内写入 `PENDING` 并立即返回，用户可离开页面，结果保留。
FR4: 任务状态按 `PENDING → RUNNING → DONE / FAILED / TIMEOUT` 流转，超过 15 分钟未结束置 `TIMEOUT`，状态变更一律带旧状态条件。
FR5: 单个 `@Scheduled` 扫描器（fixedDelay 3 秒）提交 `PENDING`、轮询 `RUNNING`；`AI_QUERY_MODE=off`（默认）时不工作，页面提示未启用。
FR6: ops 重启后任务可继续，agent 提交接口按任务 token 幂等；agent 不认识的任务转 `FAILED`。
FR7: 答案以 Markdown 净化后渲染，禁用原始 HTML 和 `img`，链接加 `rel="noopener noreferrer"`，每个答案带统计口径。
FR8: 用户可下载本人答案的导出文件，下载校验归属并拒绝路径穿越。
FR9: 失败原因分为 `FAILED`、`TIMEOUT`、`AGENT_RATE_LIMITED`、`AGENT_AUTH_FAILED`，前三类可用原问题重试，授权失效提示联系管理员。
FR10: 同一对话有未完成任务时禁止追问；单人未完成任务达到 3 个时拒绝提交。
FR11: 新增权限码 `ai.query`，不加入任何角色预设，`SUPER_ADMIN` 按现有规则隐式拥有，控制器用 `@PreAuthorize` 校验。
FR12: 用户只能访问本人对话（含超管）；不存在、属于他人、已过期统一返回 404。
FR13: 提交和下载写入 `AdminAuditService`，动作 `AI_QUERY_SUBMIT`、`AI_QUERY_DOWNLOAD`，摘要不含问题原文。
FR14: 每日清理最后更新超过 30 天的对话，先删 agent 上的导出文件和 Claude 会话记录，成功后再删库；agent 不可达则下轮重试。
FR15: agent 提供 `POST /jobs`、`GET /jobs/{token}`、文件下载、`DELETE /conversations/{token}` 四个内部接口，全部校验 `X-Internal-Secret`。
FR16: agent 并发固定 2，排队超过 20 个返回 429，完成结果落盘，重启后可取回。
FR17: 任务超时后，agent 结束对应的 `claude -p` 子进程，不继续占用并发名额。

### NonFunctional Requirements

NFR1: agent 绝不运行在 `petgo-server` 内，使用独立容器（1 核 2G），不挂 docker socket。
NFR2: 不引入 MQ、缓存层或新的 Spring 中间件；异步只用 `@Scheduled` + DB 状态机。
NFR3: 不开放写操作；库账号只读，`statement_timeout` 30 秒，连接上限 3。
NFR4: 后端日志和 agent 日志不记录问题和答案原文。
NFR5: 对外标识使用不可枚举 token。
NFR6: 凭证全部经 env 注入，`.env.example` 只放占位。
NFR7: agent 只接收白名单 env，移除 Lark 变量（S1）。
NFR8: 容器放入 Docker internal 网络，只经白名单代理访问 Anthropic API 和 GitHub（S2）。
NFR9: stag 与生产的容器、网络、库账号隔离，`claude_bot_stag` 不继承 `ops_readonly`（S3）。
NFR10: 导出目录按对话隔离，删除“重发历史文件”规则，Claude 会话记录纳入 30 天清理（S4、S5）。
NFR11: 查数说明书迁入仓库并重写格式规则，按 `lang` 指定语言（S6）。
NFR12: 查询全链路不调用 Lark API，agent 不持有 Lark 凭证。
NFR13: 模型输出的 XSS 必须有针对净化配置的直接测试，覆盖 `<script>`、`<img>`、事件属性和危险链接。

### Additional Requirements

- 无 starter template：扩展现有 ops 后台，新增 `ai-query-agent/` 目录（Node 任务服务 + `claude -p`）。
- 迁移按 `V<yyyyMMdd_HHmm>__create_ai_query.sql` 命名，新建 `ai_query_conversations`、`ai_query_messages`；整数列用 `integer`；`status` 用 varchar + CHECK；索引见规划 §5.1。
- 分支 `feat/ops-ai-query`，基于 `main`；上线路径开工前确认。
- 新增配置 `AI_QUERY_MODE`、`AI_QUERY_AGENT_URL`、`AI_QUERY_AGENT_SECRET`、`AI_QUERY_RETENTION_DAYS`。
- Claude 凭证沿用订阅 token（`CLAUDE_CODE_OAUTH_TOKEN`），调用 `claude -p` 不得加 `--bare`，凭证只靠 env。
- 前端 vendor 新增固定版本的 `marked`、`DOMPurify`，放 `/admin/vendor/`。
- agent 代码入库，服务器用 deploy key 拉取后构建，不从本机上传。
- `docker network connect` 接入生产网络属生产基建变更，执行前须 Dai 确认。
- agent 与 ops 同批上线，`AI_QUERY_MODE` 保持 `off`，验证通过后再开启。
- 权限由管理员在现有后台账号页面开通，不需要单独的开号工作。
- Lark 入口只暂停：`claude-bot` 容器保持停止，飞书应用、凭证和历史数据保留，不做下线清理。
- 验收项见规划 §9，L0、L1、L2 分层。

### UX Design Requirements

UX-DR1: 在 `AdminPageCatalog` 增加顶层单链接“🤖 AI 查数”，位于“概览”下方，权限不足时不显示；更新侧栏链接与页面权限的比对测试。
UX-DR2: 页面复用 `tpl-a-workbench`，左栏 360px 本人对话列表，右栏最小 720px，电脑端专用。
UX-DR3: 新增样式集中在 `admin.css` 的 `aiq-` 前缀下，颜色和圆角使用现有令牌，仅 `surface-quiet` 为新增值。
UX-DR4: 输入框 `aiq-box` 合并文本框、语言、快捷键提示和发送；Enter 发送，Shift+Enter 换行，输入法选词时不发送；错误紧贴输入框并保留内容；为空提示“请输入问题”。
UX-DR5: 回答语言提供中文、English、Bahasa Indonesia，默认跟随后台界面语言，只在新建对话时选择，追问时显示为只读。
UX-DR6: 空状态显示用户、支付、导出、日志四类示例问题卡片，点击只填入输入框，不直接发送。
UX-DR7: 答案报告式占满右栏，按结论、表格、统计口径、文件卡、页脚排列；打开对话时定位到最新一轮，多轮之间有分隔线。
UX-DR8: 答案表格有圆角容器，过宽时在容器内横向滚动，数字等宽。
UX-DR9: 文件卡显示文件名、大小和下载；已清除时显示“已清除”和重新导出提示。
UX-DR10: 对话列表状态用圆点加文字：排队中、查询中（带计时）、已完成、失败；用现有 `q-list`，滚动加载 `q-more`。
UX-DR11: 等待状态显示已用时长和“可以离开页面”；同一对话的输入框禁用并说明原因；不提供取消按钮。
UX-DR12: 错误卡在原位置显示：失败和授权失效用 `{colors.danger}`，超时、额度用尽、连接中断用 `{colors.warn}`；重试按钮放在原因旁，授权失效不提供重试且禁用输入框。
UX-DR13: 仅在存在未完成任务时每 3 秒轮询，同时更新左右两栏，全部结束后停止。
UX-DR14: 用户离开期间任务完成时，浏览器标签页显示“(1) 已完成”，回到页面后恢复。
UX-DR15: 轮询失败时显示连接中断提示，恢复后自动继续。
UX-DR16: 页脚提供复制（整个答案）和重新查询（当前对话新增一轮）；点击区域约 40px。
UX-DR17: 无障碍：输入框有读屏标签，错误 `role="alert"`，状态 `aria-live="polite"`，状态同时有文字；“查询中”圆点动效遵守 `prefers-reduced-motion`。
UX-DR18: 所有新增文案进入 `messages_zh_CN / en / id`，示例问题同样本地化。

### FR Coverage Map

FR1: Epic 1 - 提问
FR2: Epic 1 - 追问
FR3: Epic 1 - 提交后可离开
FR4: Epic 1 - 任务状态机
FR5: Epic 1 - 扫描器和开关
FR6: Epic 1 - 重启与幂等
FR7: Epic 1 - 答案净化渲染
FR8: Epic 2 - 文件下载
FR9: Epic 1 - 失败分类与重试
FR10: Epic 1 - 未完成任务限制
FR11: Epic 1 - 权限码
FR12: Epic 1 - 对话归属
FR13: Epic 1（提交审计）/ Epic 2（下载审计）
FR14: Epic 2 - 30 天清理
FR15: Epic 1（POST /jobs、GET /jobs/{token}）/ Epic 2（文件下载、DELETE 对话）
FR16: Epic 1 - agent 并发与落盘
FR17: Epic 1 - 超时结束 agent 子进程

## Epic List

### Epic 1: 在隔离环境里提问并查看答案
有权限的后台账号可以在 stag 的 ops 页面提问、追问、离开后回来看结果，失败时重试；agent 从第一次部署起就只拿到白名单 env 和独立只读账号。
**FRs covered:** FR1-7、FR9-13（下载审计除外）、FR15（任务接口）、FR16、FR17；NFR1-7、NFR9、NFR11、NFR12，以及 internal 网络前期验证；UX-DR1-8、UX-DR10-18

### Epic 2: 下载导出文件和到期清理
用户可以下载答案中的文件；对话、导出文件和 Claude 会话记录在 30 天后自动清除。
**FRs covered:** FR8、FR13（下载审计）、FR14、FR15（文件与删除接口）；NFR10；UX-DR9

### Epic 3: 在生产用上
管理员可以在生产安全开启 AI 查数：白名单代理完整落地，生产账号与网络就绪，上线和回退经过验收。依赖 Epic 1、2。
**FRs covered:** NFR8、NFR9（生产侧）及上线要求

## Epic 1: 在隔离环境里提问并查看答案

有权限的后台账号可以在 stag 的 ops 页面提问、追问、离开后回来看结果，失败时重试；agent 从第一次部署起就只拿到白名单 env 和独立只读账号。

### Story 1.1: Agent 执行一条查询并保存结果

作为 ops 后端，
我想要通过内部接口把问题交给 agent 执行，
以便查询在 `petgo-server` 之外运行，结果可以稍后取回。

**覆盖：** FR15（任务接口）、FR16、FR17、NFR1、NFR4

**Acceptance Criteria:**

**Given** `ai-query-agent/` 服务运行且请求带正确的 `X-Internal-Secret`
**When** 调用 `POST /jobs`
**Then** 返回 202，并用 `claude -p` 执行，不加 `--bare` [L0]
**And** 同一 `jobToken` 重复提交不产生第二个任务 [L0]

**Given** 请求带 `resumeSessionId`
**When** 任务执行
**Then** 沿用该 Claude 会话继续回答 [L0]

**Given** 请求缺少密钥或密钥错误
**When** 调用任一接口
**Then** 返回 401 [L0]

**Given** 已有 2 个任务运行且排队 20 个
**When** 再提交任务
**Then** 返回 429 [L0]

**Given** 任务已完成且 agent 重启
**When** 调用 `GET /jobs/{jobToken}`
**Then** 从 `jobs/<token>/result.json` 返回 `status, answerMd, sessionId, files, secs, turns, costUsd, errorCode` [L0]

**Given** 任务未知，或运行中的任务因 agent 重启丢失
**When** 调用 `GET /jobs/{jobToken}`
**Then** 返回 404 [L0]

**Given** 任务超过 10 分钟
**When** 超时触发
**Then** 对应 `claude -p` 子进程被结束，并发名额释放，任务标记失败 [L0]

**Given** agent 处理任意任务
**When** 写日志
**Then** 不包含问题和答案原文 [L0]

**Given** `claude -p` 报额度用尽或授权失效
**When** 任务结束
**Then** `errorCode` 分别为 `AGENT_RATE_LIMITED`、`AGENT_AUTH_FAILED` [L0]

### Story 1.2: Agent 隔离基线和查数说明书

作为系统管理员，
我想要 agent 从第一次部署起就只拿到必要的凭证和数据权限，
以便 stag 试用期间不暴露其他服务的密钥和生产数据。

**覆盖：** NFR2、NFR3、NFR6、NFR7、NFR9（stag 侧）、NFR11、NFR12

**Acceptance Criteria:**

**Given** agent 容器的环境中存在白名单之外的变量
**When** `claude -p` 及其 Bash 子进程运行
**Then** 读不到这些变量，也不存在 Lark 变量 [L0 + L2]

**Given** 订阅 token 需要提供给 `claude -p`
**When** 按实施时的官方文档配置子进程凭证清理
**Then** Bash 子进程读不到 token；做不到时记录原因并停下找 Dai 确认 [L2]

**Given** 新建 `claude_bot_stag`
**When** 用该账号访问数据库
**Then** 只能 SELECT `petgo_stag`，不继承 `ops_readonly`，读不到 `petgo` [L2]
**And** 账号带 `statement_timeout` 30 秒和连接上限 3 [L2]

**Given** 查数说明书迁入 `ai-query-agent/`
**When** 任务以 zh、en、id 运行
**Then** 回答使用对应语言，并在每个答案中给出统计口径 [L0 校验提示词 + L2 实测]
**And** 说明书不再含飞书卡片格式和“一律中文”规则 [L0]

**Given** 准备 internal 网络和白名单代理的方案
**When** 在 stag 做前期验证
**Then** 在 story 记录中写明 `claude -p` 认证和代码拉取需要的域名清单，以及 internal 网络下一次真实查询的结果 [L2]

**Given** `ai-query-agent/` 代码已入库
**When** 按 stag 部署说明构建容器
**Then** Dockerfile 和 stag 部署脚本可用，服务器用 deploy key 拉取代码，不从本机上传 [L0 构建 + L2 部署]
**And** 实际部署等待明确指令 [L2]

**Given** agent 容器
**When** 检查运行配置
**Then** 限制 1 核 2G，不挂 docker socket，日志和代码只读挂载 [L2]

### Story 1.3: 管理员开通后可以进入 AI 查数

作为后台账号，
我想要在获得权限后从导航进入 AI 查数，
以便开始使用；没有权限或功能未开启时看到明确结果。

**覆盖：** FR5（开关提示）、FR11、UX-DR1、UX-DR2、UX-DR3、UX-DR6、UX-DR18（本 story 页面）

**Acceptance Criteria:**

**Given** 账号持有 `ai.query` 或是 `SUPER_ADMIN`
**When** 打开后台
**Then** “概览”下方显示顶层单链接“🤖 AI 查数” [L1]
**And** `AdminPagesRenderSmokeTest` 的侧栏与页面权限比对通过 [L1]

**Given** 账号没有 `ai.query`
**When** 查看导航或直接访问 `/admin/ai-query`
**Then** 导航不显示入口，访问进入现有拒绝页（A3） [L1]

**Given** `ai.query` 已新增
**When** 查看角色预设
**Then** 不属于任何 `AdminRole` 预设，权限分组有三语文案 [L0]
**And** 控制器使用 `@PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('ai.query')")`，导航走现有入口门机制，不手写 `sec:authorize` [L1]

**Given** `AI_QUERY_MODE=off`
**When** 打开页面
**Then** 显示功能未启用，输入框和发送禁用（A2） [L1]

**Given** 功能开启且没有历史对话
**When** 打开页面
**Then** 左栏显示“还没有对话”，右栏显示四类示例问题（A1） [L1]
**And** 点击示例问题只填入输入框，不发送 [L2]

**Given** 页面样式
**When** 检查实现
**Then** 新增样式都在 `admin.css` 的 `aiq-` 前缀下，页面还原度对照 `mockups/A1`、`A2`、`A3` 不低于 95% [L2]

### Story 1.4: 提交问题并在同一对话追问

作为有权限的后台账号，
我想要提交问题并继续追问，
以便提交后立刻离开页面，稍后回来查看。

**覆盖：** FR1、FR2、FR3、FR10、FR12、FR13（提交审计）、NFR5、UX-DR4、UX-DR5、UX-DR18（本 story 页面）

**Acceptance Criteria:**

**Given** 迁移 `V<yyyyMMdd_HHmm>__create_ai_query.sql` 已执行
**When** 检查数据库
**Then** 两张表、索引、CHECK 约束符合规划 §5.1，整数列为 `integer`，`hibernate validate` 通过 [L1]
**And** `check-flyway-versions.sh` 和 `mvn -B clean package` 通过 [L0]

**Given** 账号没有 `ai.query`
**When** 访问本 story 新增的任一路由
**Then** 全部被拒绝 [L1]

**Given** 用户在新对话提交不超过 2000 字的问题
**When** 请求完成
**Then** 只写入 `PENDING` 并立即返回，不同步调用 agent [L1]
**And** 对话和消息使用不可枚举 token [L1]

**Given** 问题为空或超过 2000 字
**When** 提交
**Then** 错误紧贴输入框，已输入内容保留（B2） [L1]

**Given** 用户在新对话选择语言
**When** 查看后续追问
**Then** 语言按对话保存，追问时只读显示；默认跟随后台界面语言 [L1]

**Given** 同一对话有未完成任务
**When** 再次提交
**Then** 请求被拒绝，页面禁用输入框并说明原因 [L1]

**Given** 用户已有 3 个未完成任务
**When** 提交新问题
**Then** 拒绝提交并保留内容（B6） [L1]

**Given** 对话不存在、属于他人（含超管访问）或已过期
**When** 访问或提交
**Then** 统一返回 404（E1） [L1]

**Given** 提交成功
**When** 查看审计记录
**Then** 有 `AI_QUERY_SUBMIT`，摘要只含 token、语言和问题长度 [L1]
**And** 应用日志没有问题原文 [L1]

**Given** 在中文输入法选词时按 Enter
**When** 输入未确认
**Then** 不发送；确认后 Enter 发送，Shift+Enter 换行 [L2]

### Story 1.5: 后台扫描器推进任务

作为有权限的后台账号，
我想要提交的任务在后台持续推进，
以便 ops 重启后不丢结果。

**覆盖：** FR4、FR5、FR6、NFR2

**Acceptance Criteria:**

**Given** 配置项 `AI_QUERY_MODE`、`AI_QUERY_AGENT_URL`、`AI_QUERY_AGENT_SECRET`
**When** 检查配置
**Then** 全部经 env 注入，`.env.example` 只放占位，`AI_QUERY_MODE` 默认 `off` [L0]

**Given** `AI_QUERY_MODE=off`
**When** 扫描器到时
**Then** 不提交、不轮询 [L0]

**Given** 存在 `PENDING` 任务
**When** 扫描器运行（fixedDelay 3 秒）
**Then** 提交给 agent，成功后转 `RUNNING` [L1，agent stub]
**And** agent 返回 429 或不可达时保持 `PENDING`，下轮重试 [L1，agent stub]

**Given** 对话已有 `agent_session_id`
**When** 扫描器提交追问
**Then** 请求带 `resumeSessionId`，完成后保存返回的 `sessionId` [L1，agent stub]

**Given** 存在 `RUNNING` 任务
**When** agent 返回完成或失败
**Then** 写入答案、`files`、`agent_secs`、`agent_turns`、`cost_usd` 并转 `DONE` 或 `FAILED`，记录 `error_code` [L1，agent stub]

**Given** agent 对任务返回 404
**When** 扫描器轮询
**Then** 任务转 `FAILED` [L1，agent stub]

**Given** 任务超过 15 分钟未结束
**When** 扫描器运行
**Then** 转 `TIMEOUT` [L1]

**Given** 同一任务被重复处理
**When** 执行状态更新
**Then** 带旧状态条件的 UPDATE 只成功一次，不重复派发或覆盖结果 [L1]

**Given** 状态机逻辑
**When** 运行单元测试
**Then** 覆盖提交、完成、失败、超时、幂等重复提交和状态条件更新 [L0]

**Given** ops 在任务运行中重启
**When** 启动后扫描器恢复
**Then** 提交幂等，仍能取回结果 [L1，agent stub]

### Story 1.6: 在页面上跟踪查询进度

作为提交了问题的后台账号，
我想要看到每个查询的进度，
以便离开后回来时知道哪些已完成。

**覆盖：** UX-DR10、UX-DR11、UX-DR13、UX-DR14、UX-DR15、UX-DR17、UX-DR18（本 story 页面）

**Acceptance Criteria:**

**Given** 存在未完成任务
**When** 页面打开
**Then** 每 3 秒同时更新左栏状态和右栏内容；全部结束后不再轮询 [L1 + L2]

**Given** 任务排队或查询中
**When** 查看页面
**Then** 显示状态文字和已用时长，提示可以离开，不显示取消按钮（B3、B4） [L1]

**Given** 用户切到其他对话
**When** 原任务仍在运行
**Then** 左栏继续显示进度和计时（B5） [L2]

**Given** 用户离开标签页期间任务完成
**When** 轮询到结果
**Then** 标签页标题显示“(1) 已完成”，回到页面后恢复 [L2]

**Given** 轮询请求失败
**When** 网络中断
**Then** 显示连接中断提示，恢复后自动继续（E2） [L2]

**Given** 对话超过一页
**When** 滚动左栏
**Then** 用现有 `q-more` 加载更多（E3） [L1]

**Given** 状态变化
**When** 使用读屏软件或开启减少动效
**Then** 状态经 `aria-live="polite"` 播报，圆点动效停止，状态仍有文字 [L2]

### Story 1.7: 阅读可信的答案

作为后台账号，
我想要看到结构清楚、带统计口径的答案，
以便直接使用其中的数字。

**覆盖：** FR7、NFR13、UX-DR7、UX-DR8、UX-DR16、UX-DR18（本 story 页面）

**Acceptance Criteria:**

**Given** `marked` 和 `DOMPurify` 以固定版本放入 `/admin/vendor/`
**When** 渲染答案
**Then** 先净化再写入页面，禁用原始 HTML [L1]

**Given** 答案包含 `<script>`、`<img>`、事件属性或 `javascript:` 链接
**When** 渲染
**Then** 不执行、不加载，这些用例直接针对净化配置测试 [L1]
**And** 其余链接带 `rel="noopener noreferrer"` [L1]

**Given** 答案带表格和统计口径
**When** 查看
**Then** 按结论、表格、统计口径、页脚排列，宽表在容器内横向滚动，数字等宽（C1） [L2]

**Given** 对话有多轮
**When** 打开对话
**Then** 定位到最新一轮，多轮之间有分隔线（C3） [L2]

**Given** 用户点击复制或重新查询
**When** 操作完成
**Then** 复制整个答案，或在当前对话新增一轮沿用原问题 [L1]
**And** 重新查询遵守未完成任务限制 [L1]

**Given** 页面实现完成
**When** 对照 `mockups/C1`、`C3`
**Then** 还原度不低于 95% [L2]

### Story 1.8: 失败时知道原因并重试

作为后台账号，
我想要在查询失败时看到原因和下一步，
以便不用重新输入问题。

**覆盖：** FR9、UX-DR12、UX-DR18（本 story 页面）

**Acceptance Criteria:**

**Given** 任务为 `FAILED`、`TIMEOUT` 或 `AGENT_RATE_LIMITED`
**When** 查看对话
**Then** 在原位置显示对应原因和“用原问题重试”（D1～D3） [L1]
**And** 失败用 `{colors.danger}`，超时和额度用尽用 `{colors.warn}` [L2]

**Given** 用户点击重试
**When** 重试提交
**Then** 用原问题创建新一轮 `PENDING`，遵守未完成任务限制 [L1]

**Given** 错误为 `AGENT_AUTH_FAILED`
**When** 查看对话
**Then** 提示联系管理员，不提供重试，输入框禁用（D4） [L1]
**And** 后端记 error 日志，不含问题和答案 [L1]

**Given** 错误提示出现
**When** 使用读屏软件
**Then** 错误经 `role="alert"` 播报 [L2]

### Story 1.9: 在 stag 跑通真实问答

作为管理员，
我想要在 stag 验证完整链路，
以便确认基本功能和隔离后再继续。

**覆盖：** 规划 §9 第 8 项（下载除外）、9、10、11、13 项及第 12 项的 env 部分

**Acceptance Criteria:**

**Given** ops 和 agent 已按明确指令部署到 stag，`AI_QUERY_MODE` 已开启
**When** 分别用中文、英文、印尼语提问并追问
**Then** 都得到带统计口径的对应语言回答 [L2]

**Given** 查询进行中
**When** 重启 `petgo-server-stag`
**Then** 启动后仍拿到结果 [L2]

**Given** 查询进行中
**When** 重启 agent
**Then** 任务转失败，页面可重试 [L2]

**Given** 同时发起 3 个以上查询
**When** 观察排队和数据库
**Then** 排队正常，库连接不超过上限 [L2]

**Given** stag agent
**When** 尝试读取生产库和生产日志
**Then** 全部失败，且 Bash 读不到 Lark secret [L2]

**Given** 模拟 `AI_QUERY_MODE` 关闭
**When** 重启后打开页面
**Then** 显示未启用，后台其他功能不受影响 [L2]

## Epic 2: 下载导出文件和到期清理

用户可以下载答案中的文件；对话、导出文件和 Claude 会话记录在 30 天后自动清除。

### Story 2.1: 下载答案里的导出文件

作为后台账号，
我想要下载答案附带的文件，
以便把明细转发给同事。

**覆盖：** FR8、FR13（下载审计）、FR15（文件接口）、NFR10（目录隔离）、UX-DR9

**Acceptance Criteria:**

**Given** 任务生成了文件
**When** 查看答案
**Then** 文件卡显示文件名、大小和下载（C2） [L1]
**And** 文件按对话写入独立目录，历史文件不会被重发 [L0]

**Given** 用户下载本人对话的文件
**When** 请求完成
**Then** ops 校验归属后从 agent 取回文件 [L1，agent stub]
**And** 写入 `AI_QUERY_DOWNLOAD`，摘要只含 token 和文件名 [L1]

**Given** 文件属于他人，或文件名含路径穿越
**When** 请求下载
**Then** 分别返回 404 和拒绝，且 agent 的 `name` 只允许匹配该任务文件清单 [L0 + L1]

**Given** 文件已被清除
**When** 查看或下载
**Then** 显示“已清除”和重新导出提示（C4） [L1]

**Given** 已按明确指令部署到 stag
**When** 提问并要求导出文件
**Then** 可以下载到正确文件 [L2]

**Given** 页面实现完成
**When** 对照 `mockups/C2`、`C4`
**Then** 还原度不低于 95% [L2]

### Story 2.2: 对话和文件 30 天后自动清除

作为系统管理员，
我想要到期数据自动清除，
以便查询结果不会长期留存。

**覆盖：** FR14、FR15（删除接口）、NFR10（会话记录清理）

**Acceptance Criteria:**

**Given** 对话最后更新超过 `AI_QUERY_RETENTION_DAYS`（默认 30）
**When** 每日任务运行
**Then** 先调 `DELETE /conversations/{token}`，成功后再删库 [L1，agent stub]

**Given** agent 不可达
**When** 清理运行
**Then** 跳过该对话，下轮重试，不删库 [L1，agent stub]

**Given** agent 收到删除请求
**When** 执行删除
**Then** 删除该对话的导出目录和 Claude 会话记录，重复调用保持成功 [L0]

**Given** 对话被清除
**When** 查看审计记录
**Then** 审计保留，且不含问题原文 [L1]

## Epic 3: 在生产用上

管理员可以在生产安全开启 AI 查数：白名单代理完整落地，生产账号与网络就绪，上线和回退经过验收。依赖 Epic 1、2。

### Story 3.1: 限制 agent 只能访问白名单地址

作为系统管理员，
我想要 agent 只能访问 Anthropic 和 GitHub，
以便用户内容里的提示注入无法把数据外带。

**覆盖：** NFR8、规划 §9 第 12 项

**Acceptance Criteria:**

**Given** stag agent 位于 Docker internal 网络
**When** Bash 访问白名单外地址
**Then** 访问失败 [L2]

**Given** 白名单代理已按 Story 1.2 确认的域名配置
**When** 运行 `claude -p` 和拉取代码
**Then** 认证、查询和拉取正常 [L2]

**Given** stag agent 的网络配置
**When** 执行 `docker inspect`
**Then** 只接入 stag 的 internal 网络，没有接入生产网络 [L2]

### Story 3.2: 准备生产 agent 和账号

作为系统管理员，
我想要生产 agent 与 stag 隔离并默认关闭，
以便上线时可控。

**覆盖：** NFR3、NFR6、NFR9（生产侧）

**Acceptance Criteria:**

**Given** 生产接入 internal 网络属于基建变更
**When** 准备执行 `docker network connect`
**Then** 先经 Dai 确认再操作 [L2]

**Given** 生产容器 `ai-query-agent`
**When** 启动
**Then** 使用独立 env 文件（权限 600）、独立网络，默认库为 `petgo`，服务器用 deploy key 拉取代码 [L2]

**Given** 后端以 `AI_QUERY_MODE=off` 发布
**When** 打开后台
**Then** 显示未启用，后台其他功能不受影响 [L2]

**Given** 生产账号 `claude_bot`
**When** 尝试写入或超过连接上限
**Then** 被拒绝 [L2]

**Given** Lark 入口只暂停
**When** 完成生产准备
**Then** `claude-bot` 保持停止，飞书应用、凭证和历史数据不变 [L2]

### Story 3.3: 开启生产并验证回退

作为管理员，
我想要先小范围试用并确认可以回退，
以便放心给其他人开通。

**覆盖：** 上线要求、规划 §9 第 14 项

**Acceptance Criteria:**

**Given** ops 和 agent 已同批部署，`AI_QUERY_MODE` 由明确指令开启
**When** 给 Dai 开通 `ai.query` 试跑
**Then** 中文、英文、印尼语提问和文件下载正常 [L2]

**Given** 历史高频问题
**When** 比对新旧回答
**Then** 数字和口径一致，或差异有说明 [L2]

**Given** 需要回退
**When** 设置 `AI_QUERY_MODE=off` 并停止 agent
**Then** 页面显示未启用，后台其他功能不受影响 [L2]

**Given** 试跑通过
**When** 管理员按需授权
**Then** 被授权账号可以使用，未授权账号看不到入口 [L2]
