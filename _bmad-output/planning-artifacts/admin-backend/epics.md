---
stepsCompleted: [1, 2, 3, 4]
status: 'complete'
completedAt: '2026-06-29'
inputDocuments:
  - _bmad-output/planning-artifacts/admin-backend/PRD.md
  - _bmad-output/planning-artifacts/admin-backend/architecture.md
---

# TailTopia 管理后台 V1.0.0 - Epic Breakdown

## Overview

本文档将管理后台 PRD（AB- 前缀需求）与架构决策拆解为可实现的史诗与故事，供 Developer agent 执行。**brownfield**：复用既有 `com.tailtopia.admin` Thymeleaf slice + 共享 Spring Boot 单体；产物范围独立于 App 端（App 的 epics.md 在 planning-artifacts 根目录，互不干扰）。

## Requirements Inventory

### Functional Requirements

FR(AB-0A): 后台账号登录 —— Lark OAuth 主入口 + 超管用户名/密码紧急备用入口 + Lark 邮箱白名单门控；命中白名单按权限进入、未命中拒绝；离职移除白名单即时撤权（已有 Session 下次请求失效）；Session 闲置 8h 过期；公网 URL 直达无需 VPN。
FR(AB-0B): 会话审计记录 —— 每条认证会话记日志（账号身份/登录时间/登出或过期时间/IP）；只可追加不可删除；仅超管可查看。
FR(AB-1A): 后台账号管理 —— 两级权限（超管≤5，首个初始化预置；普通账号由超管创建并分配模块级权限）；停用即时撤销 Session、可重新激活；账号不可永久删除只能停用。
FR(AB-1B): 操作审计日志 —— 所有模块写操作记日志（时间/操作人/操作类型/目标实体/变更摘要）；只可追加不可删；**限定权限可查**（仅持 `admin.view_logs` 权限的账号，由超管分配；无此权限不可见）、按日期/操作人/类型筛选；永久保留。〔需求修正 2026-06-29：原 PRD「全员可查」改为权限受限，待回填 PRD AB-1B〕
FR(AB-2A): 兽医账号列表 —— 展示姓名/邮箱/账号状态/资质状态/在线快照/评分均分/创建日期；按账号状态、资质状态、在线状态筛选；按姓名/邮箱搜索。
FR(AB-2B): 创建兽医账号 —— 必填显示名/登录邮箱/联系手机号/初始密码；凭证人工交付（无自动发送）；新账号立即可登录但须资质通过方可接单。
FR(AB-2C): 编辑兽医账号 —— 可编辑显示名/登录邮箱/联系手机号；密码单独重置；不中断进行中会话。
FR(AB-2D): 重置兽医密码 —— 生成新临时密码，界面仅显示一次，人工告知。
FR(AB-2E): 封禁/解封兽医账号 —— 封禁即时失去登录权；进行中问诊 **V1.0.0：强制关闭 + 用户侧收到系统消息 + 用户可选择「重新匹配一位兽医继续」或「结束」**（免费阶段无退款）；付费退款分支 📦1.1.0；解封恢复；弹窗确认+操作记录。〔需求修正 2026-06-29：V1.0.0 免费阶段新增用户重新匹配选项，待回填 PRD AB-2E〕
FR(AB-2F): 兽医在线状态查询 —— 只读快照视图，手动刷新获取，顶部显示最后查询时间；无写操作。
FR(AB-2G): 问诊请求未成功记录 —— 记录用户取消/超时无人接单/系统故障三类未成功请求（请求ID/用户ID/提交时间/取消时间/原因/在线兽医数）；可备注并标记已跟进；系统故障类视觉预警+强制跟进；已跟进归档仍可搜。
FR(AB-2H): 兽医资质管理 —— SIPDH 等印尼证件字段；状态机(待完善/审核中/已认证/已驳回/即将到期/已过期)；运营直录(直接已认证)或兽医自传(审核中→通过/驳回)两路径；仅已认证/即将到期可接单；SIPDH 到期自动阻断+30天预警。
FR(AB-UA-01): 用户搜索与详情查看 —— 按用户ID/注册邮箱搜索；详情展示基本信息/宠物档案/历史问诊(只读元数据)/发布内容/账号状态；仅只读不可编辑资料。
FR(AB-UA-02): 停用用户账号 —— 即时不可登录+进行中会话强关+App 停用提示文案；必填原因+操作记录；可重新激活。
FR(AB-UA-03): 删除用户账号 —— 不可逆+二次确认+必填备注+删除类型选择；用户申请注销(D1 内容匿名化保留+档案/名片删除+问诊匿名化) / 违规处置(D2 内容同步下架+档案/名片删除+问诊匿名化)；永久记录。
FR(AB-3A): 用户举报队列 —— 举报按时间入队(举报人/内容预览/举报类型/作者)；可下架(移除+通知作者)或保留(驳回)；统一向举报人发模糊通知；批量下架/驳回；处理后入已处理队列记录。
FR(AB-3B): 全量内容管理 —— 浏览全平台已发布内容(三类)；按类型/作者/时间/状态筛选+正文全文搜索；授权者可主动下架(必填原因)/恢复，均记录日志。
FR(AB-3C): 人工审核队列(预建未激活) —— 超管开关激活；激活后未过自动审核内容入队(用户侧改为"审核中"挂起)；通过/拒绝/3天超时自动丢弃；24h 未处理高亮；激活前自动审核拦截直接发布失败(沿用 FR-12)。
~~FR(AB-3D): 种子内容账号管理~~ —— **已移除（决策 2026-06-29）**：所有内容（含官方/种子账号发布）一律经三方自动审查，无豁免；不引入 `users.is_seed_account` 列，不提供标记 UI。待回填 PRD §4.4 AB-3D / §6 MVP 范围。
FR(AB-4A): 异常工单队列 —— 触发=会话中兽医被封禁，系统自动生成工单；展示元数据(会话ID/用户/兽医/起止时间/状态/异常类型)；不可读 IM 正文/AI 上下文；可加内部备注、标记已处理(可传处理图)、归档仍可搜；不可删。
FR(AB-4B): 问诊会话查询 —— 按用户ID/兽医ID/日期范围查询；展示会话元数据+评分；不展示消息内容/AI 结果/用户媒体。
FR(AB-6A): 兽医评分总览 —— 列所有兽医均分(1-5)/已评/未评数；按均分、问诊量排序；日期范围筛选。
FR(AB-6B): 单个兽医评分详情 —— 已评问诊完整历史(日期/评分/文字)；未评问诊单列标原因；纯只读。

