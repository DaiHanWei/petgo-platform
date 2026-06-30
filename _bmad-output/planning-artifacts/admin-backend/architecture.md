---
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
lastStep: 8
status: 'complete'
completedAt: '2026-06-29'
inputDocuments:
  - _bmad-output/planning-artifacts/admin-backend/PRD.md
  - _bmad-output/planning-artifacts/architecture.md
  - _bmad-output/planning-artifacts/PRD.md
  - _bmad-output/planning-artifacts/TECH_FRAMEWORK.md
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md
workflowType: 'architecture'
project_name: 'TailTopia 管理后台 V1.0.0'
user_name: 'Dai'
date: '2026-06-29'
---

# TailTopia 管理后台 V1.0.0 架构决策文档（Architecture Decision Document）

_本文档通过逐步协作发现的方式构建。每完成一项架构决策，对应章节将被追加进来。本文档唯一权威范围来自管理后台 PRD（`admin-backend/PRD.md`，AB- 前缀需求）；技术基线复用 App+共享后端架构（`planning-artifacts/architecture.md`），在本工作流中针对「管理后台」这一新增产品面细化为正式架构。_

## Project Context Analysis

### V1.0.0 管理后台架构姿态（总纲）
内部运营中枢，使用者全为内部成员（超管≤5 + 普通后台账号）；非面向消费者、低并发、低频。延续 App「快速上线、≤500 DAU、不过度设计、减面」姿态：管理后台是对既有 Spring Boot 模块化单体的**扩展（新增 admin 域）**，而非新建系统；最大化复用既有各模块 service / 领域事件 / 媒体基建 / 部署管线，禁止为后台单独引入 MQ/缓存层/调度中间件。

### Requirements Overview
**Functional Requirements（6 模块 + 登录会话层，~21 条 AB- 需求；模块5 客服整体移至 V1.1.0）：**
- 登录/会话（AB-0A/0B）：Lark OAuth 主入口 + 超管账密紧急入口 + Lark 邮箱白名单门控；session 闲置 8h 过期；会话审计（账号/登录/登出过期/IP），仅超管可见、只可追加。
- 模块0 后台账号（AB-1A/1B）：两级权限模型（超管全权 + 普通账号模块级权限）；超管上限 5、首个初始化预置；账号只停用不删除；操作审计日志（时间/操作人/类型/目标/变更摘要）**只可追加、永久保留、全员可查可筛**。
- 模块1 兽医账号（AB-2A~2H）：列表（多维筛选/搜索）、创建（凭证人工交付）、编辑、密码重置（一次性显示）、封禁/解封（进行中会话按免费/付费分支处理）、在线状态**快照**查询（手动刷新、无实时）、问诊请求未成功记录（取消/超时/系统故障三类 + 视觉预警 + 归档）、资质管理（SIPDH 等印尼证件字段 + 状态机：待完善/审核中/已认证/已驳回/即将到期/已过期；运营直录 vs 兽医自传两路径；每日到期检查阻断 + 30 天预警）。
- 模块2 用户账号（AB-UA-01~03）：按 ID/邮箱搜索 + 只读详情聚合（档案/问诊/内容/状态）；停用/重新激活（含进行中会话强关 + 停用文案）；删除（注销=内容匿名化保留 / 违规=内容同步下架；均匿名化问诊与剥离 PII；不可逆 + 二次确认 + 永久记录）。
- 模块3 内容审核（AB-3A~3D）：用户举报队列（主工作流，含批量下架/驳回 + 双向通知）、全量内容浏览与主动下架/恢复（全文搜索 + 多维筛选）、人工审核队列（**预建但 V1.0.0 未激活**，超管开关；激活后改变用户侧发布体验 + 3 天超时丢弃 + 24h 高亮）。（种子账号标记 AB-3D 已于 2026-06-29 移除，所有内容一律经三方自动审查无豁免。）
- 模块4 问诊异常（AB-4A/4B）：异常工单队列（触发=会话中兽医被封禁；仅元数据 + 内部备注 + 处理图片 + 归档，**不读 IM 正文/AI 上下文**）；问诊会话查询（按用户/兽医/日期，仅元数据 + 评分，不读消息）。
- 模块6 兽医评分（AB-6A/6B）：评分总览（均分/已评/未评、排序、日期筛选）+ 单兽医评分详情（含未评分原因），V1.0.0 **纯只读**，不对 App 公开。

**Non-Functional Requirements：**
- 平台：桌面端 Web only，无移动端响应式；UI **中英双语（zh-CN + en）**，跟随用户偏好/浏览器语言切换。
  注意：管理后台语言集（zh + en）与 App（id + en，印尼语+英语）不同，是独立的一套文案资源，不复用 App 的 .arb。
  〔需求修正：原 PRD §5「仅中文、不做多语言」由用户 2026-06-29 改为中英双语；待回填 PRD §5/§9 A-8。〕
- 认证安全：独立认证体系（与 App Google/兽医账密三套隔离）；Lark OAuth + 超管账密紧急备用；无 TOTP（Lark 提供身份安全）；公网 URL 直达、无需 VPN（靠 Cloudflare 前置 + 认证 + 白名单）。
- 审计/合规：操作日志与会话日志**只可追加、不可经任何 UI 删除、永久保留**（反向指标 AM-C1 零漏记录、AM-C2 零越权）；删除用户走**匿名化**（PDP 合规，对齐 App 注销 D1/D2）。
- 数据边界：问诊消息正文/AI 上下文/用户上传媒体储存于第三方（腾讯 IM），后台 V1.0.0 **不实现跨系统读取**，仅展示 TailTopia 系统内元数据。
- SLA：举报 48h 处理（AM-1）、异常工单 48h（AM-2）、运营 1 天上手（AM-5）—— 运营效率指标，非性能压测。
- 敏感数据：兽医资质证件（KTP/SIPDH/学位证）属高敏 PII，须私密桶 + 短签名 URL + EXIF 剥离 + 日志不落。

