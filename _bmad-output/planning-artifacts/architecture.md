---
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
lastStep: 8
status: 'complete'
completedAt: '2026-06-01'
inputDocuments:
  - _bmad-output/planning-artifacts/PRD.md
  - _bmad-output/planning-artifacts/TECH_FRAMEWORK.md
  - _bmad-output/planning-artifacts/UX_DESIGN.md
  - _bmad-output/planning-artifacts/UX_EXPERIENCE.md
  - _bmad-output/planning-artifacts/implementation-readiness-report-2026-06-01.md
  - _bmad-output/planning-artifacts/index.md
workflowType: 'architecture'
project_name: 'PetGo'
user_name: 'Dai'
date: '2026-06-01'
---

# PetGo V1.0 架构决策文档（Architecture Decision Document）

_本文档通过逐步协作发现的方式构建。每完成一项架构决策，对应章节将被追加进来。本文档的唯一权威范围来自 PRD（39 个 FR）；技术选型以 TECH_FRAMEWORK 对齐稿为方向起点，在本工作流中细化为正式架构。_

## Project Context Analysis

### V1 架构姿态（总纲）

V1 硬约束：**快速上线、快速试错；目标 DAU ≤ 500；不过度设计。** 优化目标不是抗峰值，而是"小团队改一行→发出去"的速度。500 DAU 的真正瓶颈是认知负载与运维面，故默认动作是"减面"——每朵云、每个中间件都需用业务价值证明其留存。本姿态据此对 TECH_FRAMEWORK 的偏重方案做了系统性瘦身（见下"技术约束"）。

### Requirements Overview

**Functional Requirements（39 FR / 8 模块）：**
- auth（FR-0A~0H, FR-19, FR-29）：Google OAuth + 兽医账密双路；单 App 双角色门控（JWT role claim）；游客态 + 软/强登录引导。
- triage（FR-1~3, FR-4A）：AI 图文分诊（**同步直连 Gemini**，异步仅用进程内 `@Async`）+ **确定性安全规则层**（高危→强制红，独立于模型）；≤15s SLA；绿/黄/红结构化输出。
- consult（FR-4B, FR-5, FR-30~33）：会话状态机（待接单→进行中→待关闭→关闭/中断）；1min 无人接单超时、30min 评分确认门、兽医封禁中断；编排腾讯云 IM（后端不持长连接）。
- content（FR-12, FR-17, FR-18, FR-23, FR-24, FR-28, FR-36）：读扩散 Feed（时间倒序 + 宠物状态硬过滤 + 无限滚动 20/批）；两级评论；点赞开关；删除级联。V1 不做收藏/@提及/搜索。
- profile（FR-11, FR-14~16, FR-37, FR-39）：单账号单宠物；成长时间线（快乐时刻 + 健康事件）；**H5 名片 = Java 服务端模板直出 + OG 预渲染静态图**（防枚举 token + noindex）。
- notify（FR-22A~E, FR-34, FR-38）：推送**复用腾讯 IM 离线推送** + 统一深链接路由表 + 通知中心铃铛角标。
- moderation（FR-25）：举报工单进人工队列，V1 无自动下架。
- me/i18n（FR-20, FR-21, FR-27）：账号注销（PDP 强制 + 内容匿名化保留 + 级联删除）；id/en 双语跟随设备。

**Non-Functional Requirements：**
- 性能 SLA：AI 分诊 ≤15s、H5 ≤3s、Feed 20/批。
- 跨地域延迟：印尼↔德国动态 API ~320-360ms（后端留德国，靠首屏接口聚合 + 静态走边缘 + 实时走 IM 消化；预留亚太只读副本演进位）。
- 实时性：兽医咨询切 Tab 不断连、消息连续（TIM 保证）。
- 安全攸关：AI 假阴性 → 确定性安全规则层兜底（架构必需件，与规模无关）。
- 隐私/信任：媒体私密图签名 URL；H5 防枚举 + noindex；注销匿名化；红色页零变现；免责声明前置可留证。
- 可靠性降级：AI 超时/异常重试（DB 状态机驱动）+ 软引导兽医；上传失败仅重传失败件，文字 session 内存保留，无持久草稿。
- i18n：全部 UI/系统/错误文案 id+en 双套。

**Scale & Complexity：**
- Primary domain：全栈移动（Flutter + Java 模块化单体 + Thymeleaf H5 + 少量外部 SaaS）。
- Complexity level：**medium**（经瘦身后，功能数中等、技术面收敛为单体 + 少数外部 API；不再是重多云）。
- Estimated architectural components：8 后端模块（同进程）+ PostgreSQL + 轻量 Redis + 单一对象存储 + 3 个外部依赖（Gemini / 腾讯 IM / OAuth）。

### Technical Constraints & Dependencies（V1 轻量化定案）

- **后端选址 = 留德国**（45.90.122.44，不迁移、复用已付费机器、最快上线）。后端 + PostgreSQL + Redis 同机模块化单体。
- 消息队列：**砍 RocketMQ** → DB 状态机（`status` PENDING|PROCESSING|DONE|FAILED + `retry_count` + 启动重扫）+ Spring `@Async`。撑到数千 DAU 再换 Redis Stream（局部替换，接口不变）。
- H5 名片：**砍独立 SSR** → Thymeleaf `GET /p/{cardId}` 服务端直出 HTML + 服务端填 OG/Twitter meta；预览大图在生成卡片时预渲染为静态图缓存到对象存储。
- 推送：**砍独立 TPNS** → 复用腾讯 IM 离线推送能力。
- 关系库：**PostgreSQL 保留**，单实例 + 每日备份，JSONB 存 Gemini 原始响应。
- 缓存：**Redis 收窄**为 auth 限流 + 调用幂等键/防重锁，不当通用缓存、不当队列、不上 Cluster。
- 媒体存储：单一 S3 兼容对象存储承载分诊图 + 档案图；**V1 不做 VOD/视频转码**（AI 分诊仅图文；内容 Feed V1 不支持视频；兽医聊天视频由腾讯 IM 托管）。
- 外部依赖（不可避免）：Google Gemini（分诊模型）、腾讯云 IM（免费实时聊天 + 离线推送）、Google OAuth、系统地图深链。
- 跨云交互收敛到极少点：IM→对象存储存档复制、Gemini 签名 URL 直拉私密图。