### NonFunctional Requirements

NFR1: 平台 —— 桌面端 Web only，无移动端响应式。
NFR2: i18n —— UI 中英双语(zh-CN + en)，跟随偏好切换；语言集独立于 App(App 为 id/en)。〔需求修正：原 PRD §5「仅中文」改为双语，待回填 PRD §5/§9〕
NFR3: 认证安全 —— 独立认证体系(与 App Google/兽医账密三套隔离)；Lark OAuth + 超管账密紧急备用；无 TOTP；公网 URL 无需 VPN(靠 CF 前置+认证+白名单)。
NFR4: 审计/合规 —— 操作日志与会话日志只可追加、不可经任何 UI 删除、永久保留；删除用户走匿名化(PDP，对齐 App 注销 D1/D2)。
NFR5: 数据边界 —— 问诊消息正文/AI 上下文/用户媒体储存于第三方(腾讯 IM)，后台 V1.0.0 不读取，仅展示系统内元数据。
NFR6: SLA —— 举报 48h 处理(AM-1)、异常工单 48h(AM-2)、运营 1 天上手(AM-5)。
NFR7: 敏感数据 —— 兽医资质证件(KTP/SIPDH/学位证)高敏 PII，私密桶 + 短签名 URL + EXIF 剥离 + 日志不落。
NFR8: 反向指标 —— 操作日志完整性零漏记录(AM-C1)；权限越权零事故(AM-C2)。

### Additional Requirements

