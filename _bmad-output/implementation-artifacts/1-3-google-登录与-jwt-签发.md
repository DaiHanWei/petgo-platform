# Story 1.3: Google 登录与 JWT 签发

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **新/老用户**,
I want **用 Google 账号一键登录**,
so that **我无需记密码即可获得身份并使用核心功能**。

> 本 Story 是 **auth 模块的核心地基**，架构 §Implementation Sequence 第 2 步「auth 是其余一切的前置」。范围：① 前端集成 Google OAuth（系统账号选择器）取 ID Token；② 后端 `GoogleTokenVerifier` 校验 Google ID Token → 首次授权自动建号（**本 Story 创建 `users` 表**，取 Google ID/Email/Display Name/头像）→ 签发自有 JWT（access 短时 + refresh 轮换，`role=user`）；③ 客户端 `flutter_secure_storage` 存令牌 + dio `auth_interceptor` 注入/401 静默续期一次重放；④ 登录按钮下方《服务条款》《隐私政策》Text Link（FR-0D，无需勾选）；⑤ 新老用户分流（老用户直接进 App，新用户进 Story 1.6 引导）。
>
> **依赖**：Story 1.1（后端脚手架 + `shared/security` 空壳 + `shared/error` ProblemDetail + Flyway；前端 `core/network`/`core/storage` 空壳）、Story 1.2（前端外壳 + i18n，登录页文案走 .arb）。
> **被依赖**：Story 1.4（登录引导调用本 Story 登录 + 回跳）、Story 1.5（游客态门控 + dio 401→登录引导）、Story 1.6（新用户引导消费本 Story 分流信号 + 写 `pet_status`）。
>
> **不做**：兽医账密登录（FR-29，Epic 5）、昵称确认/宠物状态选择页（Story 1.6，本 Story 只产出「新用户」分流信号与 `users` 表中 `pet_status` 可空字段）、登录引导浮层/弹窗组件（Story 1.4，本 Story 提供一个最小登录入口页用于自测）。

## Acceptance Criteria

> **验证层标注**：每条 AC 末尾标注验证层级与所需本地环境——
> **L0 静态**（编译/lint/单元/MockMvc，无外部依赖；Google 校验/JWT 用 stub/mock） · **L1 集成/运行时**（需 Docker daemon + postgres + redis，验 `users` 表写入/Flyway/refresh 轮换持久化/限流） · **L2 端到端/外部凭证**（需**真实 Google OAuth 凭证 + 真机/模拟器**完成账号选择器授权全链路）。
> 本 Story 含全部三层；**AC1 主路径含 L2**（真实 Google 授权），这是 Epic 1 第一处 L2 节点。

### AC1 — Google 登录 → 校验 ID Token → 建号 → 签发 JWT → 安全存储

**Given** 用户在登录入口
**When** 点击「Google 登录」并完成系统账号选择器授权
**Then** 后端校验 Google ID Token 通过后，首次授权自动创建账户（写 `users` 表，本故事创建该表），获取 Google ID/Email/Display Name/头像
**And** 后端签发自有 JWT（access 短时 + refresh 轮换，`role=user`），客户端存入 `flutter_secure_storage`
> 验证层：**L2**（真机/模拟器 + 真实 Google OAuth client：完整账号选择器授权 → 拿 idToken → 后端建号 → 返回 JWT → secure_storage 落盘）。**降级 L0/L1**：后端 `GoogleTokenVerifier` 用注入的 stub verifier（伪 idToken）+ MockMvc 验 `POST /api/v1/auth/google` 返回 access/refresh + role=user（L0）；`users` 表实际写入 + Flyway `V2__init_auth.sql` 迁移成功需 postgres（L1）。

### AC2 — 服务条款 / 隐私政策 Text Link（FR-0D）

**Given** Google 登录按钮区域
**When** 页面渲染
**Then** 按钮下方展示「登录即表示同意《服务条款》和《隐私政策》」，两份均为可点击链接跳转 H5（FR-0D，Text Link 模式，无需勾选）
> 验证层：**L0**（widget test 断言文案 + 两个可点 link 存在、文案走 .arb 双语、**无勾选框**）+ **L2(模拟器)**（点击 link 打开外部 H5/WebView）。