### 不可协商地基（Non-Negotiables，与 DAU 无关）

1. AI 确定性安全规则层（高危症状清单 → 命中强制升红，只许往上兜不许往下降；清单可从最高频致死的 15-20 个急症起步）。
2. 红色等级页零兽医/变现引流入口。
3. 免责声明强制、前置、可留同意记录。
4. 账号与数据删除：数据模型设计之初即考虑各表 + 对象存储照片的级联删除路径。
5. 内容举报入口 + 人工下架能力。
6. PDP 基础：收集前同意 + 目的告知 + 真实隐私政策 + 数据主体权利可达路径。

### Cross-Cutting Concerns Identified

1. 鉴权与角色门控（user/vet JWT claim，游客态）
2. 异步任务（DB 状态机 + `@Async`：AI 分诊 / 通知 / 审核）
3. 会话状态机 + 在线态（Redis）
4. 媒体存储与跨云存档复制桥接
5. 统一推送（复用 IM）+ 深链接路由
6. 分诊安全规则层（确定性兜底）
7. 双语 i18n
8. 限流与幂等（Redis）

### 挂账风险（Risk Ledger）

- 后端留德国 = 印尼数据出境 + 动态 API ~320-360ms 延迟。延迟用工程手段消化；**PDP 跨境合规按用户决定暂缓——记为风险（暂缓≠豁免），印尼监管收紧需重评并可能触发后端迁亚太/只读副本。**

## Starter Template Evaluation

### Primary Technology Domain

V1 是**双产物全栈移动**项目，需两套脚手架（无独立 SSR——H5 名片由后端 Thymeleaf 承载）：
1. **移动端**：Flutter App（用户端 + 兽医端，同一 App 角色门控）
2. **后端**：Java / Spring Boot 模块化单体（含 8 业务模块 + Thymeleaf H5 名片页）

版本基线（2026-06 联网核实；Java 基线 2026-06-02 由 25 调整为 21）：Flutter 3.44.x / Dart 3.12 · Spring Boot 4.0.x · Java 21 LTS。原拟 Java 25 LTS，但全项目无任何功能需求依赖 Java 22~25 特性；改 21 的理由：云端/CI 自带 21、生态成熟、Temurin 21 LTS 支持到 ~2029、消除前沿工具链摩擦，契合 V1 轻量姿态。Boot 4 / Spring 7 官方基线 Java 17+，21 完全兼容；底线仍是 Boot 4 / Spring 7（勿退回 SB3）。

### Starter Options Considered

**移动端：**
- `flutter create`（官方）+ Riverpod —— ✅ 选定。最轻、样板最少、上手最快，契合"快速试错、不过度设计"；l10n/CI/测试按需加，不强制 100% 覆盖。
- Very Good CLI（`very_good create flutter_app`，Bloc + flavors + 100% 覆盖 + CI）—— 未选。电池全包但绑定 Bloc + 强制覆盖率，对快速试错略有仪式感负担。
- `flutter create` + Bloc（手动）—— 未选。

**后端：**
- Spring Initializr（`start.spring.io` / `spring init` CLI）—— ✅ 选定。Spring Boot 官方生成器，业界标准；按需勾选依赖即出可运行骨架。
- 构建工具：**Maven**（选定，最"无聊稳定"、小队零摩擦）vs Gradle（未选）。

### Selected Starter A — 移动端：Flutter (官方 create + Riverpod)

**Initialization Command:**

```bash
# 1) 生成骨架（org 决定包名/Bundle ID，可改）
flutter create --org com.petgo --project-name petgo petgo_app
cd petgo_app

# 2) 状态管理 Riverpod
flutter pub add flutter_riverpod riverpod_annotation
flutter pub add dev:riverpod_generator dev:build_runner dev:custom_lint dev:riverpod_lint

# 3) 双语 i10n（id/en，FR-27，Day-1 必须）
flutter pub add intl
flutter pub add flutter_localizations --sdk=flutter
#   → 配 l10n.yaml + lib/l10n/app_en.arb / app_id.arb，generate: true

# 4) 路由（推荐 go_router，便于 FR-38 深链接路由表落地）
flutter pub add go_router

# 5) 严格 lint
flutter pub add dev:flutter_lints
```

**Architectural Decisions Provided / Established:**
- 语言/运行时：Dart 3.12 / Flutter 3.44.x，portrait-only，iOS + Android 单代码库。
- 状态管理：Riverpod（轻样板、编译期安全、易测）。
- 国际化：flutter_localizations + intl + .arb（id/en），跟随设备语言回退英语。
- 路由：go_router（承接 FR-38 推送深链接路由表；二级页 Tab Bar 隐藏）。
- 目录：按 feature 分层（auth / triage / consult / content / profile / notify / me），与后端模块边界对齐。
- 测试/CI：按需添加（不强制 100% 覆盖），关键路径优先（分诊状态机、登录门控、深链接）。

### Selected Starter B — 后端：Spring Boot (Initializr + Maven)

**Initialization Command:**

```bash
# 需安装 Spring Boot CLI；或直接用 start.spring.io 网页勾选同样依赖
spring init \
  --type=maven-project \
  --boot-version=4.0.6 \
  --java-version=21 \
  --group-id=com.petgo \
  --artifact-id=petgo-backend \
  --name=petgo-backend \
  --package-name=com.petgo \
  --dependencies=web,validation,data-jpa,postgresql,data-redis,oauth2-resource-server,security,thymeleaf,actuator,flyway,lombok \
  petgo-backend
```