- brownfield：复用既有 `com.tailtopia.admin` Thymeleaf slice（种子内容/举报/兽医CRUD评分封禁已建）+ 共享 Spring Boot 单体；**非新脚手架**，无独立 Epic-1-Story-1 脚手架故事。
- **实现第 1 步（AG-1）**：建专用 `admin_accounts` 表 + 权限模型，迁移现有 `AdminBootstrap`/紧急账密，重构 Story 3.1 的 users-role=ADMIN 认证指向新表（旧 V7 列保留停用）。
- Flyway 新序号从 **V30** 起单调顺延（决策 E2，勿照搬示例号）。
- Lark OAuth 非标准 OIDC：自定义 `ClientRegistration` + 自定义 token/userinfo 或轻量手写流；校验企业租户 + email_verified。
- 安全体系：服务端 HttpSession 即时撤权(A1) + 逐端点 permission authority 门控(A5) + 审计哈希链 append-only(A6) + CF Access 前置 admin.tailtopia.id(A4) + 紧急账密强限流/锁定/告警全体超管(A3)。
- 前端：Thymeleaf SSR + HTMX 2.0.9(本地托管) + Spring MessageSource(messages_zh_CN/_en) + Cookie LocaleResolver；模板硬编码中文外化为 `#{admin.*}`。
- 定时任务：`@Scheduled` 日扫 SIPDH 到期(切已过期停接单+预警)/30天预警/人工审核 3 天超时丢弃；`@Async`+DB 去重；禁中间件(F5)。
- 跨模块编排：封禁→既有会话中断+`ConsultAnomalyRaisedEvent`→工单；删除用户→既有级联 D1/D2；下架→既有下架+notify 通知作者；只经既有 service/事件，禁跨 repo。
- 资质证件图复用 `shared/media` 私密桶 + STS + 签名 URL + EXIF 剥离。

### UX Design Requirements

（无独立 UX 文档；管理后台前端形态与交互规则见架构 §Frontend Architecture / §Implementation Patterns：SSR+HTMX、桌面 only、中英双语、layout fragment 复用、筛选/批量走 HTMX 片段。）

### FR Coverage Map

AB-0A: Epic 1 — 后台账号登录（Lark OAuth + 紧急账密 + 白名单）
AB-0B: Epic 1 — 会话审计记录
AB-1A: Epic 1 — 后台账号管理（两级权限）
AB-1B: Epic 1 — 操作审计日志
AB-2A: Epic 2 — 兽医账号列表
AB-2B: Epic 2 — 创建兽医账号
AB-2C: Epic 2 — 编辑兽医账号
AB-2D: Epic 2 — 重置兽医密码
AB-2E: Epic 2 — 封禁/解封兽医账号
AB-2F: Epic 2 — 兽医在线状态查询
AB-2G: Epic 2 — 问诊请求未成功记录
AB-2H: Epic 2 — 兽医资质管理
AB-UA-01: Epic 3 — 用户搜索与详情查看
AB-UA-02: Epic 3 — 停用用户账号
AB-UA-03: Epic 3 — 删除用户账号（D1/D2）
AB-3A: Epic 4 — 用户举报队列
AB-3B: Epic 4 — 全量内容管理
AB-3C: Epic 4 — 人工审核队列（预建未激活）
AB-4A: Epic 5 — 异常工单队列
AB-4B: Epic 5 — 问诊会话查询
AB-6A: Epic 6 — 兽医评分总览
AB-6B: Epic 6 — 单个兽医评分详情

## Epic List

### Epic 1: 后台地基 — 账号、权限、登录与审计
运营团队能用 Lark 登录后台、超管能管理后台账号并分配模块权限、所有写操作与会话留下不可篡改审计。一切后台操作的前置；含 brownfield 认证重构（AG-1：users-role=ADMIN → 专用 `admin_accounts`）、Lark OAuth、CF Access、审计哈希链、双语 i18n 基建（含登录页/布局外化）。
**FRs covered:** AB-0A, AB-0B, AB-1A, AB-1B

### Epic 2: 兽医账号治理
运营能全生命周期管理兽医账号——列表筛选、增删改、重置密码、封禁解封、查在线快照、看未成功问诊请求、审核资质（SIPDH 状态机 + 到期阻断）。封禁动作发 `ConsultAnomalyRaisedEvent` 供 Epic 5 消费（自身封禁功能完整，不反向依赖 Epic 5）。
**FRs covered:** AB-2A, AB-2B, AB-2C, AB-2D, AB-2E, AB-2F, AB-2G, AB-2H

### Epic 3: 用户账号治理
客服/运营能搜索查看用户、停用/重新激活、按注销(D1)或违规(D2)删除（匿名化合规，对齐 App 注销级联）。
**FRs covered:** AB-UA-01, AB-UA-02, AB-UA-03

### Epic 4: 内容审核
运营能处理举报队列、全量浏览 + 主动下架/恢复；并预建（V1.0.0 未激活）人工审核队列 + 超管开关。**所有内容一律经三方自动审查，无种子豁免。**
**FRs covered:** AB-3A, AB-3B, AB-3C