### AC3 — access 过期 → dio 拦截器 refresh 静默续期一次并重放

**Given** 已登录用户
**When** access token 过期触发 401
**Then** dio 拦截器用 refresh 静默续期一次并重放原请求；续期失败则落游客态（游客态门控见 Story 1.5）（NFR-12）
> 验证层：**L0**（前端 dio interceptor 单元测试：mock 401→调 refresh→重放原请求成功；refresh 也失败→清 token 落游客态、**只续期一次不死循环**）+ **L1**（后端 `POST /api/v1/auth/refresh` 真实校验 refresh + 轮换：旧 refresh 失效、发新 refresh，需 postgres/redis 持久化轮换状态）。

### AC4 — 新老用户分流

**Given** 授权成功后的用户分流
**When** 判定新老用户
**Then** 老用户直接进入 App（不进入昵称/状态引导）；新用户进入注册引导流程（Story 1.6 昵称 + 宠物状态），引导完成后回到触发点
> 验证层：**L0**（后端登录响应含 `isNewUser`/`onboardingCompleted` 标志，MockMvc 验首次建号=新用户、二次登录=老用户；前端按标志路由——新→引导占位、老→App）+ **L1**（二次登录命中已存在 `users` 行判定老用户，需 postgres）。本 Story 只产出**分流信号 + 路由分叉**，引导页本体在 Story 1.6。

---

## Tasks / Subtasks

> **按"后端子任务 / 前端子任务 / 联调验收"三段组织**。本 Story 后端重（OAuth 校验/JWT/users 表/SecurityConfig）、前端中（登录 UI/secure_storage/dio 拦截器/分流路由）。执行顺序：后端 → 前端 → 联调。

### 🟦 后端子任务（petgo-backend / Spring Boot）

- [ ] **B1. `users` 表 Flyway 迁移（本 Story 创建）** (AC: 1, 4)
  - [ ] `src/main/resources/db/migration/V2__init_auth.sql`（命名严格 `V<序号>__<描述>.sql`，序号接 1.1 基线之后）。
  - [ ] 表 `users`：`id bigserial PK`、`google_sub varchar UNIQUE NOT NULL`（Google ID/sub）、`email varchar`、`display_name varchar`、`avatar_url varchar`、`nickname varchar`（≤20，初始可同 display_name，Story 1.6 确认）、`pet_status varchar NULL`（`A|B|C`，Story 1.6 写）、`onboarding_completed boolean NOT NULL DEFAULT false`、`role varchar NOT NULL DEFAULT 'USER'`、`created_at`/`updated_at timestamptz`（**UTC**）、`deleted_at timestamptz NULL`（备注销匿名化，Epic 7）。
  - [ ] 约束/索引：`uq_users_google_sub`；枚举落库 `varchar` + UPPER（`role ∈ {USER,VET,ADMIN}`，本 Story 只用 USER；`pet_status ∈ {A,B,C}`）。
  - [ ] `ddl-auto=validate` 下迁移须与实体严格匹配。
- [ ] **B2. User 实体 + Repository** (AC: 1, 4)
  - [ ] `com.petgo.auth.domain.User`（JPA `@Entity`，字段 camelCase ↔ 列 snake_case 由 JPA 桥接）。
  - [ ] `com.petgo.auth.repository.UserRepository`（`findByGoogleSub`）。
- [ ] **B3. Google ID Token 校验（shared/security/GoogleTokenVerifier）** (AC: 1)
  - [ ] 校验 Google ID Token（验签/aud=本应用 client、iss、exp）；解析 sub/email/name/picture。用 Google 官方库或 JWKS 校验。
  - [ ] **抽象为接口**便于测试注入 stub（L0 用伪 verifier）；client id/secret **env 注入**（`GOOGLE_OAUTH_CLIENT_ID` 等，**绝不入库**）。