**Scale & Complexity：**
- Primary domain：内部运营 Web 后台（全栈：既有 Spring Boot 单体 + admin 域扩展 + 新增后台前端 + Lark OAuth）。
- Complexity level：**medium**（功能数中等；技术面收敛；难点在权限/审计/跨模块编排的一致性，而非规模或并发）。
- Estimated architectural components：1 个新增后端 admin 域（复用 6 个既有模块 service）+ 一套后台前端 + 新增 ~7-9 张表 + 1 个新外部依赖（Lark OAuth）。

### Technical Constraints & Dependencies
- **复用既有共享后端基建**：PostgreSQL（单实例）/ Redis（收窄）/ Flyway（**序号冻结、加列走 ALTER，决策 E2**）/ `ddl-auto=validate` / OSS 私密桶 + STS + 签名 URL / Cloudflare 前置 / 德国单机 Docker Compose / GitHub Actions。
- **新增外部依赖**：Lark（飞书）开放平台 OAuth —— 后台账号唯一身份标识（Lark 邮箱）；凭证 env 注入不入库。
- **跨地域**：后端留德国，运营团队访问内部后台属低频、延迟不敏感，无需亚太副本。
- **护栏复用**：异步仅 @Async + DB 状态机（禁 MQ/缓存层/调度中间件 F5）；对外暴露用不可枚举 token；错误统一 RFC 9457 ProblemDetail；日志 JSON 且严禁记录 PII/健康数据/令牌/签名 URL。

### Cross-Cutting Concerns Identified
1. 后台认证与两级授权（Lark OAuth + 超管账密紧急入口 + 邮箱白名单 + 8h session + 模块级权限门控；为后续动态 RBAC 预留扩展位）。
2. 审计日志（操作日志 + 会话日志，append-only、永久、可筛）—— 安全攸关，零漏记录。
3. 跨模块编排（封禁兽医→异常工单；删除用户→内容匿名化/下架 + 名片失效；下架内容→App 通知作者）—— 走既有领域事件/service，禁跨 repo、无新中间件。
4. 定时任务（SIPDH 每日到期检查阻断 + 30 天预警；人工审核 3 天超时丢弃）—— @Scheduled + @Async + DB 去重，禁中间件。
5. 敏感证件媒体（KTP/SIPDH/学位证）—— 私密桶 + 短签名 URL + EXIF 剥离 + 日志脱敏。
6. 删除合规匿名化（注销 D1 / 违规 D2 两策略，对齐 App 级联删除路径）。
7. 中英双语（zh + en）i18n —— 后台前端需内建文案资源与语言切换；桌面端、无响应式。语言集独立于 App（App 为 id/en）。

### 安全攻击面与加固结论（Red/Blue Team 推演）
- **A1 后台认证 = 服务端会话（Redis admin_sessions），非自包含 JWT**：每请求校验白名单命中 + 账号 active，满足「撤权/停用即时失效」；8h 为闲置滑动过期 + 绝对上限。（与 App 无状态 JWT 相反，是后台的关键差异点。）
- **A2 Lark OAuth 硬化**：state + PKCE + redirect_uri allowlist + 校验企业租户 & email_verified；白名单匹配「租户内已验证邮箱」而非裸 email。
- **A3 紧急账密收口**：强限流 + 失败锁定 + 每次紧急登录强制告警全体超管并入审计 + 源 IP allowlist；引导期至少 2 名超管避免找回死锁。
- **A4 Cloudflare Access（Zero Trust）前置后台子域**：保留「公网无 VPN」体验的同时加 SSO/设备门，复用既有 CF 前置。
- **A5 服务端逐端点授权**：每端点声明所需模块权限，Spring Security 方法级强制；前端模块隐藏仅为体验，非安全边界。
- **A6 审计不可篡改**：append-only + 应用 DB 角色无 UPDATE/DELETE 权 + 哈希链（行间链式哈希，篡改可检）；会话日志仅超管可读。

### 前端形态倾向（待 step-03 定）
- 加权对比：Thymeleaf+HTMX/Alpine(4.30) ≳ Thymeleaf 纯 SSR(4.20) > 纯 SPA(3.75)。
- V1 减面姿态 + 服务端会话(A1) + 复用 Spring i18n 倾向「服务端渲染 + HTMX 增强交互」；纯 SPA 在交互/双语领先但运维/同源安全扣分。
- 关键未定变量：团队前端技能（React/Vue 熟手 vs Java/模板为主）→ step-03 拍板。

## Starter Template Evaluation

### Primary Technology Domain
内部运营 Web 后台（全栈）。**非绿地**——既有 `com.tailtopia.admin` Thymeleaf slice 已运行（种子内容 3.1 + 举报 3.7 + 兽医 CRUD/评分/封禁 Epic 5），本架构是在其上扩展为完整 V1.0.0 后台。

### Starter Options Considered
- **延用既有 Thymeleaf slice（✅ 选定）**：复用 Spring Boot 4 / Java 21 / Spring Security（已有独立 `adminFilterChain`：session + 表单登录 + CSRF + `role=ADMIN`）。零新增运维面、与 A1 服务端会话天然契合、复用既有 service 与部署管线。
- **独立 SPA（React/Vue，未选）**：交互/双语略优，但需独立构建/部署/CORS/令牌管理，丢弃既有可用后台，违背 V1 减面（step-02 矩阵 3.75 < 4.30）。
- **重写为其他 SSR（未选）**：无收益、纯返工。