### Epic 5: 问诊异常与会话查询
运营能处理因兽医封禁产生的异常工单（仅元数据 + 内部备注 + 处理图 + 归档）、按用户/兽医/日期查会话元数据。异常工单数据由 Epic 2 封禁事件喂入。
**FRs covered:** AB-4A, AB-4B

### Epic 6: 兽医评分质量看板
运营能查评分总览（均分/已评未评/排序/日期筛选）与单兽医评分详情（纯只读，不对 App 公开）。
**FRs covered:** AB-6A, AB-6B

---

> **验证层标注约定**（沿用本仓库 story 惯例）：**L0** 静态/编译/MockMvc 切片（无需真实 DB）· **L1** 集成（需 Docker postgres+redis 真跑门控/审计/迁移）· **L2** 端到端/外部凭证（真实 Lark 应用、CF Access、真机）。管理后台无 Flutter 侧，故事以 L0/L1 为主，Lark OAuth/CF Access 属 L2。Flyway 序号按 sprint-planning 实际执行顺序顺延（指示性 V30+，决策 E2 勿照搬）。

## Epic 1: 后台地基 — 账号、权限、登录与审计

运营团队能用 Lark 登录后台、超管能管理后台账号并分配模块权限、所有写操作与会话留下不可篡改审计。一切后台操作的前置；含 brownfield 认证重构（AG-1）、Lark OAuth、CF Access、审计哈希链、双语 i18n 基建。

### Story 1.1: 后台账号模型与认证重构（admin_accounts + 紧急账密）

As a **超级管理员**,
I want **后台账号迁移到独立的 `admin_accounts` 表并保留用户名/密码紧急登录**,
So that **后台身份与 App 用户/兽医完全隔离，且 Lark 异常时仍有备用入口**。

**Acceptance Criteria:**

**Given** 现有 Story 3.1 用 `users(role=ADMIN)` + V7 password_hash 承载后台账号
**When** 新建 `admin_accounts` 表（lark_email uniq / display_name / account_type SUPER_ADMIN|STAFF / status ACTIVE|DISABLED / password_hash 可空 BCrypt / created_by / 时间戳）并迁移 `AdminBootstrap` 与 `AdminUserDetailsService` 指向新表
**Then** 首个超管由 env 注入在 `admin_accounts` 落库，旧 `users.password_hash` 列保留但停用（不再作登录依据）
**And** 超管可用用户名+密码经紧急入口登录，普通/兽医/未设密码账号一律不可登录后台（L1）

**Given** 紧急账密为单因子高敏入口
**When** 连续登录失败
**Then** 触发限流与账号锁定，失败统一提示「账号或密码错误」不区分字段，且每次成功的紧急登录写审计并告警全体超管（L1）

**Given** 凭证不入库护栏
**When** 迁移与 bootstrap 执行
**Then** 迁移脚本不含任何明文/哈希，密码仅来自 env 注入（L0 迁移审查 + L1 验证）

### Story 1.2: Lark OAuth 登录与邮箱白名单门控

As a **运营成员**,
I want **用 Lark 账号一键登录后台**,
So that **团队全员复用 Lark 身份，无需额外密码**。

**Acceptance Criteria:**

**Given** 后台登录页
**When** 点击「用 Lark 登录」
**Then** 跳转 Lark OAuth 授权页（state + PKCE + redirect_uri allowlist），回调校验企业租户 + email_verified（L2 真实 Lark 应用）

**Given** 回调返回 Lark 邮箱
**When** 比对 `admin_accounts.lark_email` 且 `status=ACTIVE`
**Then** 命中 → 按 account_type/权限建会话进入后台；未命中或停用 → 拒绝并提示「你的账号无访问权限，请联系管理员」（L1）

**Given** 已登录会话
**When** 该账号被移除白名单/停用，或闲置超过 8h
**Then** 下次请求即失效跳登录页并提示「会话已过期」（服务端会话每请求校验 status+白名单，A1）（L1）

**Given** 后台经公网子域 `admin.tailtopia.id` 访问
**When** 部署
**Then** 前置 Cloudflare + CF Access（Zero Trust）SSO 门，源站仅放行 CF 回源（L2 边缘配置）

### Story 1.3: 操作审计日志（哈希链 append-only）与查询

