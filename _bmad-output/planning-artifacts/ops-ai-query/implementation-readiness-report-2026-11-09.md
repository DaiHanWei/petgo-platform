---
stepsCompleted: [1, 2, 3, 4, 5, 6]
filesIncluded:
  - _bmad-output/implementation-artifacts/specs/spec-ops-ai-query.md
  - _bmad-output/planning-artifacts/ops-ai-query/epics.md
  - _bmad-output/planning-artifacts/ops-ai-query/ux-designs/ux-petgo-platform-2026-11-09/DESIGN.md
  - _bmad-output/planning-artifacts/ops-ai-query/ux-designs/ux-petgo-platform-2026-11-09/EXPERIENCE.md
---

# Implementation Readiness Assessment Report

**Date:** 2026-11-09
**Project:** ops AI 查数（主题 ops-ai-query，不归属版本）

## 文档清单

| 类型 | 文件 |
|---|---|
| PRD 与架构 | `spec-ops-ai-query.md`（同时承担 PRD 与架构） |
| Epics | `epics.md` |
| UX | `DESIGN.md`、`EXPERIENCE.md` |

无重复文档，无缺失文档。

## PRD Analysis

规划文档未给需求编号，下列编号来自 `epics.md` 的需求提取，并已对照规划全文。

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

共 17 条。

### Non-Functional Requirements

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

共 13 条。

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

### PRD Completeness Assessment

规划完整，目标、反目标、状态机、接口、数据模型、安全要求和验收都已写明。需要在后续步骤关注的是规划中有明确要求、但未进入验收的细节。

## Epic Coverage Validation

### Coverage Matrix

| FR | 需求 | 覆盖 | 状态 |
|---|---|---|---|
| FR1 | 提问 | Story 1.4 | ✓ |
| FR2 | 追问沿用会话 | Story 1.1、1.4、1.5 | ✓ |
| FR3 | 提交后可离开 | Story 1.4 | ✓ |
| FR4 | 状态机 | Story 1.5 | ✓ |
| FR5 | 扫描器和开关 | Story 1.3、1.5 | ✓ |
| FR6 | 重启与幂等 | Story 1.1、1.5 | ✓ |
| FR7 | 净化渲染与口径 | Story 1.2、1.7 | ✓ |
| FR8 | 文件下载 | Story 2.1 | ✓ |
| FR9 | 失败分类与重试 | Story 1.1、1.8 | ✓ |
| FR10 | 未完成任务限制 | Story 1.4 | ✓ |
| FR11 | 权限码 | Story 1.3、1.4 | ✓ |
| FR12 | 对话归属 | Story 1.4 | ✓ |
| FR13 | 审计 | Story 1.4、2.1 | ✓ |
| FR14 | 30 天清理 | Story 2.2 | ✓ |
| FR15 | agent 接口 | Story 1.1、2.1、2.2 | ✓ |
| FR16 | agent 并发与落盘 | Story 1.1 | ✓ |
| FR17 | 超时结束子进程 | Story 1.1 | ✓ |

### Missing Requirements

没有未覆盖的 FR。规划中已有、但验收未明确写出的细节：

- 规划 §5.6 的 4 个配置项和 `.env.example` 占位。
- 规划 §5.1 的 `agent_secs`、`agent_turns`、`cost_usd` 需要由扫描器写入。
- 规划 §5.2 的 `@PreAuthorize` 表达式和导航入口门登记。

### Coverage Statistics

- Total PRD FRs: 17
- FRs covered in epics: 17
- Coverage percentage: 100%

## UX Alignment Assessment

### UX Document Status

已找到：`DESIGN.md`、`EXPERIENCE.md`、20 张单页稿。

### Alignment Issues

- `EXPERIENCE.md` 定义了对话深链路由 `/admin/ai-query/{conversationToken}`，规划只写了 `/admin/ai-query`，需要补入规划。
- “重新查询”和“复制”只在 UX 与 Story 1.7 出现，规划未写；范围已确认，不影响实现。

### Warnings

- 工作台左栏 360px、右栏最小 720px，加侧栏和内边距约需 1370px，在 1280～1366 宽的笔记本上会轻微横向溢出。这是现有工作台的行为，实现时需确认。
- 单页稿的样式来自 `dev_1.4.2`，当前分支基于 `main`，实现时需以 `main` 的 `admin.css` 为准。

## Epic Quality Review

### 🔴 Critical Violations

无。

### 🟠 Major Issues（已修复）

- 没有 story 负责 agent 的 Dockerfile 和 stag 部署脚本，已加入 Story 1.2。
- Story 1.2 的网络前期验证没有可衡量的结果，已改为记录域名清单和一次真实查询结果。

### 🟡 Minor Concerns

已修复：`.env.example` 与配置项（1.5）、结果字段落库（1.5）、`@PreAuthorize` 与导航入口门（1.3）、Story 3.1 不可测试的验收、规划补充对话深链路由。

保留：
- Epic 1、2 的价值在 stag，生产价值由 Epic 3 兑现。这是已确认的风险边界。
- Story 1.1、1.2、1.5 偏技术，但都有可验收的结果，且是后续页面的前提。
- Story 1.4 验收较多（10 条），开发时如超出单次范围可再拆。

### 依赖与时机

没有向后依赖；两张表在 Story 1.4 首次需要时创建；现有系统集成点（权限、审计、导航、工作台）都已写入验收。

## Summary and Recommendations

### Overall Readiness Status

READY

### Critical Issues Requiring Immediate Action

无。

### Recommended Next Steps

1. 运行 sprint planning，生成该主题的执行顺序文件。
2. 从 Story 1.1 开始创建 story 文件。
3. 实现 Story 1.2 时，优先验证订阅 token 能否对 Bash 子进程隔离；做不到时找 Dai 确认。
4. 实现页面时以 `main` 的 `admin.css` 为准，并确认 1280～1366 宽屏幕下的工作台显示。

### Final Note

本次评估发现 7 项问题，已全部修复；另有 5 项风险或取舍已记录，不阻塞开发。

评估人：Claude（2026-11-09）