### Selected Starter — 既有 admin slice + 三项增强
**基线（已存在，不重建）：** `com.tailtopia.admin/{web,service}` + `templates/admin/*.html` + `adminFilterChain`（session/form/CSRF/ADMIN）+ `AdminBootstrap` + `V7__add_admin_password.sql`。

**增强项（本架构新增，落 step-04+ 决策与故事）：**
1. **双语 i18n（zh-CN + en）**：引入 Spring `MessageSource` + `messages_zh_CN.properties` / `messages_en.properties` + Cookie/Session `LocaleResolver` + 顶栏语言切换；现有模板硬编码中文逐步外化为 `th:text="#{key}"`。语言集独立于 App（App 为 id/en）。
2. **Lark OAuth 主入口 + 密码降级为紧急入口**：`spring-boot-starter-oauth2-client`，自定义飞书 `ClientRegistration`（**飞书 OAuth 非标准 OIDC**：token 需 app_access_token、user-info 走自有端点，`oauth2Login` 不能完全开箱，需自定义 token/userinfo 处理或一段轻量手写 OAuth 流）；登录成功比对 Lark 邮箱白名单；现有表单密码登录保留为超管紧急入口（底部小字）。
3. **交互承载 = Thymeleaf + HTMX 2.0.x（稳定线，永久支持；不上 4.0 beta）**：PRD 大量列表/筛选/搜索/批量操作，用 HTMX（~14KB、无构建步骤、SSR 原生增强）做局部刷新与批量提交；不引入打包链、不破坏同源会话。

**Initialization（无 init 命令，brownfield 增量）：**
```xml
<!-- petgo-backend/pom.xml 追加 -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-oauth2-client</artifactId>
</dependency>
<!-- HTMX 2.0.9：静态托管 src/main/resources/static/vendor/htmx.min.js（不走 CDN，避免外网依赖与隐私泄漏） -->
```

**Architectural Decisions Provided by Baseline + Enhancements:**
- 语言/运行时：Java 21 / Spring Boot 4 / Maven（与主后端同一产物，单体内 admin 域）。
- 渲染：Thymeleaf SSR + thymeleaf-extras-springsecurity6；HTMX 2.0.x 做交互增强（无 SPA 构建）。
- 认证/会话：独立 `adminFilterChain`，session + CSRF；Lark OAuth 主 + 密码紧急；服务端会话（A1）。
- i18n：Spring MessageSource（zh-CN/en），与 App .arb 隔离。
- 样式：沿用内联/轻量 CSS（无前端框架，减面）；可抽一份共享 admin.css。
- 测试：MockMvc 切片（已有 `AdminWebControllerTest` 范式）+ 关键路径。

**Note:** 这些增强不构成「第一个脚手架 Story」——基线已存在；增强项作为 Epic/Story 排入实现阶段（step-04 后细化）。

## Core Architectural Decisions

> 三处分叉 2026-06-29 定案：F-A 后台账号用**专用 `admin_accounts` 表**（符合 PRD「完全隔离」，需重构 Story 3.1 现有 users-role=ADMIN 认证）；F-B 后台子域 **`admin.tailtopia.id` + Cloudflare Access**；~~F-C 种子标记加 `users.is_seed_account` 列~~ **F-C 已撤销（2026-06-29）：AB-3D 种子账号标记功能移除，不加该列、不提供标记 UI，所有内容经三方自动审查无豁免**。

### Decision Priority Analysis
**Critical（阻塞实现）：** 后台账号存储模型（专用表 vs 复用 users）· Lark OAuth 接入 · 两级权限模型 · 审计不可篡改 · 新表 Flyway 序号（V30 起）。
**Important（显著塑形）：** 后台子域 + CF Access · SSR+HTMX 交互契约 · 跨模块编排复用 · 定时任务 · i18n。
**Deferred：** 动态 RBAC（角色表，后续版本）· 人工审核队列激活（开关已预建）· 付费封禁退款分支（1.1.0）· 客服模块（1.1.0）。

### Data Architecture
- **【F-A 定案】后台账号 = 新建专用 `admin_accounts` 表**（符合 PRD「完全隔离」）：字段 `id / lark_email(uniq，身份兼白名单) / display_name / account_type(SUPER_ADMIN|STAFF) / status(ACTIVE|DISABLED) / password_hash(仅超管紧急入口，BCrypt，可空) / created_by / created_at / updated_at`。超管上限 5 应用层校验；`AdminBootstrap` 与紧急账密迁移到此表（现 V7 在 users 上的 `password_hash` 不删、停用，决策 E2）。
- **权限模型**：join 表 `admin_account_permissions(account_id, permission_code)`；`SUPER_ADMIN` 隐式全权，`STAFF` 按 `permission_code`（附录 B 标识：`vet.view/vet.create/vet.ban/content.takedown/...`）授权。**RBAC-ready**：后续加 `admin_roles` 表即平滑升级，V1.0.0 不建角色表（不过度设计）。
- **审计（A6 不可篡改）**：`admin_audit_logs`（操作日志：`actor_account_id/action_type/target_type/target_id/summary/created_at/prev_hash/row_hash`，行间哈希链）+ `admin_session_logs`（会话审计：`account_id/login_at/logout_at/ip`，仅超管可读）。append-only：应用 DB 角色无 UPDATE/DELETE 权；永久保留。
- **兽医资质** `vet_qualifications`（1:1 兽医，新表）：`vet_account_id(FK) / ktp_no / ktp_photo_key / sipdh_no / sipdh_issuer / sipdh_expiry / sipdh_photo_key / degree_photo_key / profile_photo_key? / pdhi_photo_key? / specialties? / status(待完善|审核中|已认证|已驳回|即将到期|已过期 UPPER_SNAKE) / reject_reason / created_at / updated_at`。仅「已认证/即将到期」可接单（接单门控复用既有 vet 队列逻辑读此状态）。
- **问诊失败请求** `failed_consult_requests`：`request_token / user_id / submitted_at / cancelled_at / cancel_reason(USER_CANCEL|TIMEOUT|SYSTEM_FAILURE) / online_vet_count / followed_up / note / archived_at`。系统故障类视觉预警 + 强制跟进。
- **人工审核队列** `manual_review_queue`（预建未激活）：`content_id / submitted_at / status(PENDING|APPROVED|REJECTED|TIMED_OUT) / decided_by / decided_at` + 全局激活开关（`admin_settings` 单行配置表，超管控）。
- **【F-C 已撤销 2026-06-29】种子账号标记移除**：不引入 `users.is_seed_account` 列、不提供标记 UI；所有内容（含官方/种子账号发布）一律经三方自动审查，无豁免（AB-3D 移出范围）。
- **Flyway 新序号（V30 起单调顺延，实际号实现时定）**：`V30__init_admin_accounts` / `V31__init_admin_permissions` / `V32__init_admin_audit_logs` / `V33__init_vet_qualifications` / `V34__init_failed_consult_requests` / `V35__init_manual_review_queue` / `V36__init_admin_settings`（原 V36 种子标记列随 AB-3D 移除）。