**Architectural Decisions Provided by Starter:**
- 语言/运行时：Java 21 LTS，Spring Boot 4.0.x，打包 jar，部署德国单机。
- Web/校验：Spring Web（REST）+ Bean Validation。
- 持久层：Spring Data JPA + PostgreSQL 驱动；**Flyway** 管理 schema 迁移（版本化、可重放）。
- 缓存/限流/幂等：Spring Data Redis（仅 auth 限流 + 调用幂等键/防重锁）。
- 安全：Spring Security + OAuth2 Resource Server（校验后端自签 JWT，role claim 区分 user/vet）；Google ID Token 校验在 auth 模块内做。
- H5 名片：Thymeleaf 服务端模板（`/p/{cardId}` 直出 + OG meta）。
- 运维：Actuator（健康检查/基础指标）。
- 开发便利：Lombok。
- 代码组织：模块化单体——按 8 模块（auth/triage/consult/content/profile/notify/moderation/vet）划包，模块边界即未来拆分线；异步用 Spring `@Async` + DB 状态机（无 MQ）。

**Note:** 这两条初始化命令应作为实现阶段的**第一个 Story**（项目脚手架搭建）。

## Core Architectural Decisions

### Decision Priority Analysis

**Critical（阻塞实现，必须先定）：** 数据库=PostgreSQL · 鉴权=Google OAuth+自签 JWT / 兽医账密 · AI 接入=Gemini Developer API(gemini-2.5-flash) · 异步=DB 状态机+@Async · 媒体=阿里 OSS 雅加达(公开+私密桶) · 边缘=Cloudflare 前置 · IM=腾讯云。
**Important（显著塑形）：** 错误规范=Problem Details(RFC 9457) · API 版本=/api/v1 · 限流=Redis · 分诊结果=轮询/IM 推送 · 前端网络=dio · 部署=德国单机 Docker Compose + GitHub Actions。
**Deferred（后置/有 traction 再做）：** 正式 MQ(先 @Async，瓶颈再换 Redis Stream) · VOD/内容视频(V2) · Vertex AI 迁移(需企业数据控制时) · 亚太只读副本(延迟成体验瓶颈时) · 自动化内容审核(先人工) · PDP 跨境合规(用户暂缓)。

### Data Architecture

- **关系库**：PostgreSQL（单实例，每日 pg_dump 离线备份至 OSS 私密桶）。Spring Data JPA + **Flyway** 版本化迁移。
- **建模**：8 模块按 schema/包划界；分诊结构化结果与档案弹性字段用 **JSONB**（存 Gemini 原始响应 + 解析后绿/黄/红字段）。
- **主键与对外标识**：内部 `bigint` 代理主键；**对外暴露处一律用不可枚举 token**——H5 名片 `/p/{token}`（FR-14 安全要求，随机 token + noindex，绝不暴露顺序 ID），推送深链亦用 token。
- **缓存（Redis 收窄）**：auth 限流、调用幂等键/防重锁、兽医在线态(在线/忙/离线)、问诊队列态、未读角标计数。**不做通用缓存、不当队列、不上 Cluster。**
- **媒体三层（隐私边界）**：① 阿里 OSS 公开桶（Feed/档案/H5 名片图，阿里 CDN 分发）；② 阿里 OSS 私密桶（AI 分诊图/健康历史图，仅短期签名 URL）；③ 腾讯 IM 托管（兽医聊天图/视频）。客户端 **STS 临时凭证直传 OSS**（不经后端）。**桥接规则**：问诊存档时把所需图从 IM 复制一份到 ②私密桶，档案只引用应用自有 URL（不引用会过期的 IM URL）。

### Authentication & Security

- **用户登录**：Google OAuth → 后端校验 Google ID Token → 签发自有 **JWT（access 短时 + refresh 轮换）**，`role=user`。游客可只读浏览 Feed（FR-0A）。
- **兽医登录**：运营创建账号/密码，**BCrypt** 存储 → JWT `role=vet`。与用户侧 Google 流程隔离（FR-29）。
- **令牌存储（客户端）**：`flutter_secure_storage`（access/refresh）；`shared_preferences` 存非敏感偏好（语言、宠物状态、引导计数）。
- **授权**：Spring Security 按 `role` claim 路由/方法门控；兽医账号不可达用户侧功能，反之亦然（单 App 角色门控）。
- **边缘安全**：Cloudflare 前置——靠近印尼终止 TLS、基础 WAF/DDoS、**隐藏德国源站 IP（45.90.122.44）**；源站防火墙仅放行 Cloudflare 回源 IP。
- **媒体访问**：私密桶仅发**短 TTL 签名 URL**；上传走 STS 限定 scope；Gemini 用签名 URL 直拉私密图（不三角绕行）。
- **🔒 AI 确定性安全规则层（不可协商）**：高危症状关键词清单 → 命中**强制升红**，置于模型返回之后做后置校验，**只许往上兜、永不往下降**，独立于 Gemini。清单先覆盖最高频致死 15-20 急症（误食巧克力/葡萄/木糖醇/百合、呼吸困难、呕吐带血、犬胃扭转、猫尿道阻塞等），由兽医顾问维护。
- **PDP 基础（不可砍）**：登录页前置同意 + 服务条款/隐私政策链接（FR-0D）；账号与数据删除级联（FR-20，数据模型设计之初即纳入各表 + OSS 图片删除路径）；红色页零变现入口（FR-3）；免责声明前置可留证（附录 C）。
- **密钥管理**：env 变量 / Spring 外部化配置，不入库；STS、Gemini Key、IM SecretKey、OAuth Secret 全部环境注入。

### API & Communication Patterns