- [ ] **B4. JWT 签发与刷新（shared/security/JwtService + RefreshToken）** (AC: 1, 3)
  - [ ] `JwtService`：签 access（短时，如 15min，含 `sub=userId`、`role`、`exp`）+ refresh（长时 + **轮换**：每次刷新作废旧 refresh、发新 refresh）。签名密钥 env 注入。
  - [ ] refresh 轮换持久化：可建轻量 `refresh_tokens` 表或在 Redis 存 refresh 句柄（**Redis 仅用于 auth 场景，符合收窄边界**；二选一在 Completion Notes 记录）。轮换=旧句柄失效防重放。
  - [ ] `OAuth2 Resource Server` 配置校验**自签 JWT**（非 Google token）：本应用 JWT 作为后续所有受保护端点的鉴权凭证，`role` claim 驱动门控。
- [ ] **B5. Auth Controller + DTO** (AC: 1, 3, 4)
  - [ ] `auth/web/AuthController`：
    - `POST /api/v1/auth/google`（body `{idToken}`）→ 校验→建号或取号→签 JWT → 返回 `LoginResponse{accessToken, refreshToken, role, isNewUser, onboardingCompleted, profile{nickname,avatarUrl,...}}`。HTTP 200（已存在）/ 视实现 200 统一。
    - `POST /api/v1/auth/refresh`（body `{refreshToken}`）→ 轮换 → 返回新 `{accessToken, refreshToken}`；refresh 失效 → 401 ProblemDetail。
  - [ ] DTO 为 record（`GoogleLoginRequest`/`RefreshRequest`/`LoginResponse`/`TokenResponse`）；camelCase；null 省略。
- [ ] **B6. SecurityConfig 角色门控（放行 auth + actuator/docs，其余需 JWT）** (AC: 1, 3, 4; 支撑 NFR-12)
  - [ ] `shared/security/SecurityConfig`：`POST /api/v1/auth/**` permitAll；`/actuator/**`、`/v3/api-docs/**`、`/swagger-ui/**`、`/p/**` 放行；**其余 `/api/v1/**` 默认需认证**（Story 1.1 阶段的「全放行」在此 Story 收紧为「auth 放行 + 其余需 JWT」）。
  - [ ] `role` claim → Spring authority 映射（`ROLE_USER`）；为 Story 1.5 游客只读端点预留 permitAll 锚点（Feed/详情只读，本 Story 不实现具体端点，仅约定）。
  - [ ] ⚠️ 不实现兽医/admin 门控（Epic 5/横切），但 role 枚举与映射结构要能容纳 VET/ADMIN。
- [ ] **B7. 限流（auth 写端点，Redis 令牌桶）** (AC: 1; 支撑 NFR-12)
  - [ ] `POST /api/v1/auth/google`、`/auth/refresh` 接 `shared/ratelimit/RedisRateLimiter`（令牌桶）；超限 429 ProblemDetail。Redis 仅 auth 限流用途（符合收窄边界）。
- [ ] **B8. 错误与日志规范对齐** (AC: 1, 3)
  - [ ] 校验失败/token 失效统一走 `shared/error` ProblemDetail（401/422/429 语义）；**日志严禁记录 idToken/JWT/email 等 PII**（架构 §日志）。

### 🟩 前端子任务（petgo_app / Flutter）

- [ ] **F1. Google OAuth 集成（features/auth/data）** (AC: 1)
  - [ ] 引入 `google_sign_in`（或等价）插件；配置 iOS/Android OAuth client（GoogleService/Info.plist/strings，client id **env/配置注入**，不硬编码进源码仓的敏感处）。
  - [ ] `auth/data` repository：触发系统账号选择器 → 取 `idToken` → 调 `POST /api/v1/auth/google` → 收 `LoginResponse`。
- [ ] **F2. 令牌安全存储（core/storage/secure_storage）** (AC: 1, 3)
  - [ ] `flutter_secure_storage` 存 access/refresh；`shared_preferences` 存非敏感（语言/petStatus/引导计数——petStatus 在 Story 1.6 写）。
  - [ ] 登录态 provider（`authStateProvider`：游客 / 已登录(role) / 新用户待引导），不可变 `AsyncValue`。