### Authentication & Security
- **Lark OAuth（主入口）**：`spring-boot-starter-oauth2-client` + 自定义飞书 `ClientRegistration`（非标准 OIDC，自定义 token/userinfo 或轻量手写流）；回调取 Lark 邮箱 → 比对 `admin_accounts.lark_email` 且 `status=ACTIVE` → 建会话；校验企业租户 + email_verified（A2）。命中失败 → 拒绝「无访问权限」。
- **紧急入口（仅超管）**：`password_hash` BCrypt；强限流 + 失败锁定 + **每次紧急登录强制告警全体超管并入审计** + 源 IP allowlist；引导期≥2 超管避免找回死锁（A3）。
- **会话（服务端，A1）**：沿用 HttpSession；闲置 8h 滑动 + 绝对上限；**每请求校验账号 `status=ACTIVE` 且仍在白名单** → 撤权/停用即时失效。
- **授权（A5）**：Spring Security 方法级 `@PreAuthorize` 按 permission authority；`SUPER_ADMIN` 隐式全权；前端模块隐藏仅体验、非安全边界。
- **【F-B 定案】边缘**：后台子域 `admin.tailtopia.id` 经 Cloudflare；**前置 Cloudflare Access（Zero Trust）**（A4），保留「公网无 VPN」体验同时加 SSO/设备门。
- **审计不可篡改（A6）** 见 Data；密钥（Lark app id/secret、紧急账密种子）env 注入不入库。

### API & Communication Patterns
- **形态**：管理后台 = **Thymeleaf SSR MVC + HTMX 片段**，路由 `/admin/**`，**不在 `/api/v1`**（那是 App 的 JSON REST）；HTMX 端点返回 HTML 片段；CSRF 开启；错误用**友好错误页**（非 ProblemDetail JSON——后者仅 API）。
- **跨模块编排（复用既有，禁跨 repo）**：封禁兽医 → 既有 `ConsultSession` 中断逻辑 + 生成异常工单(AB-4A)；删除用户 → 既有注销级联(D1 匿名化) / 违规走 content service 下架(D2)；下架内容 → 既有下架 + notify 通知作者。
- **定时任务**：`@Scheduled` 每日扫 SIPDH 到期（切 `已过期` 停接单 + 后台预警）& 30 天预警标记 & 人工审核 3 天超时丢弃；`@Async` 逐条 + DB 去重；**禁调度/消息中间件（F5）**。

### Frontend Architecture
- Thymeleaf SSR + HTMX 2.0.x；`admin/layout.html` fragment 复用；**i18n**：Spring `MessageSource`（`messages_zh_CN/_en.properties`）+ Cookie `LocaleResolver` + 顶栏语言切换，模板硬编码中文外化为 `#{key}`；无 SPA 状态管理；桌面 only、无响应式；轻量共享 `admin.css`；HTMX 本地静态托管（不走 CDN）。

### Infrastructure & Deployment
- 同一单体 / 同德国单机 / 同 Docker Compose / 同 GitHub Actions（admin 域随主后端一起构建部署，零新增产物）；admin 子域经 CF + CF Access；可观测沿用 Actuator + JSON 日志（审计日志独立于应用日志，落库非落文件）。

### Decision Impact Analysis
**Implementation Sequence（建议）：**
1. `admin_accounts` + 权限模型 + 迁移现有 bootstrap/紧急账密（重构 3.1 认证）→ 一切前置
2. Lark OAuth 接入 + 白名单门控 + CF Access
3. 审计基建（操作 + 会话日志，哈希链，append-only）
4. 模块1 兽医（资质 `vet_qualifications` + 列表/CRUD/封禁/在线快照/失败请求/到期定时）
5. 模块2 用户（搜索/详情聚合/停用/删除匿名化）
6. 模块3 内容（举报队列/全量管理/人工审核预建）
7. 模块4 问诊异常（工单/会话元数据查询）+ 模块6 评分只读
8. i18n 双语外化收口

**Cross-Component Dependencies：**
- `admin_accounts` + 权限是所有受保护后台操作的根；审计横切所有写操作。
- 兽医资质状态 → 既有接单队列门控；封禁 → 既有会话中断 + 异常工单。
- 删除用户 → 既有注销级联(D1/D2) + 名片失效 + 内容处置。

## Implementation Patterns & Consistency Rules