- **风格**：REST + JSON，版本前缀 `/api/v1`。
- **文档**：springdoc-openapi（最新版，SB4 兼容；不用 HATEOAS 规避兼容坑）→ 自动 OpenAPI 3.1。
- **错误规范**：Spring Boot 4 原生 **ProblemDetail（RFC 9457）** 统一错误信封（type/title/status/detail/traceId），前端可一致解析降级文案。
- **限流**：Redis 令牌桶，作用于登录、AI 提交、发布等写端点。
- **分诊异步契约**：`POST /api/v1/triage` → 202 + `triageId`；客户端**短轮询** `GET /api/v1/triage/{id}` 或收 IM/推送通知完成。DB 状态机（PENDING→PROCESSING→DONE/FAILED + retry_count + 启动重扫）驱动可靠性。
- **IM 编排**：后端通过腾讯 IM REST/回调建会话、发系统消息、封禁中断；会话状态机（待接单→进行中→待关闭→关闭/中断）、1min 无人接单超时、30min 评分确认门在 Java 侧。兽医在线态/队列在 Redis。
- **推送**：复用腾讯 IM 离线推送（底层 APNs/FCM）；深链接路由表（FR-38）客户端 go_router 落地。
- **延迟缓解**：首屏接口走 **BFF 式聚合**（一次返回，减少印尼↔德国串行往返）；可缓存响应丢 Redis / CF 边缘缓存。

### Frontend Architecture

- **状态**：Riverpod（编译期安全、易测）。**路由**：go_router（承接 FR-38 深链；二级页隐藏 Tab Bar）。**结构**：feature-folder，与后端 8 模块对齐。
- **网络层**：dio（拦截器：JWT 注入/刷新、重试、ProblemDetail→本地化错误映射）+ 薄 repository 层。
- **实时/媒体**：腾讯 IM Flutter SDK 直连；图片客户端压缩（≤10MB）后 STS 直传 OSS；portrait-only。
- **i18n**：flutter_localizations + intl + .arb（id/en），跟随设备回退英语，即时切换。
- **草稿**：按 PRD 仅内存保留，无持久草稿；上传失败仅重传失败件。

### Infrastructure & Deployment

- **托管**：德国单机（45.90.122.44），**Docker Compose**：app(Spring Boot jar) + PostgreSQL + Redis；Cloudflare 前置。
- **TLS/反代**：Cloudflare 终止 TLS；源站 Caddy/Nginx 持源站证书，防火墙仅放行 CF 回源。
- **CI/CD**：GitHub Actions——后端 build jar + 镜像 → 部署德国机（registry pull / ssh）；Flutter build，V1 手动上架商店。
- **配置**：Spring profiles(dev/prod) + env 注入；无独立配置中心。
- **可观测**：Actuator 健康/基础指标 + 轻量在线监测(UptimeRobot 类) + logback JSON 结构化日志；基础告警。**不上重型 observability 栈。**
- **媒体分发**：阿里 OSS 雅加达（公开+私密桶）+ 阿里 CDN（媒体）；Cloudflare CDN（API/H5 页）。**CDN 职责不重叠。**

### Decision Impact Analysis

**Implementation Sequence（建议）：**
1. 脚手架（两条 init 命令）+ Docker Compose(PG/Redis) + CF 接入 + CI 骨架
2. auth（Google OAuth + 兽医账密 + JWT + 角色门控 + 游客态）—— 其余一切的前置
3. 媒体基建（OSS 双桶 + STS 直传 + 签名 URL + 阿里 CDN）
4. triage（DB 状态机 + @Async + Gemini Developer API + **安全规则层** + 绿/黄/红结果）
5. consult（IM 编排 + 会话状态机 + 在线态 + 评分门）
6. content + profile（Feed 硬过滤 + 两级评论 + 时间线 + H5 名片 Thymeleaf + OG 预渲染图）
7. notify（IM 离线推送 + 深链路由 + 通知中心）+ moderation（举报人工队列）
8. me/i18n（注销级联删除 + 双语）

**Cross-Component Dependencies：**
- auth 的 JWT/role 是所有写操作与角色门控的根；游客态贯穿 Feed/详情只读。
- 媒体 STS + 签名 URL 被 triage(私密图)、profile(档案/名片图)、content(Feed 图) 共用。
- 安全规则层挂在 triage 结果后置，红色态联动 UX 半屏告警 + 零变现入口。
- IM 编排 + 状态机串起 consult/notify/vet；存档桥接(IM→OSS)连接 consult→profile。
- 深链路由表(go_router) 连接 notify 推送 → content 详情/consult 会话/评分。

## Implementation Patterns & Consistency Rules

### Pattern Categories Defined

识别出约 30 个 AI agent 可能产生分歧的冲突点，跨命名/结构/格式/通信/过程五类，统一约定如下。**核心映射链**：DB `snake_case` ↔ Java/Dart `camelCase` ↔ JSON `camelCase`，由 JPA + Jackson 自动桥接。

### Naming Patterns

**数据库（PostgreSQL，全 snake_case）：**
- 表名：复数 snake_case —— `users`、`pet_profiles`、`triage_tasks`、`consult_sessions`、`content_posts`、`comments`、`notifications`。
- 列名：snake_case —— `created_at`、`pet_id`、`danger_level`。
- 主键：`id`（`bigint` 自增/序列）。外键：`<引用单数>_id` —— `pet_id`、`author_id`。
- 时间戳：`created_at` / `updated_at`（`timestamptz`，**一律 UTC**）；需软删处 `deleted_at`（如 content_posts）。
- 索引 `idx_<表>_<列>`；唯一 `uq_<表>_<列>`；外键约束 `fk_<表>_<引用>`。
- 枚举落库为 `varchar` + UPPER_SNAKE 值（如 `danger_level ∈ {GREEN,YELLOW,RED}`、`status ∈ {PENDING,PROCESSING,DONE,FAILED}`）。
- Flyway 脚本：`V<序号>__<描述>.sql`（如 `V1__init_auth.sql`）。