- [ ] **F3. dio 拦截器（core/network/auth_interceptor）** (AC: 3; 支撑 NFR-12)
  - [ ] 请求拦截：注入 `Authorization: Bearer <access>` + `Accept-Language`（驱动后端 id/en）+ 写端点 `Idempotency-Key`（结构预留）。
  - [ ] 响应拦截：401 → 调 `/auth/refresh` 静默续期**一次** → 重放原请求；**只续期一次**（加重入锁/标志位防死循环）；refresh 也失败 → 清 token、落游客态（门控/弹引导在 Story 1.5 接，本 Story 仅置游客态）。
  - [ ] ProblemDetail 解析（`core/network/problem_detail`）→ 本地化错误文案。
- [ ] **F4. 登录入口页（features/auth/presentation，最小可自测）** (AC: 1, 2)
  - [ ] 一个最小 `LoginPage`：「Google 登录」按钮（区域色，调 F1 流程）+ 按钮下方 Text Link「登录即表示同意《服务条款》和《隐私政策》」（两个可点 link → 打开 H5/WebView，**无勾选框**，FR-0D）。
  - [ ] 全部文案走 .arb（id/en）；样式引用 Story 1.2 token。
  - [ ] ⚠️ 这是**最小自测入口**；正式触发场景（软浮层/强弹窗）是 Story 1.4，本页可被其复用或仅作开发自测。
- [ ] **F5. 新老用户分流路由（features/auth/presentation + core/router）** (AC: 4)
  - [ ] 登录成功后按 `LoginResponse`：`onboardingCompleted==true` → 进 App 主框架（Story 1.2 Tab 外壳）；`isNewUser || !onboardingCompleted` → 路由到**新用户引导占位**（Story 1.6 实现本体，本 Story 仅留路由分叉 + 占位页 + 回跳锚点）。
  - [ ] 回跳锚点（pendingAction）结构预留，供 Story 1.4 注入触发点后回跳——本 Story 不接具体触发源。

### 🟨 联调验收子任务（端到端跑起来 + CI）

- [ ] **J1. 后端集成验收** (AC: 1,3,4 / **L1**)
  - [ ] `docker compose up -d`（pg+redis）→ `mvn spring-boot:run` → Flyway `V2__init_auth.sql` 迁移成功（启动日志确认 `users` 表创建）。
  - [ ] MockMvc/集成测试（stub GoogleTokenVerifier）：首次 `POST /auth/google` → 201 语义建号 + 返回 JWT + `isNewUser=true`；二次同 sub → 老用户 `isNewUser=false`；`POST /auth/refresh` 轮换后旧 refresh 失效（重放旧 refresh→401）。
  - [ ] 限流：连续超阈值 `POST /auth/google` → 429 ProblemDetail。
- [ ] **J2. 前端拦截器与分流验收** (AC: 3,4 / **L0**)
  - [ ] dio 拦截器单测：401→refresh→重放成功；refresh 失败→落游客态；**只续期一次**断言。
  - [ ] 分流单测：mock `onboardingCompleted` true/false → 路由分别进 App / 进引导占位。
  - [ ] AC2 widget test：登录页两个 Text Link 存在 + 无勾选框 + 文案双语切换。
- [ ] **J3. Google 登录端到端（L2，需真实凭证）** (AC: 1 / **L2**)
  - [ ] 配置真实 Google OAuth client（dev）；真机/模拟器：点 Google 登录 → 账号选择器 → 后端建号 → 返回 JWT → secure_storage 落盘 → 进 App（老用户）或引导占位（新用户）。
  - [ ] ⚠️ 此步需外部凭证，若 CI 环境无凭证则在本地/手动验收并在 Completion Notes 记录；CI 仅跑 L0/L1。
- [ ] **J4. CI 仍绿** (AC: all / **L0+L1**)
  - [ ] 后端 CI：postgres+redis service container → `mvn -B package`（含 auth 集成测试）。
  - [ ] 前端 CI：`flutter analyze` + `flutter test`（拦截器/分流/登录页测试）+ `flutter build`。

---

## Dev Notes

### 关键架构约定（本 Story 相关部分）