As a **后台账号**,
I want **所有写操作被不可篡改地记录并可筛查**,
So that **每个运营决策可追溯（AM-C1 零漏记录）**。

**Acceptance Criteria:**

**Given** 任一后台写操作
**When** 操作提交
**Then** 同事务写一条 `admin_audit_logs`（actor/action_type UPPER_SNAKE/target_type/target_id/summary/created_at/prev_hash/row_hash），经统一 `AdminAuditService.record(...)`（L0 切片 + L1）

**Given** 审计需不可篡改
**When** 应用尝试 UPDATE/DELETE 审计行
**Then** 被拒（应用 DB 角色无 UPDATE/DELETE 权或触发器阻断）；行间哈希链可校验篡改（L1）

**Given** 审计日志查询页
**When** 持 `admin.view_logs` 权限的账号按日期/操作人/操作类型筛选
**Then** 返回匹配记录（HTMX 片段刷新），永久保留不设年限；无此权限的账号访问审计页返回 403（L0 门控 + L1）

### Story 1.4: 会话审计记录

As a **超级管理员**,
I want **查看所有登录会话的审计**,
So that **能发现异常登录（含紧急账密使用）**。

**Acceptance Criteria:**

**Given** 任一认证会话（Lark 或紧急账密）
**When** 登录/登出/过期
**Then** 写 `admin_session_logs`（account_id/login_at/logout_at/ip），只可追加不可删（L1）

**Given** 会话审计页
**When** 普通后台账号访问
**Then** 403 越权；仅超管可查看（L0 门控 + L1）

### Story 1.5: 后台账号管理 UI（两级权限与生命周期）

As a **超级管理员**,
I want **创建普通后台账号、分配模块权限、停用/重新激活账号**,
So that **按岗位最小授权且离职即时撤权（AM-C2 零越权）**。

**Acceptance Criteria:**

**Given** 超管在账号管理页
**When** 填写成员 Lark 邮箱 + 勾选模块权限（permission_code，如 vet.view/content.takedown）创建普通账号
**Then** 该邮箱加入白名单、按权限生效，成员下次 Lark 登录即可访问；未授权模块不可见（L1）

**Given** 超管上限 5
**When** 创建第 6 个 SUPER_ADMIN
**Then** 应用层拒绝（L0/L1）

**Given** 任一后台账号
**When** 超管停用
**Then** 即时撤销其所有会话、账号保留不可登录；可重新激活恢复；账号不可永久删除；操作写审计（L1）

**Given** 授权为服务端权威
**When** 普通账号直接请求未授权模块端点
**Then** `@PreAuthorize` 方法级拒绝 403（前端隐藏仅体验，A5）（L0 切片）

### Story 1.6: 双语 i18n 基建 + admin 设计令牌与既有模板统一

As a **运营成员**,
I want **后台 UI 中英可切换、且全后台视觉统一带品牌识别**,
So that **中英团队都能顺畅使用，且页面风格一致不漂移**。

> 视觉规范见 `admin-backend/UX_DESIGN.md`（轻量令牌：淡紫品牌强调色 + 中性高密度底，基于 App `colors.dart`）。

**Acceptance Criteria:**

**Given** 后台前端
**When** 引入 Spring MessageSource（messages_zh_CN/_en）+ Cookie LocaleResolver + 顶栏语言切换
**Then** 切换语言后页面文案随之切换并记住偏好；两套键集一一对应（L0/L1）

**Given** 既有模板（login/layout/dashboard 等）硬编码中文
**When** 外化为 `#{admin.*}` 键
**Then** 渲染无硬编码中文残留，缺失键有回退（L0）

**Given** 现有模板各写内联 `<style>`、配色不统一（深蓝/灰）
**When** 抽取单一共享 `static/admin/admin.css`（`:root` 设计令牌 + 组件类 .btn/.badge/.filter-bar/表格）并把既有模板改引用类
**Then** 全后台统一为「淡紫品牌强调色（`#845EC9` 等，取自 App colors.dart）+ 中性高密度底」，无内联样式残留；后续新页面一律复用这些令牌/类（L0 视觉走查 + L1）

**Given** 后台是桌面内部工具（与 App 俏皮调性不同）
**When** 落地令牌
**Then** 不引入 App 的吉祥物/立体按钮/活泼动效/大圆角米白底；圆角克制、表格密集、状态色清晰（L0）

## Epic 2: 兽医账号治理