> **继承基线**：本节是对 App+共享后端架构 §「Implementation Patterns & Consistency Rules」的**增量**。所有未在此覆盖的命名/结构/格式/通信/过程规则一律沿用基线（DB snake_case ↔ JSON/Java camelCase、Flyway `V<n>__desc.sql`、枚举 `varchar`+UPPER_SNAKE、`@Async`+DB 状态机禁中间件、SLF4J JSON 日志严禁 PII/令牌、RFC 9457 仅用于 `/api/v1`）。以下为管理后台**专属增量**。

### 关键冲突点（管理后台特有）
管理后台是 **SSR MVC + HTMX**，与 App 的 JSON REST 范式不同；若不约定，agent 会在「返回 JSON 还是 HTML 片段 / 路由前缀 / i18n 键 / 审计写法 / 权限标识」上各行其是。

### Naming Patterns（增量）
- **路由**：一律 `/admin/**`（**绝不**落 `/api/v1`）。页面路由用名词复数：`/admin/vets`、`/admin/users`、`/admin/reports`、`/admin/consult-anomalies`、`/admin/ratings`。HTMX 片段端点加动词或子资源段：`/admin/vets/{id}/ban`、`/admin/reports?status=PENDING&page=2`（筛选走 query）。
- **新表**：沿用 snake_case 复数 —— `admin_accounts`、`admin_account_permissions`、`admin_audit_logs`、`admin_session_logs`、`vet_qualifications`、`failed_consult_requests`、`manual_review_queue`、`admin_settings`；管理后台专属表统一 `admin_` 前缀（账号/权限/审计/设置），业务实体表不加前缀（`vet_qualifications` 等）。
- **权限标识 `permission_code`**：`<模块>.<动作>` 全小写点分（附录 B）：`vet.view/vet.create/vet.ban/vet.reset_password`、`content.view_reports/content.takedown/content.restore/content.proactive_takedown`、`consult.view_anomalies/consult.handle/consult.view_sessions`、`rating.view`、`admin.create_account/admin.deactivate/admin.view_logs`。Spring authority 直接用此字符串。
- **Java 包**：`com.tailtopia.admin.<子域>.{web,service,domain,repository,dto}`；后台专属 `web` 控制器后缀用 `XxxAdminController`（区别于 App 的 `XxxController`），返回视图名/片段而非 `@ResponseBody` DTO。
- **i18n 键**：`admin.<模块>.<语义>`，全小写点分 —— `admin.vet.list.title`、`admin.common.action.confirm`、`admin.audit.action.vet_banned`。两套 `messages_zh_CN.properties` / `messages_en.properties` 键集必须**一一对应**。
- **Thymeleaf 模板/片段**：模板放 `templates/admin/<模块>.html`；HTMX 片段用 fragment：`th:fragment="rows(...)"`，命名 `<语义>` 小写（`rows`/`row`/`form`/`detail`）。

### Structure Patterns（增量）
- 后端：`com.tailtopia.admin/` 下按子域分包（`account`/`audit`/`vetqual`/`usermgmt`/`moderation`/`anomaly`/`rating`），各含 `{web,service,domain,repository,dto}`；跨模块**只经既有各业务 service 或领域事件，禁跨 repo**（沿用基线边界）。
- 模板：`templates/admin/` 平铺业务页 + `admin/layout.html` 布局 fragment + `admin/fragments/` 放可复用片段（分页/筛选条/确认弹层）。
- 静态：HTMX 与 admin.css 放 `static/admin/vendor/htmx.min.js`、`static/admin/admin.css`（本地托管，noindex）。
- 测试：沿用 `AdminWebControllerTest` MockMvc 切片范式，镜像子域结构。

### Format Patterns（增量）
- **HTMX 端点返回 HTML 片段**（`text/html`），**不返回 JSON**；整页请求返回完整视图（`layout :: page`）。区分依据 `HX-Request` 头。
- **错误呈现**：后台用**友好错误页/内联错误条**（i18n 文案），**不外泄堆栈、不返回 ProblemDetail JSON**（ProblemDetail 仅 `/api/v1`）。表单校验错误回填到对应字段。
- **时间显示**：库内 `timestamptz` UTC（沿用基线）；**后台页面按运营所在时区展示**（统一在视图层格式化，标注时区），审计/日志存储仍 UTC ISO。
- **一次性敏感值**（重置兽医密码、紧急账密）：界面只显示一次，**绝不写入审计 summary / 日志**。

### Communication Patterns（增量）
- **审计写入是强制副作用**：每个后台**写操作**必须在同一事务内写一条 `admin_audit_logs`（`actor/action_type/target/summary` + 哈希链 `prev_hash→row_hash`）。`action_type` 用 UPPER_SNAKE 动词过去式：`VET_BANNED/CONTENT_TAKEN_DOWN/USER_DEACTIVATED/USER_DELETED/ACCOUNT_CREATED/PERMISSION_GRANTED`。统一经 `AdminAuditService.record(...)`，禁各控制器自拼。
- **跨模块副作用走既有领域事件**（沿用基线）：封禁→既有会话中断 + 发 `ConsultAnomalyRaisedEvent`（admin 订阅入 `failed`/`anomaly` 表）；下架→既有事件 + notify 通知作者。**不在 admin 内直接操别模块的库**。
- **会话/在线态**：在线快照读既有 Redis 兽医在线态（沿用），AB-2F 只读不写。

### Process Patterns（增量）
- **鉴权流（与 App 相反）**：后台是**服务端会话**，非 JWT 静默续期；未登录/会话失效 → 302 跳 `/admin/login`（HTMX 请求用 `HX-Redirect` 头跳转）。每请求校验账号 `status=ACTIVE` + 仍在白名单。
- **授权校验**：`@PreAuthorize("hasAuthority('<permission_code>')")` 于 service/controller 方法（权威）；模板按权限隐藏入口仅为体验（`sec:authorize`）。`SUPER_ADMIN` 隐式全权（自定义 `PermissionEvaluator` 或授予全集）。
- **CSRF**：后台所有 POST/PUT/DELETE 表单与 HTMX 请求**必须带 CSRF token**（沿用既有 admin 链已开启 CSRF）。
- **批量操作**：多选 → 单次提交一组 id → service 内逐条处理 + 逐条审计 + 汇总结果回片段；部分失败回报每条结果，不整体回滚已成功项（除非业务要求原子）。
- **删除合规**：用户删除按 D1（注销匿名化）/ D2（违规下架）两分支，复用既有级联删除/匿名化路径；二次确认 + 必填备注 + 永久审计。