**API（REST）：**
- 资源路径：小写复数名词，多词用连字符 —— `/api/v1/pet-profiles`、`/api/v1/triage-tasks/{id}`、`/api/v1/consult-sessions/{id}/messages`。
- 路径参数 `{id}`（Spring 风格）；查询参数 camelCase（`?petStatus=A&cursor=...`）。
- 公开/未授权暴露处用不可枚举 token，不暴露顺序 id —— `/p/{cardToken}`、深链用 token。
- 自定义头：`Idempotency-Key`、`Accept-Language`（驱动 id/en）。

**Java 代码：**
- 类 PascalCase、方法/字段 camelCase、常量 UPPER_SNAKE、包全小写。
- 分层后缀：`XxxController` / `XxxService` / `XxxRepository` / 实体 `Xxx`（JPA `@Entity`）/ `XxxRequest`·`XxxResponse`（DTO，record）/ `XxxMapper`。
- 包按模块再分层：`com.petgo.<module>.{web,service,domain,repository,dto,event}`（module ∈ auth/triage/consult/content/profile/notify/moderation/vet）。
- 领域事件 `XxxEvent`（过去式语义，如 `TriageSubmittedEvent`）。

**Dart / Flutter：**
- 文件 snake_case（`pet_profile_card.dart`）；类 PascalCase；成员 camelCase；常量 lowerCamel/`kXxx` 不强制。
- Riverpod provider 命名 `xxxProvider`；状态类 `XxxState`（不可变，copyWith/freezed）。
- 特性目录：`lib/features/<feature>/{data,domain,presentation}`；跨特性放 `lib/core/`（网络/路由/主题/l10n）与 `lib/shared/`（通用 widget）。

### Structure Patterns

**后端（package-by-feature → layer）：**
```
src/main/java/com/petgo/
  <module>/{web,service,domain,repository,dto,event}
  shared/{config,security,error,async,media,im}   # 跨模块基础设施
src/main/resources/db/migration/                    # Flyway
src/main/resources/templates/                       # Thymeleaf H5 名片
src/test/java/com/petgo/<module>/...                # 镜像结构
```

**Flutter（feature-first）：**
```
lib/
  core/{network,router,theme,l10n,storage}
  shared/{widgets,utils}
  features/<feature>/{data,domain,presentation}
  l10n/{app_en.arb, app_id.arb}
test/features/<feature>/...                          # 镜像 + 关键路径优先
```

### Format Patterns

**API 响应：**
- 成功响应**不包通用 wrapper**，直接返回资源对象/数组。
- 列表分页：**游标式**（承接 Feed 无限滚动 20/批）—— `{ "items": [...], "nextCursor": "...", "hasMore": true }`。
- 字段命名 **camelCase**；时间 **ISO-8601 UTC 字符串**（`2026-06-01T10:00:00Z`）；金额/计数为数字；`null` 字段**省略**（Jackson `NON_NULL`）。
- 资源 `id`：已授权资源可暴露数字 `id`；对外公开资源用 `token`（见命名）。

**错误响应（统一 RFC 9457 ProblemDetail）：**
```json
{ "type":"https://petgo/errors/validation", "title":"Validation Failed",
  "status":422, "detail":"昵称超过 20 字", "instance":"/api/v1/...",
  "traceId":"...", "errors":[{"field":"nickname","message":"≤20"}] }
```
- HTTP 语义：200 读/改成功 · 201 创建 · 202 异步受理（分诊提交）· 400/422 校验 · 401 未认证 · 403 越权 · 404 不存在 · 409 冲突/重复 · 429 限流 · 5xx 服务端。

### Communication Patterns

**事件与异步（进程内）：** `ApplicationEventPublisher` 发 `XxxEvent`（不可变 record）→ `@Async @EventListener` 消费；可靠性靠 DB 状态机（`status` + `retry_count` + 启动重扫），**不引入 MQ**。状态枚举 UPPER_SNAKE。
**会话状态机（consult）：** `WAITING → IN_PROGRESS → PENDING_CLOSE → CLOSED / INTERRUPTED`，外加 `WAITING → CANCELLED`（等待中用户主动取消，Story 5.3 引入的第 6 态）；超时(1min)、评分门(30min)为状态迁移触发器，全在 Java 侧编排 IM。
**前端状态（Riverpod）：** 一律不可变更新（copyWith/freezed）；异步用 `AsyncValue<T>`（loading/data/error 三态）；副作用进 provider，不写在 widget build 内。
**日志：** SLF4J + logback JSON；级别 ERROR(可行动)/WARN(降级)/INFO(业务事件)/DEBUG(开发)；MDC 带 `traceId`、`userId`；**严禁记录 PII/健康数据/令牌/签名 URL**。

### Process Patterns

**错误处理：** 后端 `@RestControllerAdvice` 统一转 ProblemDetail，绝不外泄堆栈；客户端 dio 拦截器把 ProblemDetail → 本地化文案，按 UX 呈现（网络顶部 banner 5s 自消 / 上传底部 toast 3s / 兽医忙卡片态）。
**加载态：** Feed=骨架屏；AI 分析中=全屏 spinner + 文案；上传=区域内进度条（均见 UX 规范）。
**重试与幂等：** 分诊失败 DB 状态机重试 ≤3；客户端上传仅重传失败件；提交/发布带 `Idempotency-Key` 去重。
**鉴权流：** 401 → 用 refresh 静默续期一次 → 重放原请求；失败则落游客态/登录引导（FR-0C）。游客触发受控功能即弹 FR-0C。
**校验时机：** 服务端 Bean Validation 于 Controller（权威）；客户端实时预校验仅为体验（字数 FR-12/0E、文件规格 FR-1）。

### Enforcement Guidelines

**所有 AI Agent 实现时 MUST：**
- 遵循上述 DB/API/代码命名表；DB snake_case、JSON camelCase、时间 UTC ISO。
- 错误一律 ProblemDetail；HTTP 状态码按语义表。
- 后端 package-by-feature + 分层后缀；Flutter feature-first + AsyncValue + 不可变状态。
- 异步只用 `@Async`+DB 状态机，**禁止擅自引入 MQ/缓存层/新中间件**（违背 V1 轻量姿态）。
- **绝不改动/绕过 AI 安全规则层的"只升不降"语义**；红色态零变现入口。
- 日志与对象存储 URL 不落 PII/健康数据；私密图只走签名 URL。
- 新增对外暴露标识一律用不可枚举 token。