**鉴权（架构 §Authentication & Security）**：
- 用户登录：Google OAuth → 后端校验 **Google ID Token** → 签发**自有 JWT（access 短时 + refresh 轮换）**，`role=user`。游客可只读浏览 Feed（FR-0A，本 Story 不实现 Feed，仅留游客态）。
- 令牌存储（客户端）：`flutter_secure_storage`（access/refresh）；`shared_preferences` 存非敏感偏好。
- 授权：Spring Security 按 `role` claim 路由/方法门控；`OAuth2 Resource Server` 校验本应用自签 JWT（**不是** Google token——Google token 仅在 `/auth/google` 一次性换取自有 JWT）。
- 密钥管理：JWT 签名密钥、Google client id/secret 全 **env 注入，不入库**。

**目录/分层（架构 §Project Structure）**：
- 后端：`com.petgo.auth/{web,service,domain,repository,dto,event}` + `com.petgo.shared/security/{SecurityConfig, JwtService, JwtAuthFilter, GoogleTokenVerifier, RoleGuard}` + `shared/ratelimit/{RedisRateLimiter, IdempotencyService}` + `shared/error`。
- 前端：`lib/features/auth/{data,domain,presentation}` + `lib/core/network/{dio_client, auth_interceptor, problem_detail, api_paths}` + `lib/core/storage/{secure_storage, prefs}`。
- 模块间只经 service/事件通信，禁跨模块直接访问对方 repository；`shared/` 不放业务。

**命名映射链**：DB snake_case（`users`/`google_sub`/`pet_status`/`created_at`）↔ Java/Dart camelCase ↔ JSON camelCase（`googleSub`/`petStatus`/`createdAt`）。枚举落库 `varchar` + UPPER（`role`/`pet_status`）。Flyway `V2__init_auth.sql`。API `/api/v1/auth/...`，自定义头 `Accept-Language`/`Idempotency-Key`。

**错误规范**：统一 RFC 9457 ProblemDetail；状态码语义 200 读/改 · 201 创建 · 401 未认证 · 403 越权 · 422 校验 · 429 限流。

**鉴权流（架构 §Process Patterns）**：401 → refresh 静默续期**一次** → 重放原请求；失败则落游客态/登录引导（FR-0C，引导组件在 Story 1.4）。

### 强制护栏（架构 §Enforcement —— 违反即返工）

- **凭证全部 env 注入，绝不入库**（Google client id/secret、JWT 签名密钥、Redis 连接）。
- **`ddl-auto=validate`**：`users` 表归 Flyway，禁 Hibernate 自动建表。
- **对外暴露标识用不可枚举 token**：本 Story 对外不暴露 `users.id` 顺序号；JWT `sub` 用内部 id 可接受（已授权上下文），但任何未来公开链接用 token。
- **Redis 仅 auth 限流 + 幂等/防重锁**（refresh 句柄属 auth 场景，可用 Redis）；**禁止把 Redis 当通用缓存/队列**。
- **日志严禁记录 PII / 令牌**：idToken、JWT、email、refresh 句柄一律不落日志（架构 §日志，MDC 仅 traceId/userId）。
- **安全规则只升不降**：本 Story 的 SecurityConfig 从 1.1「全放行」**收紧**为「auth 放行 + 其余需 JWT」——这是收紧方向，符合护栏；后续 Story 只可继续收紧，不可放松。

### 范围边界（防 scope creep —— 本 Story 明确不做）

- ❌ 不做兽医账密登录（FR-29，Epic 5）——但 `role` 枚举/映射结构预留 VET/ADMIN。
- ❌ 不实现昵称确认页 / 宠物状态选择页（Story 1.6）——本 Story 只产出 `isNewUser`/`onboardingCompleted` 分流信号 + `users.pet_status` 可空字段 + 引导占位路由。
- ❌ 不实现软浮层 / 强弹窗登录引导组件（Story 1.4）——本 Story 仅最小 `LoginPage` 自测入口。
- ❌ 不做游客态受控 Tab 门控（Story 1.5）——本 Story 拦截器失败仅「落游客态」，弹引导是 1.5。
- ❌ 不实现《服务条款》《隐私政策》H5 页面本体（运营/法务产出）——本 Story 仅 link 跳转占位 URL（env 配置）。
- ✅ 只做：Google 登录全链路 + `users` 表 + JWT 签发/refresh 轮换 + SecurityConfig 收紧 + dio 拦截器 + secure_storage + Text Link + 新老分流路由。

### 页面多态完整性（本 Story 相关态）