### Enforcement Guidelines
**所有 AI Agent 实现管理后台时 MUST：**
- 后台路由一律 `/admin/**`，返回视图/HTML 片段；**绝不**在后台控制器返回 JSON 或落 `/api/v1`。
- 每个写操作经 `AdminAuditService` 写审计（哈希链）；审计/日志**绝不**含一次性密码、令牌、签名 URL、健康/PII。
- 授权用 `permission_code` authority 方法级强制；前端隐藏非安全边界；`SUPER_ADMIN` 隐式全权。
- 新表沿用 snake_case + Flyway 从 V30 顺延 + 枚举 UPPER_SNAKE；后台账号走 `admin_accounts`（**不**复用 `users(role=ADMIN)`）。
- 异步/定时只用 `@Async`+`@Scheduled`+DB 去重，**禁中间件**；跨模块只经既有 service/事件，禁跨 repo。
- i18n 双键集一一对应；文案外化 `#{admin.*}`，不硬编码中文/英文。

### Pattern Examples
- ✅ `GET /admin/vets?status=ACTIVE`（HX-Request 时返 `admin/vets :: rows`）；封禁 `POST /admin/vets/{id}/ban` → service 改状态 + 中断会话事件 + `AdminAuditService.record(VET_BANNED, vetId, summary)` → 返回更新后的行片段。
- ❌ 反例：后台控制器 `@ResponseBody` 返 JSON 让前端 JS 渲染（违背 SSR+HTMX）；封禁直接 `consultRepository.update(...)` 跨 repo；审计 summary 写入新密码明文；后台账号塞进 `users` 表。

## Project Structure & Boundaries

### Complete Project Directory Structure（管理后台增量，全部在 petgo-backend 内）
```
petgo-backend/
├── pom.xml                                          # ♻️ 追加 spring-boot-starter-oauth2-client
├── src/main/java/com/tailtopia/
│   ├── admin/                                       # ♻️ 既有 slice，本架构扩为完整后台
│   │   ├── account/      {web,service,domain,repository,dto}   # 🆕 AB-0/AB-1 后台账号·权限·登录·白名单
│   │   │   ├── domain/{AdminAccount, AdminAccountStatus, AdminAccountType, AdminPermission}
│   │   │   ├── service/{AdminAccountService, AdminBootstrap♻️, LarkOAuthService, AdminPermissionEvaluator}
│   │   │   └── web/{AdminLoginController, AdminAccountAdminController}
│   │   ├── audit/        {web,service,domain,repository,dto}   # 🆕 AB-0B/AB-1B 操作+会话审计(哈希链)
│   │   │   ├── domain/{AdminAuditLog, AdminSessionLog}
│   │   │   └── service/{AdminAuditService}          # record(...) 统一入口
│   │   ├── vetqual/      {web,service,domain,repository,dto}   # 🆕 AB-2H 兽医资质(SIPDH 状态机)
│   │   │   ├── domain/{VetQualification, QualificationStatus}
│   │   │   └── service/{VetQualificationService, SipdhExpiryScanner(@Scheduled)}
│   │   ├── vet/          {web,service,dto}          # ♻️ AB-2A~2F 兽医列表/CRUD/封禁/在线快照（扩 AdminVetService♻️）
│   │   │   ├── service/{FailedConsultRequestService}            # 🆕 AB-2G 问诊失败请求
│   │   │   └── web/{VetAdminController♻️, FailedRequestAdminController}
│   │   ├── usermgmt/     {web,service,domain,repository,dto}   # 🆕 AB-UA 用户搜索/详情/停用/删除(D1/D2)
│   │   │   └── service/{AdminUserService}           # 复用 auth/profile/content/consult service
│   │   ├── moderation/   {web,service,dto}          # ♻️ AB-3 举报队列/全量管理/人工审核（扩 AdminModerationService♻️/AdminContentService♻️）
│   │   │   ├── domain/{ManualReviewItem, ReviewStatus, AdminSettings}
│   │   │   └── service/{ManualReviewService, ReviewTimeoutScanner(@Scheduled)}
│   │   ├── anomaly/      {web,service,domain,repository,dto}   # 🆕 AB-4 问诊异常工单/会话元数据查询
│   │   │   └── service/{ConsultAnomalyService}      # 订阅 ConsultAnomalyRaisedEvent
│   │   └── rating/       {web,service,dto}          # 🆕 AB-6 评分总览/单兽医详情(只读)
│   │       └── service/{AdminRatingService}         # 读 consult 评分
│   └── shared/
│       ├── security/  {SecurityConfig♻️(adminFilterChain 扩 oauth2Login),
│       │               AdminUserDetailsService♻️→指向 admin_accounts,
│       │               LarkClientRegistration🆕, AdminWhitelistFilter🆕, EmergencyLoginGuard🆕}
│       └── i18n/      {AdminLocaleConfig🆕(MessageSource+CookieLocaleResolver)}
├── src/main/resources/
│   ├── db/migration/   V30__init_admin_accounts.sql … V37__init_admin_settings.sql   # 🆕 顺延
│   ├── templates/admin/
│   │   ├── layout.html♻️  login.html♻️(加 Lark 主入口+紧急折叠)  dashboard.html♻️
│   │   ├── vets.html♻️  reports.html♻️  vet-ratings.html♻️  seed-post.html♻️         # ♻️ 外化 i18n
│   │   ├── vet-qualifications.html  users.html  user-detail.html                     # 🆕
│   │   ├── failed-requests.html  manual-review.html  consult-anomalies.html          # 🆕
│   │   ├── admin-accounts.html  audit-logs.html  session-logs.html                   # 🆕
│   │   └── fragments/{pagination.html, filter-bar.html, confirm-dialog.html}         # 🆕
│   ├── static/admin/{vendor/htmx.min.js🆕(2.0.9), admin.css🆕}
│   └── i18n/{messages_zh_CN.properties🆕, messages_en.properties🆕}                  # admin.* 键集
└── src/test/java/com/tailtopia/admin/<子域>/...      # ♻️ 镜像，MockMvc 切片 + 关键路径
```