**Pattern Examples：**
- ✅ 表 `triage_tasks` / 列 `danger_level` / JSON `{"dangerLevel":"YELLOW","createdAt":"...Z"}` / 实体 `TriageTask` / DTO `TriageResultResponse` / provider `triageResultProvider`。
- ❌ 反例：JSON 用 snake_case（与 Dart/Java 不一致）；成功响应包 `{data,error}` wrapper；时间用本地时区或时间戳数字；在 Controller 抛裸 `RuntimeException` 不转 ProblemDetail；为某功能"顺手"加个 Kafka/Caffeine。

## Project Structure & Boundaries

### Complete Project Directory Structure

**后端 —— petgo-backend（Spring Boot 模块化单体）**
```
petgo-backend/
├── pom.xml                         # Maven, SB4.0.x, Java21
├── README.md
├── .env.example                    # OAuth/Gemini/IM/OSS/DB/Redis 凭证占位
├── Dockerfile
├── docker-compose.yml              # app + postgres + redis（德国单机）
├── .github/workflows/ci.yml        # build jar+镜像 → 部署德国机
├── src/main/java/com/petgo/
│   ├── PetgoApplication.java
│   ├── auth/        {web, service, domain, repository, dto, event}   # FR-0A~0H,19,29
│   ├── triage/      {web, service, domain, repository, dto, event}   # FR-1~3,4A,5
│   │   └── service/{TriageService, SafetyRuleLayer, TriageTaskScanner}
│   ├── consult/     {web, service, domain, repository, dto, event}   # FR-4B,30~33
│   │   └── domain/{ConsultSession, SessionStatus, RatingGate}
│   ├── content/     {web, service, domain, repository, dto, event}   # FR-12,17,18,23,24,25,28,36
│   ├── profile/     {web, service, domain, repository, dto, event}   # FR-11,14~16,37,39
│   │   └── web/{ProfileApiController, CardPageController(Thymeleaf)}  # FR-14 H5
│   ├── notify/      {web, service, domain, repository, dto, event}   # FR-22*,34,38
│   ├── moderation/  {web, service, domain, repository, dto, event}   # FR-25
│   ├── vet/         {web, service, domain, repository, dto, event}   # FR-29~33（兽医工作台视图）
│   └── shared/
│       ├── config/     {AsyncConfig, RedisConfig, OpenApiConfig, JacksonConfig, WebConfig}
│       ├── security/   {SecurityConfig, JwtService, JwtAuthFilter, GoogleTokenVerifier, RoleGuard}
│       ├── error/      {GlobalExceptionHandler→ProblemDetail, AppException, ErrorTypes}
│       ├── async/      {DomainEvent基类, TaskStatus枚举, RetryScanner}
│       ├── media/      {AliyunOssClient, StsService, SignedUrlService, ImToOssArchiver}
│       ├── im/         {TencentImClient, ImCallbackController}        # 建会话/系统消息/封禁/回调
│       ├── ai/         {GeminiClient}                                # Developer API, gemini-2.5-flash
│       └── ratelimit/  {RedisRateLimiter, IdempotencyService}
├── src/main/resources/
│   ├── application.yml / application-dev.yml / application-prod.yml
│   ├── db/migration/   V1__init_auth.sql, V2__init_profile.sql, ...   # Flyway
│   ├── templates/      card.html                                      # H5 名片 + OG meta
│   └── safety/         high_risk_symptoms.yml                         # 安全规则层清单（兽医顾问维护）
└── src/test/java/com/petgo/<module>/...                               # 镜像，关键路径优先
```

**移动端 —— petgo_app（Flutter feature-first）**
```
petgo_app/
├── pubspec.yaml
├── l10n.yaml
├── analysis_options.yaml           # flutter_lints + riverpod_lint + custom_lint
├── .github/workflows/ci.yml
├── lib/
│   ├── main.dart  /  app.dart       # ProviderScope + MaterialApp.router
│   ├── core/
│   │   ├── network/   {dio_client, auth_interceptor, problem_detail, api_paths}
│   │   ├── router/    {app_router(go_router), deep_link_routes}       # FR-38 深链表
│   │   ├── theme/     {colors, typography, spacing, elevation}        # UX_DESIGN tokens
│   │   ├── l10n/      {generated}
│   │   └── storage/   {secure_storage(token), prefs(lang/petStatus/onboarding)}
│   ├── shared/widgets/ {bottom_tab_bar(5位凸起+), publish_fab, triage_result_card,
│   │                    masonry_card, red_alert_overlay, mini_profile_sheet, empty_state}
│   ├── features/
│   │   ├── auth/      {data,domain,presentation}    # Google登录/游客态/引导/兽医登录
│   │   ├── triage/    {data,domain,presentation}    # 上传/分析中/绿黄红结果/红色半屏
│   │   ├── consult/   {data,domain,presentation}    # 腾讯 IM SDK 聊天/队列/评分
│   │   ├── content/   {data,domain,presentation}    # Feed瀑布/详情/两级评论/发布/举报
│   │   ├── profile/   {data,domain,presentation}    # 档案/时间线/名片分享FAB
│   │   ├── notify/    {data,domain,presentation}    # 通知中心/角标
│   │   ├── me/        {data,domain,presentation}    # 设置/语言/注销
│   │   └── vet/       {data,domain,presentation}    # 兽医工作台独立Tab
│   └── l10n/ {app_en.arb, app_id.arb}
└── test/features/<feature>/...
```

### Architectural Boundaries