- Google 授权被用户取消 / 网络失败 → 登录页内联错误（顶部 banner，UX-DR10），可重试，不崩。
- idToken 校验失败（伪造/过期）→ 后端 401 ProblemDetail → 前端本地化「登录失败，请重试」。
- refresh 失效（如被注销/轮换重放）→ 落游客态（不是死循环重试）；后续受控操作触发 Story 1.4 引导。
- 限流 429 → 「操作过于频繁，请稍后再试」。

### Project Structure Notes

- 新增（后端）：`db/migration/V2__init_auth.sql`、`auth/{domain/User, repository/UserRepository, service/AuthService, web/AuthController, dto/*}`、`shared/security/{GoogleTokenVerifier, JwtService, SecurityConfig, JwtAuthFilter}`、`shared/ratelimit/RedisRateLimiter`、`.env.example` 增 `GOOGLE_OAUTH_*`/`JWT_*`。
- 新增（前端）：`features/auth/{data/auth_repository, domain/auth_state, presentation/login_page}`、`core/network/{auth_interceptor, problem_detail}`、`core/storage/secure_storage`。
- refresh 轮换存储（`refresh_tokens` 表 vs Redis 句柄）实现选择记录于 Completion Notes。

### References

- [Source: epics.md#Story 1.3] — 四组原始 AC（Given/When/Then）。
- [Source: architecture.md#Authentication & Security] — Google OAuth→自签 JWT(access 短+refresh 轮换, role=user)、secure_storage、OAuth2 Resource Server 校验自签 JWT、env 注入、限流。
- [Source: architecture.md#API & Communication Patterns] — `/api/v1`、ProblemDetail、限流 Redis 令牌桶、`Accept-Language`/`Idempotency-Key` 头。
- [Source: architecture.md#Data Architecture] — `users` 表、snake_case、对外用 token、Redis 收窄（auth 限流/幂等）。
- [Source: architecture.md#Frontend Architecture] — dio 拦截器（JWT 注入/刷新/ProblemDetail→本地化）。
- [Source: architecture.md#Process Patterns] — 401→refresh 续期一次→重放→失败落游客态。
- [Source: architecture.md#Naming Patterns] — DB/API/Java/Dart 命名、Flyway `V<序号>__`、枚举 UPPER_SNAKE。
- [Source: PRD §4 FR-0D] — Google OAuth 唯一方式、首次自动建号、Text Link 服务条款/隐私政策无需勾选。
- [Source: epics.md#Epic 1] — auth 是其余一切前置；1.3 含 L2（真实 Google）。
- [Memory: v1-architecture-posture] — 禁 MQ/通用缓存；Redis 仅 auth 限流/幂等等收窄用途。
- [Memory: spec-page-state-completeness] — 覆盖授权取消/校验失败/refresh 失效/限流多态。

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

- **L0 全绿**：后端 `./mvnw -B -DskipTests package` BUILD SUCCESS；后端单测 8/8（AuthServiceTest 5 + JwtServiceTest 3，纯 Mockito，无 DB）；前端 `flutter analyze` 零警告 + `flutter test` 18/18（含 dio 拦截器 4 例）。
- **refresh 轮换存储方案**：选用 **`refresh_tokens` 表**（非 Redis 句柄）。理由：跨重启可验证、不为核心 auth 增运行依赖、轮换语义（revoked 标志 + 重放→401）直观可测；Redis 仅留作限流（符合收窄边界）。表存 SHA-256 hash，明文绝不落库/日志。
- **Flyway 序号**：`V2__init_auth.sql`（接 V1 基线，决策 E2）。建 `users` + `refresh_tokens` 两表。
- **令牌设计**：access = HS256 自签 JWT（claim `sub=userId` / `role` / `exp` 15min，密钥 env 注入）；refresh = 256bit 不可枚举随机串，仅 hash 入库。OAuth2 Resource Server 校验**自签 JWT**（非 Google token）；`role` claim→`ROLE_USER`（结构容纳 VET/ADMIN，决策 C2）。
- **SecurityConfig 收紧**：1.1「全放行」→「`/api/v1/auth/**`+actuator+docs+`/p/**`+dev ping 放行，其余需 JWT」（只升不降）。401/403 统一 ProblemDetail（`ProblemDetailAuthHandlers`，二者不混用）。
- **限流**：`RedisRateLimiter` 固定窗口；`/auth/google` 10/min、`/auth/refresh` 30/min（按 IP）。Redis 仅 auth 用途。
- **前端拦截器**：401→单飞 refresh 续期**一次**→带新令牌重放；`retried` 标志防死循环、`skipRefresh` 防 refresh 端点自触发；失败→清 token + 落游客态（弹引导留 Story 1.5）。注入 `Bearer` + `Accept-Language`（id/en）。
- **Riverpod provider 循环**：`dioProvider`↔`authRepositoryProvider` 经显式变量类型注解打破 top-level 推断环；refresh 闭包运行期惰性 `ref.read` 避免构造期环。
- **待本地验收**：
  - **L1（需 Docker postgres+redis）**：① Flyway `V2` 迁移成功 + `users`/`refresh_tokens` 落库（ddl-auto=validate 匹配）；② MockMvc/集成：首登建号 `isNewUser=true`、二登 `false`、refresh 轮换旧句柄重放→401；③ 限流超阈值→429 ProblemDetail。后端集成测试因需真实 DB 未在云端跑（云端 `-DskipTests`），脚本就绪待本地 `mvn -B package`（pg+redis service）。
  - **L2（需真实 Google OAuth 凭证 + 真机/模拟器）**：完整账号选择器授权→拿 idToken→后端建号→返回 JWT→`flutter_secure_storage` 落盘→进 App（老）/引导占位（新）；Text Link 点击打开 H5。**dev client id 经 env 注入**（`GOOGLE_OAUTH_CLIENT_ID` 后端 / `GOOGLE_SERVER_CLIENT_ID`、`PETGO_TERMS_URL`/`PETGO_PRIVACY_URL` 前端 --dart-define），未入库；尚未配置，待本地。
  - **Android/iOS google_sign_in 原生配置**（OAuth client、Info.plist/strings、URL scheme）待本地接入真机时补。

### File List

**后端 — 新增**
- `petgo-backend/src/main/resources/db/migration/V2__init_auth.sql`
- `auth/domain/{User,Role,PetStatus,RefreshToken}.java`
- `auth/repository/{UserRepository,RefreshTokenRepository}.java`
- `auth/service/AuthService.java`
- `auth/web/AuthController.java`
- `auth/dto/{GoogleLoginRequest,RefreshRequest,TokenResponse,LoginResponse,UserProfileResponse}.java`
- `shared/security/{GoogleIdentity,GoogleTokenVerifier,NimbusGoogleTokenVerifier,AuthProperties,JwtConfig,JwtService,JwtRoleConverter,ProblemDetailAuthHandlers}.java`
- `shared/ratelimit/RedisRateLimiter.java`
- `src/test/java/com/petgo/auth/service/AuthServiceTest.java`、`src/test/java/com/petgo/shared/security/JwtServiceTest.java`

**后端 — 修改**
- `shared/security/SecurityConfig.java`（收紧门控 + JWT resource server）
- `shared/error/AppException.java`（+unauthorized/rateLimited 工厂）
- `src/main/resources/application.yml`（petgo.auth.*）
- `.env.example`（JWT_SECRET / GOOGLE_OAUTH_CLIENT_ID）

**前端 — 新增**
- `core/network/{api_paths,problem_detail,auth_interceptor,dio_client}.dart`
- `core/storage/{secure_storage,prefs}.dart`
- `core/router/route_intent.dart`
- `features/auth/data/{google_auth_client,auth_repository}.dart`
- `features/auth/domain/{login_response,auth_routing,auth_state}.dart`
- `features/auth/presentation/{login_page,onboarding_placeholder_page}.dart`
- `test/auth/{auth_logic_test,auth_interceptor_test,login_page_test}.dart`

**前端 — 修改**
- `pubspec.yaml`（dio/flutter_secure_storage/shared_preferences/google_sign_in/url_launcher）
- `core/router/app_router.dart`（+/login、/onboarding）
- `lib/l10n/app_en.arb`、`app_id.arb`（登录文案）