### Architectural Boundaries
- **路由边界**：`/admin/**` = SSR MVC + HTMX（独立 `adminFilterChain`：session+form+oauth2Login+CSRF，ROLE/authority 门控）；`/api/v1/**` = App JSON REST（STATELESS JWT）；`/p/{cardToken}` = 公开 H5。三链互不触达（securityMatcher 隔离）。
- **模块边界**：`com.tailtopia.admin.*` 跨业务**只经既有各模块 service 或领域事件**，禁直接访问 content/consult/profile/vet 的 repository；admin 自有表（`admin_*` / `vet_qualifications` / `failed_consult_requests` / `manual_review_queue`）由 admin repository 拥有。
- **数据边界**：后台账号/权限/审计/设置 = `admin_*` 表（与 `users`/`vet_accounts` 隔离）；用户详情聚合经 owning service 读，不跨库 join；资质证件图 → OSS **私密桶 + 短签名 URL + EXIF 剥离**；在线快照读既有 Redis。
- **边缘边界**：`admin.tailtopia.id` 经 Cloudflare + **CF Access（Zero Trust）**；源站仅放行 CF 回源。

### Requirements to Structure Mapping
| AB 模块 | 后端落点 | 模板 |
|---|---|---|
| AB-0/1 登录·会话·账号·权限·审计 | `admin/account` + `admin/audit` + `shared/security` | `login/admin-accounts/audit-logs/session-logs` |
| AB-2A~2G 兽医账号·失败请求 | `admin/vet` + `AdminVetService♻️` | `vets/failed-requests` |
| AB-2H 兽医资质 | `admin/vetqual` + `SipdhExpiryScanner` | `vet-qualifications` |
| AB-UA 用户管理 | `admin/usermgmt`（复用 auth/profile/content/consult service） | `users/user-detail` |
| AB-3 内容审核·人工队列 | `admin/moderation` + `ReviewTimeoutScanner` | `reports/manual-review` |
| AB-4 问诊异常·会话查询 | `admin/anomaly`（订阅事件） | `consult-anomalies` |
| AB-6 评分看板 | `admin/rating`（只读） | `vet-ratings` |

**跨切面**：认证/授权 `shared/security`（Lark OAuth + 白名单 + 紧急账密 + permission authority）；审计 `admin/audit/AdminAuditService`（所有写操作强制）；i18n `shared/i18n` + `resources/i18n/messages_*`；定时 `*Scanner(@Scheduled)`。

### Integration Points
- **外部**：Lark 开放平台 OAuth（`LarkOAuthService`/`LarkClientRegistration`，env 注入 app id/secret）；Cloudflare Access（边缘 SSO）；OSS 私密桶（资质图，复用 `shared/media`）。
- **内部**：admin service → 既有 vet/content/consult/profile/auth service（只读聚合 + 状态变更）；领域事件（封禁→会话中断 + `ConsultAnomalyRaisedEvent`→anomaly；下架→notify 作者）；Redis（在线快照只读）。
- **数据流（三条关键）**：① 封禁兽医：`VetAdminController`→既有会话中断+审计+`ConsultAnomalyRaisedEvent`→`ConsultAnomalyService`入工单。② 删除用户：`AdminUserService`→D1 匿名化 / D2 下架（既有级联）+名片失效+审计。③ SIPDH 到期：`SipdhExpiryScanner@Scheduled`日扫→切「已过期」停接单+后台预警，30天预警标记。

### Development Workflow Integration
- 开发：`docker-compose up`(PG/Redis) + `mvn spring-boot:run`，浏览器开 `/admin/login`（本地 Lark 回调用 dev 配置或走紧急账密）。
- 构建/部署：随主后端同一 `mvn package` 出 jar + 镜像 → 德国机 compose；admin 子域 CF+CF Access 配置（基建侧）。
- 测试：MockMvc 切片（沿用 `AdminWebControllerTest`）；L1 需 PG/Redis 验真实门控/审计/迁移；Lark OAuth/CF Access 属 L2（真实凭证/边缘）。

## Architecture Validation Results