**API 边界：**
- `/api/v1/**`：JSON REST，默认需 JWT；例外 = Feed/详情**只读**对游客可见（FR-0A/17）。
- `/p/{cardToken}`：**公开 H5 名片**（Thymeleaf 直出 + OG，noindex），无需鉴权，仅展示快乐时刻最近 5 条。
- `/im/callback`：腾讯 IM 服务端回调（签名校验，内网/白名单）。
- `/actuator/**`：健康/指标，仅内网。
- 全部经 Cloudflare 回源；源站仅放行 CF IP。

**模块边界（单体内）：**
- 模块间**只经 service 接口或领域事件**通信，**禁止跨模块直接访问对方 repository**。
- 跨模块副作用走进程内事件：如 `ConsultClosedEvent` → profile 订阅做存档桥接；`ContentLikedEvent` → notify 推送。
- `shared/` 仅放跨模块基础设施（安全/错误/异步/媒体/IM/AI/限流），不放业务。

**数据边界：**
- 每模块拥有自己的表；跨模块读取经 owning service，不在他模块 repository 里 join 对方表。
- 私密数据（分诊图/健康历史）仅 ②私密桶 + 签名 URL；公开图 ①公开桶 + 阿里 CDN；聊天媒体留 IM。
- Redis 仅 auth 限流 / 幂等 / 兽医在线态 / 队列态 / 角标。

**前端边界：**
- `presentation` 只依赖 `domain`（实体+用例 provider）；`data`（repository 实现 + dio + DTO）实现 domain 抽象；不跨 feature 直接 import 对方 presentation。
- 全局跨切面（路由/网络/主题/存储/l10n）在 `core/`；通用 UI 在 `shared/widgets/`。

### Requirements to Structure Mapping

| FR 簇 | 后端 | 前端 |
|------|------|------|
| FR-0A~0H,19,29 登录/引导/导航/兽医登录 | `auth/` + `shared/security/` | `features/auth/` + `shared/widgets/bottom_tab_bar` |
| FR-1~3,4A,5 AI 分诊 | `triage/`（含 `SafetyRuleLayer`）+ `shared/ai`,`media` | `features/triage/` |
| FR-4B,30~33 兽医咨询/工作台/评分 | `consult/` + `vet/` + `shared/im` | `features/consult/` + `features/vet/` |
| FR-12,17,18,23~25,28,36 内容/Feed/互动/举报 | `content/` + `moderation/` | `features/content/` |
| FR-11,14~16,37,39 档案/名片 | `profile/`（含 `CardPageController`）+ `templates/card.html` | `features/profile/` |
| FR-22*,34,38 推送/通知中心/深链 | `notify/` + `shared/im`(离线推送) | `features/notify/` + `core/router/deep_link_routes` |
| FR-20,21,27 个人中心/注销/双语 | `auth/`,`profile/` + 级联删除作业 | `features/me/` + `core/l10n` |

**跨切面：** 鉴权门控 `shared/security` ↔ `core/network/auth_interceptor`；错误 `shared/error`(ProblemDetail) ↔ `core/network/problem_detail`；i18n `Accept-Language` ↔ `core/l10n`；安全规则层 `triage/service/SafetyRuleLayer` + `resources/safety/high_risk_symptoms.yml`。

### Integration Points

**内部通信：** REST(客户端→后端) + 进程内领域事件(`@Async @EventListener`) + DB 状态机(可靠异步)。
**外部集成：**
- Google OAuth → `shared/security/GoogleTokenVerifier`
- Gemini Developer API(gemini-2.5-flash) → `shared/ai/GeminiClient`（triage 调用，签名 URL 拉私密图）
- 腾讯云 IM → `shared/im/TencentImClient`（建会话/系统消息/封禁）+ `ImCallbackController` + 离线推送
- 阿里云 OSS(雅加达, 双桶)/STS/CDN → `shared/media/*`；客户端 STS 直传
- Cloudflare 边缘 → 部署/网络层（TLS/WAF/隐源 IP/CDN for API+H5）

**数据流（关键三条）：**
1. **分诊**：Flutter 压缩图 → STS 直传②私密桶 → `POST /triage`(202+id) → `TriageSubmittedEvent` → `@Async` 调 Gemini(签名URL拉图) → 解析绿/黄/红 → **SafetyRuleLayer 后置强制升红** → 写库 status=DONE → 客户端轮询/IM 推送取结果。
2. **发布**：Flutter 压缩图 → STS 直传①公开桶 → `POST /content-posts` → 入 Feed（时间倒序，按宠物状态硬过滤读取）→ 阿里 CDN 分发图。
3. **名片分享**：生成卡片即预渲染 OG 静态图存①公开桶 → 分享 `/p/{cardToken}` → Thymeleaf 直出 + OG meta → 社交预览 → 下载引导。

### File Organization Patterns

- **配置**：后端 `application-*.yml` + env 注入；前端 `pubspec.yaml`/`analysis_options.yaml`/`l10n.yaml`。
- **源码**：后端 package-by-feature→layer；前端 feature-first（data/domain/presentation）。
- **测试**：后端 `src/test` 镜像模块；前端 `test/features` 镜像；关键路径优先（分诊状态机、安全规则层、登录门控、深链路由、注销级联）。
- **资产**：DB 迁移 `db/migration`；H5 模板 `templates/`；安全清单 `safety/high_risk_symptoms.yml`；前端图标/字体随主题 `core/theme`。

### Development Workflow Integration

- **开发**：`docker-compose up`(PG+Redis) + `mvn spring-boot:run`；`flutter run`（dev profile 指向本地后端）。
- **构建**：GitHub Actions → 后端 `mvn package` 出 jar + 镜像；前端 `flutter build apk/ipa`。
- **部署**：镜像推送 → 德国机 compose pull 重启；Cloudflare 前置不变；Flutter V1 手动上架。

## Architecture Validation Results

### Coherence Validation ✅