运营能全生命周期管理兽医账号——列表、增删改、重置密码、封禁解封、在线快照、未成功请求、资质审核。封禁发 `ConsultAnomalyRaisedEvent` 供 Epic 5 消费。

### Story 2.1: 兽医资质数据模型与接单门控

As a **平台**,
I want **兽医资质以状态机建模并据此门控接单**,
So that **仅合法执业（SIPDH 有效）的兽医可接诊**。

**Acceptance Criteria:**

**Given** 资质需建模
**When** 新建 `vet_qualifications`（1:1 兽医：KTP/SIPDH 编号·机构·有效期·证件照 key、学位证、可选职业照/PDHI/专长、status 枚举、reject_reason、时间戳）
**Then** 兽医账号创建后默认资质 `待完善`、不可接单（L1）

**Given** 接单队列门控
**When** 兽医状态非 `已认证`/`即将到期`
**Then** 不得进入接单队列（复用既有 vet 队列读此状态）（L1）

### Story 2.2: 兽医账号列表与搜索筛选

As a **运营成员**,
I want **按多维度筛选/搜索兽医账号列表**,
So that **快速定位目标兽医**。

**Acceptance Criteria:**

**Given** 兽医列表页
**When** 加载
**Then** 展示姓名/邮箱/账号状态/资质状态/在线快照/评分均分/创建日期（L1）

**Given** 列表
**When** 按账号状态(活跃/已封禁)、资质状态(6 态)、在线状态筛选，或按姓名/邮箱搜索
**Then** HTMX 片段返回匹配行（L1）

### Story 2.3: 创建兽医账号

As a **运营成员**,
I want **创建兽医账号并拿到初始凭证**,
So that **人工交付给兽医登录工作台**。

**Acceptance Criteria:**

**Given** 创建表单
**When** 填写显示名/登录邮箱/联系手机号/初始密码提交
**Then** 账号立即可登录但资质 `待完善` 不可接单；初始密码界面只显示一次；操作写审计（L1）

**Given** 邮箱唯一
**When** 邮箱已被占用
**Then** 校验拒绝并提示（L0/L1）

### Story 2.4: 编辑兽医账号与重置密码

As a **运营成员**,
I want **编辑兽医资料并重置其密码**,
So that **维护账号信息且不中断进行中会话**。

**Acceptance Criteria:**

**Given** 编辑页
**When** 修改显示名/登录邮箱/联系手机号
**Then** 保存生效、不中断进行中会话、写审计（L1）

**Given** 重置密码
**When** 生成新临时密码
**Then** 新密码界面只显示一次、不入审计 summary/日志、人工告知（L1）

### Story 2.5: 封禁/解封兽医账号

As a **运营成员**,
I want **封禁或解封兽医账号**,
So that **及时处置违规兽医并按需恢复**。

**Acceptance Criteria:**

**Given** 弹窗确认后封禁
**When** 兽医有进行中问诊（免费阶段 V1.0.0）
**Then** 会话强制关闭、App 向用户发系统消息告知兽医已临时下线本次中断，并提供「重新匹配一位兽医继续」或「结束」选项由用户选择（免费阶段无退款）；同时发 `ConsultAnomalyRaisedEvent`（供 Epic 5 工单）（L1）
**And** 〔跨产品：用户选择「重新匹配」走既有问诊发起/排队路径，需 App 侧配合呈现选项〕

**Given** 封禁
**When** 完成
**Then** 兽医即时失去登录权、列表显示「已封禁」；解封恢复活跃；操作含操作人+时间戳写审计（L1）

### Story 2.6: 兽医在线状态快照查询

As a **运营成员**,
I want **手动刷新查看兽医在线/离线快照**,
So that **判断接诊能力（无需实时连接）**。

**Acceptance Criteria:**

**Given** 在线状态只读视图
**When** 点击「刷新」
**Then** 读既有 Redis 兽医在线态返回快照，顶部显示「最后查询时间」，页面不自动刷新、无写操作（L1）

### Story 2.7: 兽医资质录入与审核

As a **运营成员**,
I want **直接录入或审核兽医上传的资质**,
So that **兽医通过资质后方可接单**。

**Acceptance Criteria:**

**Given** 运营在后台直接录入资质
**When** 填写完整字段提交
**Then** 状态直接切 `已认证`，证件图存 OSS 私密桶（短签名 URL + EXIF 剥离），无需额外审核（L1/L2 媒体）