### Coherence Validation ✅
- **决策兼容**：Spring Boot 4 / Java 21 / Thymeleaf + thymeleaf-extras-springsecurity6 / HTMX 2.0.9 / oauth2-client / PostgreSQL+Flyway / Redis —— 组合均验证可用；与共享后端同栈、同产物。三条安全链（admin session / api JWT / 公开 H5）经 securityMatcher 隔离，互不触达，无矛盾。
- **模式一致**：后台 SSR+HTMX 范式与「路由 /admin/** 返 HTML 片段、审计强制副作用、permission authority 门控」自洽；继承基线命名/格式/通信/过程规则无冲突；与「禁中间件、禁跨 repo、对外 token、日志脱敏」一脉。
- **结构对齐**：`com.tailtopia.admin.*` 子域分包 ↔ 模板/静态/迁移布局 ↔ AB 模块映射表三者闭合；admin 自有 `admin_*` 表与既有 users/vet 隔离边界清晰。

### Requirements Coverage Validation ✅
- **AB 需求覆盖（21/21 有架构落点）**：登录/会话(account+security+audit)、后台账号/权限/审计(account+audit)、兽医账号/资质/失败请求/在线快照(vet+vetqual)、用户管理(usermgmt 复用既有 service)、内容审核/人工队列(moderation)、问诊异常/会话查询(anomaly)、评分看板(rating)。逐模块可追溯至目录（见结构映射表）。
- **NFR 覆盖**：独立认证体系(Lark OAuth+紧急账密+白名单)✅ · 审计 append-only+哈希链 永久(A6)✅ · 删除匿名化 D1/D2✅ · 数据边界 不读 IM 正文✅ · 桌面 only + 中英双语 i18n✅ · 敏感证件 私密桶+签名URL+EXIF✅ · 公网无 VPN + CF Access(A4)✅。
- **反向指标**：AM-C1 零漏记录 → 审计强制副作用 + 哈希链；AM-C2 零越权 → 服务端逐端点 authority 门控(A5)。

### Implementation Readiness Validation ✅
- **决策完整**：关键决策均带版本/理由；3 处分叉(F-A/F-B/F-C)已收敛；Flyway 序号 V30 起单调分配明确。
- **结构完整**：目录树具体到文件、标 🆕/♻️；集成点/边界/数据流明确。
- **模式完整**：管理后台特有冲突点(JSON vs 片段 / 路由 / i18n 键 / 审计 / 权限标识)均有规则 + 正反例。

### Gap Analysis Results
| # | 等级 | 缺口 | 处置 |
|---|------|------|------|
| AG-1 | 🟠 重要 | **现有 Story 3.1 认证重构**：users-role=ADMIN + V7 password_hash → 迁移到专用 `admin_accounts`，须不破坏现网 bootstrap admin。 | 实现序列第 1 步专门处理：建 `admin_accounts` + 迁移 bootstrap，`AdminUserDetailsService` 改指向新表；旧 V7 列保留停用（决策 E2）。落为独立故事。 |
| AG-2 | 🟠 重要 | **审计哈希链并发写入**：`prev_hash→row_hash` 链依赖写入串行，并发写可能竞争。 | 后台写并发极低；`AdminAuditService.record` 内对链尾取行锁/借数据库序列串行化，单写入路径。实现注意项。 |
| AG-3 | 🟠 重要 | **人工审核队列激活是跨产品契约**：AB-3C 激活后改变 App 用户侧发布体验（覆盖 FR-12），需 App 侧配合。 | V1.0.0 队列**预建未激活**，不阻塞；激活时另起跨 App+后台的协调故事。已记 Deferred。 |
| AG-4 | 🟡 次要 | **PRD §5/§9 双语修正待回填**（原「仅中文」）。 | 架构已采纳中英双语；回填 PRD 作为收尾动作（step-08 提示）。 |
| AG-5 | 🟡 次要 | **Lark OAuth 非标流 + CF Access 属 L2**：需真实飞书应用凭证与边缘配置。 | 接口抽象 `LarkOAuthService`，dev 可走紧急账密；L2 验收留真实凭证。已记。 |
| AG-6 | 🟡 次要 | 用户详情「历史问诊」只能展示元数据（IM 正文不可读）。 | 与 AB-4B 一致；UI 明确标注「仅元数据」。 |

> 无 🔴 Critical 阻断项。AG-1/2/3 为重要但已有落点/边界，不阻塞启动实现。

### Architecture Completeness Checklist
**Requirements Analysis**
- [x] Project context thoroughly analyzed
- [x] Scale and complexity assessed
- [x] Technical constraints identified
- [x] Cross-cutting concerns mapped

**Architectural Decisions**
- [x] Critical decisions documented with versions
- [x] Technology stack fully specified
- [x] Integration patterns defined
- [x] Performance considerations addressed（低频内部工具，性能非瓶颈，已论证）

**Implementation Patterns**
- [x] Naming conventions established
- [x] Structure patterns defined
- [x] Communication patterns specified
- [x] Process patterns documented

**Project Structure**
- [x] Complete directory structure defined
- [x] Component boundaries established
- [x] Integration points mapped
- [x] Requirements to structure mapping complete

### Architecture Readiness Assessment
**Overall Status:** READY FOR IMPLEMENTATION（16/16 勾选，无 Critical Gap；AG-1/2/3 重要项已有落点与边界）
**Confidence Level:** high
**Key Strengths：**
- brownfield 复用既有 admin slice + 共享后端基建，零新增产物/运维面，高度契合 V1 减面。
- 安全攸关项（服务端会话即时撤权 A1、逐端点授权 A5、审计不可篡改 A6、CF Access A4、Lark 硬化 A2）成体系，直击 AM-C1/C2 反向指标。
- admin 账号与 App 身份完全隔离（符合 PRD），两级权限 RBAC-ready 而不过度设计。
**Areas for Future Enhancement：**
- 动态 RBAC（角色表）· 人工审核队列激活 · 付费封禁退款分支(1.1.0) · 客服模块(1.1.0)。

### Implementation Handoff
**AI Agent Guidelines：** 严格遵循已记录决策与一致性规范；后台一律 /admin/** + HTML 片段、不返 JSON；每写操作经 AdminAuditService 审计；授权用 permission authority 方法级强制；后台账号走 admin_accounts；异步/定时只用 @Async/@Scheduled+DB（禁中间件）；跨模块只经既有 service/事件。
**First Implementation Priority：** 实现序列第 1 步——建 `admin_accounts` + 权限模型 + 迁移现有 bootstrap/紧急账密（重构 Story 3.1 认证），作为一切后台操作的前置。