- **技术兼容**：SB4.0.x + Java21 + springdoc(SB4 兼容) + JPA/PostgreSQL/Flyway + Redis + Riverpod/go_router/dio + Gemini 2.5-flash + 腾讯 IM + 阿里 OSS——版本与组合均验证可用；不用 HATEOAS 规避了 springdoc/Jackson3 已知坑。
- **模式一致**：DB snake_case ↔ JSON camelCase ↔ Java/Dart camelCase 映射链自洽；ProblemDetail/状态码/游标分页/UTC ISO 贯穿前后端；异步统一 @Async+DB 状态机，与"无 MQ"决策一致。
- **结构对齐**：8 模块边界（事件/service 通信，禁跨 repo）↔ 前端 feature-first ↔ FR 映射表三者闭合；shared/ 与 core/ 承载跨切面无业务泄漏。

### Requirements Coverage Validation

- **FR 覆盖（39/39 有架构落点）**：登录引导(auth)、AI 分诊+安全层(triage)、兽医咨询/工作台/评分(consult+vet)、内容/Feed/互动/举报(content+moderation)、档案/名片(profile)、推送/通知/深链(notify)、个人中心/双语/注销(auth+profile)。逐簇可追溯至目录（见 §结构映射）。
- **NFR 覆盖**：≤15s 分诊(异步单次调用)✅ · ≤3s H5(Thymeleaf+CDN+预渲染 OG)✅ · 实时聊天(TIM)✅ · 安全攸关(确定性规则层)✅ · 隐私/信任六地基✅ · i18n✅ · 降级可靠性(DB 状态机重试)✅。
- **跨地域延迟**：作为已接受风险处理（BFF 聚合+边缘+IM 绕行），非阻塞。

### Implementation Readiness Validation

- **决策完整**：关键决策均带版本与理由；6 个原待拍板项全部收敛。
- **结构完整**：双产物目录树具体到关键文件；集成点/边界/数据流明确。
- **模式完整**：约 30 冲突点覆盖；命名/格式/通信/过程均有规则 + 正反例。

### Gap Analysis Results

| # | 等级 | 缺口 | 处置 |
|---|------|------|------|
| G-1 | 🟠 重要 | **运营/Admin 能力缺架构落点**：PRD 多处假设运营后台存在——FR-18 种子内容发布、FR-25 举报审核、FR-29 兽医账号创建、FR-33 评分查看、FR-19 封禁兽医。当前架构未定义 admin。 | **本步补齐**：在后端增设 `admin` 跨切面 slice——`role=ADMIN` 的一组受保护端点 + 极简 Thymeleaf 后台页（复用现有模块 service）：兽医账号 CRUD、举报工单处理/下架、种子内容发帖、评分查看、封禁。不做独立系统，V1 够用。 |
| G-2 | 🟡 次要 | **FR-5 历史判断摘要匹配** 需关键词匹配历史问诊库，冷启动为空。 | 已被 PRD 覆盖（冷启动仅给 AI 参考回复）；V1 先实现 AI 参考回复，历史匹配作为 consult 内查询后置增强。 |
| G-3 | 🟡 次要/挂账 | **Gemini Developer API 数据出境**：默认经 Google 全球基础设施，叠加德国出境。 | 与"暂缓 PDP 跨境"一致挂账；如未来收紧，迁 Vertex(新加坡 Region + 数据控制)，接口层已抽象 `GeminiClient` 便于切换。 |
| G-4 | 🟡 次要 | **上传图 EXIF/GPS 未剥离**，H5 公开页有定位泄漏风险。 | 补规则：媒体上传（客户端或后端）**剥离 EXIF GPS**；归入 `shared/media` 处理。 |

> 无 🔴 Critical 阻断项。G-1 已在本步以 `admin` slice 补齐架构落点；G-4 补入媒体处理规则；G-2/G-3 为已知低风险/挂账。

### Validation Issues Addressed

- **G-1**：新增 `com.petgo.admin/`（`role=ADMIN` 端点 + Thymeleaf 简易后台），FR-18/25/29/33/封禁均挂此 slice，复用各模块 service，不引入新系统。
- **G-4**：`shared/media` 上传链路增加 EXIF GPS 剥离。

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
- [x] Performance considerations addressed

**Implementation Patterns**
- [x] Naming conventions established
- [x] Structure patterns defined
- [x] Communication patterns specified
- [x] Process patterns documented

**Project Structure**
- [x] Complete directory structure defined（含本步补入的 `admin` slice）
- [x] Component boundaries established
- [x] Integration points mapped
- [x] Requirements to structure mapping complete

### Architecture Readiness Assessment

**Overall Status:** READY FOR IMPLEMENTATION（16/16 勾选，无 Critical Gap；G-1 已补齐，G-2/3/4 为次要/挂账，不阻断）
**Confidence Level:** high
**Key Strengths：**
- 与"快速上线、≤500 DAU、不过度设计"硬约束高度对齐：单体 + 少中间件 + 无 MQ，小队可整体掌控。
- 安全/信任/合规六地基与规模解耦，确定性安全层独立兜底 AI 假阴性。
- 模块边界 = 未来拆分线；外部依赖收敛、跨云交互点极少。
**Areas for Future Enhancement：**
- 亚太只读副本/后端就近（延迟成瓶颈时）· 正式 MQ(量级上来) · Vertex 迁移(企业数据控制) · 自动化内容审核 · PDP 跨境合规 · 内容视频/VOD(V2)。

### Implementation Handoff

**AI Agent Guidelines：** 严格遵循已记录的决策与一致性规范；尊重模块/feature 边界；异步只用 @Async+DB 状态机（禁擅自加中间件）；绝不绕过安全规则层"只升不降"语义；私密图只走签名 URL；新增对外标识用不可枚举 token。
**First Implementation Priority：** 执行两条脚手架 init 命令（petgo-backend / petgo_app）+ Docker Compose(PG/Redis) + Cloudflare 接入 + CI 骨架，作为第一个 Story；随后按实现序列 auth→media→triage→consult→content/profile→notify/moderation→me/i18n，admin slice 穿插。