**Given** 兽医在工作台自传资质（状态 `审核中`）
**When** 运营审核通过/驳回
**Then** 通过→`已认证`；驳回→`已驳回` 且必填原因，兽医下次登录工作台见状态与原因（V1.0.0 无外部通知）（L1）

**Given** 已认证账号资质更新（如 SIPDH 续期）
**When** 更新有效期与新证件照
**Then** 保持 `已认证`；操作写审计（L1）

### Story 2.8: SIPDH 到期自动阻断与 30 天预警

As a **平台**,
I want **每日检查 SIPDH 有效期并自动阻断到期兽医**,
So that **杜绝证件过期仍接诊**。

**Acceptance Criteria:**

**Given** `@Scheduled` 每日扫描已认证兽医 SIPDH 有效期（@Async + DB 去重，禁中间件）
**When** 到期当日
**Then** 账号自动切 `证件已过期`、停止接单、向运营发后台预警（L1）

**Given** 距到期 ≤ 30 天
**When** 扫描
**Then** 账号切 `证件即将到期`（仍可接单）、详情页橙色预警、列表可按「即将到期」筛选（L1）

### Story 2.9: 问诊请求未成功记录

As a **运营成员**,
I want **查看并跟进所有未成功建立会话的问诊请求**,
So that **监控接诊能力并排查系统异常**。

**Acceptance Criteria:**

**Given** 请求未成功（用户取消 / 超时无人接单 / 系统故障）
**When** 事件发生
**Then** 写 `failed_consult_requests`（请求ID/用户ID/提交时间/取消时间/原因分类/取消时在线兽医数）（L1）

**Given** 记录列表
**When** 系统故障类记录
**Then** 自动触发视觉预警、须标记跟进；其他类型跟进可选；已跟进移入归档仍可搜（L1）

## Epic 3: 用户账号治理

客服/运营能搜索查看用户、停用/重新激活、按注销(D1)或违规(D2)删除（匿名化合规）。

### Story 3.1: 用户搜索与详情查看

As a **客服/运营**,
I want **按 ID/邮箱搜索用户并查看只读详情**,
So that **处理投诉与合规时掌握账号全貌**。

**Acceptance Criteria:**

**Given** 用户搜索
**When** 按用户 ID 或注册邮箱查询
**Then** 返回匹配用户，已停用者标注「已停用」（L1）

**Given** 用户详情页
**When** 打开
**Then** 展示基本信息（注册/最后活跃时间）、宠物档案列表、历史问诊（只读元数据，标注「仅元数据」）、发布内容列表、账号状态；仅只读不可编辑资料（L1）

### Story 3.2: 停用与重新激活用户账号

As a **客服/运营**,
I want **停用或重新激活用户账号**,
So that **执行处置并可恢复**。

**Acceptance Criteria:**

**Given** 停用弹窗
**When** 填写必填原因确认
**Then** 账号即时不可登录、进行中会话强关、App 展示停用提示文案（含 WhatsApp/邮箱）、操作含操作人+时间戳写审计（L1）

**Given** 已停用账号
**When** 运营重新激活
**Then** 恢复登录权（L1）

### Story 3.3: 删除用户账号（D1 注销 / D2 违规）

As a **运营**,
I want **按注销或违规两种类型删除用户并匿名化处理内容**,
So that **满足 PDP 合规同时保留审计链**。

**Acceptance Criteria:**

**Given** 删除操作
**When** 执行前
**Then** 二次确认弹窗显示「此操作不可撤销」，须选删除类型 + 填必填备注（L1）

**Given** 用户申请注销（D1）
**When** 删除
**Then** 账号移除不可登录；其内容匿名化保留（头像默认图/昵称「已注销用户」/不触发迷你卡）；宠物档案+H5名片立即删除失效；问诊匿名化保留（L1）

**Given** 违规处置（D2）
**When** 删除
**Then** 账号移除；其内容同步下架（Feed/我的发布/成长档案全移除）；档案+名片删除失效；问诊匿名化（L1）

**Given** 删除完成
**When** 记录
**Then** 写永久不可删记录（操作人/时间/类型/备注）（L1）

## Epic 4: 内容审核

运营能处理举报队列、全量管理+主动下架；预建（未激活）人工审核队列+超管开关。**所有内容一律经三方自动审查，无种子豁免。**

### Story 4.1: 用户举报队列处理

As a **运营成员**,
I want **处理用户举报并批量下架或驳回**,
So that **48h 内维护社区环境（AM-1）**。

**Acceptance Criteria:**

**Given** 举报队列
**When** 加载
**Then** 按举报时间排序展示（举报人/内容预览/举报类型/时间/作者）（L1）

**Given** 一条举报
**When** 选择下架
**Then** 内容从 Feed/我的发布/成长档案同步移除、向作者发通知「因违反社区规范已被移除」；选择保留→举报关闭不处理；无论结果向举报人发统一模糊通知（L1）

**Given** 多选举报
**When** 批量下架/驳回
**Then** 逐条处理+逐条审计+汇总结果回片段；处理后移入已处理队列（L1）

### Story 4.2: 全量内容管理与主动下架

As a **运营成员**,
I want **浏览全平台内容并主动下架/恢复**,
So that **无举报时也能处置违规内容**。

**Acceptance Criteria:**

**Given** 全量内容页
**When** 按类型/作者/时间/状态(上线中/已下架)筛选或正文全文搜索
**Then** 返回匹配内容（HTMX 片段）（L1）

**Given** 授权运营
**When** 主动下架（必填原因）或恢复已下架内容
**Then** 操作生效并记录日志（L1）

### Story 4.3: 人工审核队列预建（未激活）

As a **超级管理员**,
I want **预建人工审核队列并以开关控制激活**,
So that **招到人手后一键开启而无需改架构**。

**Acceptance Criteria:**

**Given** 新建 `manual_review_queue` + `admin_settings` 开关（默认关）
**When** V1.0.0 上线
**Then** 队列不在 UI 展示，自动审核拦截内容直接返回发布失败（沿用 FR-12）（L1）

**Given** 超管激活开关
**When** 未过自动审核的内容提交
**Then** 入队挂起、用户侧显示「内容审核中，通常 3 天内完成」；运营可通过(发布+通知)/拒绝(丢弃+通知)；超 3 天自动丢弃并通知；超 24h 未处理高亮（L1）

## Epic 5: 问诊异常与会话查询

运营能处理因兽医封禁产生的异常工单、查会话元数据。

### Story 5.1: 异常工单队列

As a **运营成员**,
I want **处理因兽医封禁产生的异常工单**,
So that **48h 内处置受影响问诊（AM-2）**。

**Acceptance Criteria:**

**Given** Epic 2 封禁发 `ConsultAnomalyRaisedEvent`
**When** 事件到达
**Then** `ConsultAnomalyService` 订阅落 anomaly 工单（会话ID/用户/兽医/起止时间/状态/异常类型元数据）（L1）

**Given** 工单（模块4 导航或模块1 均可达）
**When** 运营处理
**Then** 可加内部备注、标记已处理（可传处理图）后归档仍可搜；不可读 IM 正文/AI 上下文；工单不可删（L1）

### Story 5.2: 问诊会话元数据查询

As a **授权运营**,
I want **按用户/兽医/日期查询问诊会话元数据**,
So that **排查与审计问诊（不触达消息正文）**。

**Acceptance Criteria:**

**Given** 会话查询页
**When** 按用户 ID/兽医 ID/日期范围查询
**Then** 返回会话元数据（会话ID/用户/兽医/起止时间/状态/评分若有）；不展示消息内容/AI 结果/用户媒体（L1）

## Epic 6: 兽医评分质量看板

运营能查评分总览与单兽医详情（纯只读）。

### Story 6.1: 兽医评分总览

As a **运营成员**,
I want **查看所有兽医评分总览**,
So that **监控整体服务质量**。

**Acceptance Criteria:**

**Given** 评分总览页
**When** 加载
**Then** 列所有兽医均分(1-5)/已评数/未评数（L1）

**Given** 总览
**When** 按均分(升/降)或问诊量排序、按日期范围筛选
**Then** 返回对应结果（L1）

### Story 6.2: 单个兽医评分详情

As a **运营成员**,
I want **查看单个兽医的评分详情**,
So that **深入分析其服务表现**。

**Acceptance Criteria:**

**Given** 单兽医评分详情页
**When** 打开
**Then** 展示已评问诊完整历史（日期/1-5星/文字评价）；未评问诊单列标原因（用户未评/30分钟超时自动关闭）；纯只读无操作（L1）
