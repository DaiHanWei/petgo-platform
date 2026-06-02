# Story 5.1: 兽医账号与登录（含 Admin 开户）

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **运营与兽医**,
I want **运营能为兽医开户、兽医能独立登录**,
so that **兽医资源以机构合作方式安全接入平台，且与用户侧角色彻底隔离**。

> 这是 **Epic 5（第二根问诊支柱：在线兽医咨询 + 兽医工作台）的奠基 Story**。本 Story 只交付「兽医账号体系 + 兽医账密登录 + role=vet 角色门控 + Admin 兽医账号 CRUD」三件事，**不做**工作台界面（5.2）、不做在线态/队列（5.2）、不做咨询会话（5.3+）、不做 IM（5.5）、不做封禁的会话中断处理（5.7，本 Story 仅建账号状态字段与 Admin 封禁开关的「不可登录」语义）。
>
> 依赖前序：Epic 1 已落地 `shared/security`（JwtService/JwtAuthFilter/SecurityConfig/RoleGuard）、ProblemDetail 错误规范、用户侧 Google 登录与 `role=user` JWT。本 Story 在同一 `shared/security` 体系内**新增第二条登录路径**（账密 → BCrypt 校验 → `role=vet` JWT），并新增 `com.petgo.admin/` slice（`role=ADMIN`）承载兽医账号 CRUD。架构 G-1 已明确 admin 为跨切面 slice，复用各模块 service，不建独立系统。

## Acceptance Criteria

> **验证层标注**：每条 AC 末尾标注验证层级与所需本地环境——
> **L0 静态**（编译/lint/MockMvc，无需真实 DB） · **L1 集成/运行时**（需 Docker daemon + postgres + redis，真实 Flyway 迁移 + BCrypt + JWT 签发链路） · **L2 端到端/外部凭证**（需真机/模拟器跑 Flutter 登录页、真实兽医账号走完跳转工作台）。
> 本 Story 主体落在 **L1**（账号 CRUD + 登录链路可在 Docker 栈内验），登录页 UI 与跳转隔离需 **L2**（前端 + 真机）。无腾讯 IM（IM 从 5.5 起）。

### AC1 — 运营在 Admin 后台创建兽医账号（建 `vet_accounts` 表）

**Given** 运营在 Admin 后台
**When** 创建兽医账号
**Then** 生成账号/密码（BCrypt 存储），发放凭证（写 `vet_accounts` 表，本故事创建该表）（FR-29、G-1）
> 验证层：**L1**（Flyway 迁移建 `vet_accounts`、BCrypt hash 落库、Admin 端点 `role=ADMIN` 门控均需 Docker+pg 真实跑）。密码明文**绝不落库、绝不进日志**；创建响应仅一次性回显初始密码（或运营手填），此后不可再读取明文。

### AC2 — 兽医账密登录（与用户侧 Google 流程隔离，签发 role=vet JWT）

**Given** 兽医在登录页
**When** 点击 Google 按钮下方「兽医登录」小字链接并用账号密码登录
**Then** 与用户侧 Google 流程隔离，登录成功签发 JWT `role=vet`，跳转兽医工作台；无「忘记密码」（由运营重置）（FR-29、NFR-12）
> 验证层：登录 API（账密校验 → BCrypt 比对 → 签发 `role=vet` JWT）= **L1**；登录页「兽医登录」小字入口 + 账密表单 + 登录后路由跳工作台 = **L2**（前端真机）。无「忘记密码」入口（前端不渲染该按钮，重置走 Admin，见 AC4）。

### AC3 — 兽医/用户角色双向门控（互不可达）

**Given** 兽医登录后
**When** 访问端点 / 进入 App
**Then** 兽医账号不可访问用户侧首页/成长档案等功能，反之亦然（角色门控）
> 验证层：后端 `role` claim 路由/方法门控（vet 访问 `/api/v1` 用户写端点 → 403；user 访问 vet 工作台端点 → 403）= **L1**（MockMvc + 真实 SecurityConfig 可验，部分 L0）；前端登录态按 `role` 决定渲染用户侧 5-Tab 还是兽医侧独立 Tab（5.2 承接 UI，本 Story 仅保证 role 落地与守卫存在）= **L2**。

### AC4 — 无「忘记密码」，密码重置由运营在 Admin 执行

**Given** 兽医忘记密码
**When** 需要重置
**Then** 兽医侧无自助「忘记密码」流程；运营在 Admin 后台重置该兽医密码（重新生成 BCrypt hash，旧凭证失效）（FR-29、NFR-12）
> 验证层：**L1**（Admin 重置端点 `role=ADMIN`、新 BCrypt hash 落库、旧密码登录失败、新密码登录成功）。前端登录页**不得**出现「忘记密码」链接 = **L2**。

---

## Tasks / Subtasks

> **按「后端子任务 / 前端子任务 / 联调验收」三段组织**。本 Story 后端重（账号体系 + 双登录路径 + Admin slice），前端轻（登录页加一条小字入口 + 账密表单 + 按 role 分流）。建议顺序：后端 → 前端 → 联调。

### 🟦 后端子任务（petgo-backend / Spring Boot）

- [ ] **B1. `vet_accounts` 表 Flyway 迁移** (AC: 1)
  - [ ] 新建 `db/migration/V<n>__init_vet_accounts.sql`（序号接现有最大值）。表 `vet_accounts`：`id`(bigint PK)、`username`(varchar uq，登录账号)、`password_hash`(varchar，BCrypt)、`display_name`(varchar，兽医昵称，对话/历史展示用)、`status`(varchar，`ACTIVE|BANNED`，默认 `ACTIVE`，为 5.7 封禁预留)、`created_at`/`updated_at`(timestamptz UTC)。唯一约束 `uq_vet_accounts_username`。
  - [ ] **不建**「忘记密码」相关列（无 reset token / 无找回邮箱）；重置是 Admin 直接改 `password_hash`。
- [ ] **B2. vet 领域与认证服务** (AC: 1,2,4)
  - [ ] `com.petgo.vet.domain/{VetAccount(@Entity), VetStatus(enum ACTIVE/BANNED)}`、`vet.repository.VetAccountRepository`（`findByUsername`）。
  - [ ] `vet.service.VetAccountService`：`create(displayName,username,rawPassword)`（BCrypt encode 落库）、`resetPassword(vetId,newRawPassword)`、`findActiveByUsername`。复用 `shared/security` 的 `PasswordEncoder`（BCrypt，强度默认 10）。
  - [ ] **登录校验**：账密比对、`status=BANNED` 直接拒登（与 5.7 共用同一守卫语义，本 Story 先实现「BANNED 不可登录」）。
- [ ] **B3. 兽医账密登录端点（第二条登录路径）** (AC: 2,3)
  - [ ] `com.petgo.auth.web` 增 `POST /api/v1/auth/vet/login`（`VetLoginRequest{username,password}` → `VetLoginResponse{accessToken,refreshToken,displayName,role}`）。校验 → BCrypt 比对 → `JwtService.issue(role=VET, subject=vetId)`。失败统一 401 ProblemDetail（不区分「账号不存在/密码错」防枚举）。
  - [ ] 复用 Epic 1 `JwtService`，**本 Story 仅补 `VET` claim**。role 枚举三态归属：`USER`@Story 1.3 引入、`ADMIN`@Story 3.1 引入（建 Admin shell + `/admin/**` 门控时），`VET`@本 Story 引入——确认 `USER|VET|ADMIN` 三态在 `shared/security` 齐备，**不重复引入 ADMIN**。
  - [ ] 该端点 `permitAll`（登录本身无需 token），但产出的 JWT 带 `role=vet`。
- [ ] **B4. 角色门控收口** (AC: 3)
  - [ ] `shared/security/SecurityConfig` / `RoleGuard`：vet 端点（5.2+ 工作台 API，前缀建议 `/api/v1/vet/**`）要求 `hasRole('VET')`；用户侧写端点保持 `hasRole('USER')`；交叉访问 → 403 ProblemDetail。
  - [ ] 本 Story 仅落「守卫存在 + role 正确路由」，工作台业务端点在 5.2 填充；先放一个受 `hasRole('VET')` 保护的 `GET /api/v1/vet/me`（返回 displayName/status）供门控验证与前端登录后探活。
- [ ] **B5. Admin slice：兽医账号 CRUD** (AC: 1,4 / G-1)
  - [ ] 新建 `com.petgo.admin/{web,service}`（admin 为跨切面 slice，复用 `vet.service.VetAccountService`，**不**自建 vet repository——遵守「禁跨模块直接访问对方 repository」，经 service 接口）。
  - [ ] `role=ADMIN` 门控的端点：`POST /api/v1/admin/vets`（创建，返回一次性初始密码或接受运营手填）、`GET /api/v1/admin/vets`（列表 + 状态）、`PUT /api/v1/admin/vets/{id}/password`（重置）、`PUT /api/v1/admin/vets/{id}/status`（封禁/解封，为 5.7 预留，本 Story 实现 ACTIVE↔BANNED 切换 + BANNED 不可登录）。
  - [ ] 极简 Thymeleaf 后台页：**复用 Story 3.1 首建的 Admin shell**（`admin/layout.html` 导航壳 + `/admin/**` 门控 + ADMIN 账号种子），本 Story 仅在其上挂 `admin/vets` 页（列表 + 创建表单 + 重置按钮，V1 够用）。**不重复建 Admin shell、不重复种 ADMIN 账号**——`role=ADMIN` 门控与 ADMIN 账号均由 3.1 提供。
- [ ] **B6. 安全/合规守卫** (AC: 1,2,4)
  - [ ] 密码明文**绝不落库/绝不进日志/绝不进 MDC**；BCrypt hash 不外泄到任何响应。
  - [ ] 登录端点接 Redis 限流（复用 `shared/ratelimit/RedisRateLimiter`，作用于 `/auth/vet/login`，防爆破）。
  - [ ] 兽医 JWT 与用户 JWT 同一签发体系但 role 严格区分；refresh 轮换沿用 Epic 1。

### 🟩 前端子任务（petgo_app / Flutter）

- [ ] **F1. 登录页「兽医登录」入口** (AC: 2,4)
  - [ ] `features/auth/presentation`：在 Google 登录按钮**下方**加一条小字链接「兽医登录」（id/en 双语，走 l10n，不写死）。点击进入兽医账密登录页（`VetLoginPage`：username + password 表单 + 登录按钮）。
  - [ ] **不渲染**「忘记密码」任何入口（文案提示「如忘记密码请联系运营」可选，但无自助流程）。
- [ ] **F2. 兽医登录调用与 role 分流** (AC: 2,3)
  - [ ] `features/auth/data`：`vetLogin(username,password)` 调 `POST /api/v1/auth/vet/login`，成功存 token 到 `core/storage/secure_storage`，记录 `role=vet`。
  - [ ] `core/router/app_router`：登录态按 `role` 分流——`role=user` → 用户侧 5-Tab 首页；`role=vet` → 兽医工作台壳（5.2 实现 Tab 内容，本 Story 跳到一个占位 `VetWorkbenchShell`，调 `GET /api/v1/vet/me` 显示 displayName 证明登录链路通）。
  - [ ] 角色守卫：`role=vet` 时路由表**禁止**进入用户侧问诊/档案/Feed 等路由（反之亦然），命中越权路由重定向回各自首页。
- [ ] **F3. 错误与文案** (AC: 2)
  - [ ] 登录失败 → ProblemDetail 映射本地化文案（「账号或密码错误」统一文案，不泄露账号是否存在）；429 限流 → 「尝试过于频繁，请稍后再试」。

### 🟨 联调验收子任务（端到端）

- [ ] **J1. Admin 开户 → 兽医登录闭环** (AC: 1,2 / **L1+L2**)
  - [ ] Docker 栈起（pg+redis）→ Admin 创建兽医账号（`vet_accounts` 落 BCrypt hash，库内查 `password_hash` 非明文）→ 兽医用该账密在 App 登录成功 → 签发 `role=vet` JWT → 跳工作台壳并显示 displayName。
- [ ] **J2. 角色门控双向验证** (AC: 3 / **L1**)
  - [ ] vet token 访问 `/api/v1/`（用户写端点）→ 403 ProblemDetail；user token 访问 `/api/v1/vet/me` → 403。前端 vet 态尝试深链用户侧路由 → 被守卫挡回。
- [ ] **J3. 重置密码 + 封禁不可登录** (AC: 4 / **L1**)
  - [ ] Admin 重置某兽医密码 → 旧密码登录 401、新密码登录成功。Admin 置 `status=BANNED` → 该账号登录 401（为 5.7 铺垫）。
- [ ] **J4. 无忘记密码 + 凭证不外泄** (AC: 2,4 / L0+L2)
  - [ ] 登录页无「忘记密码」链接；日志/响应体全程无明文密码、无 BCrypt hash 外泄（抽查登录与创建链路日志）。

---

## Dev Notes

### 关键架构约定（本 Story 必须落实）

- **双登录路径，单 JWT 体系**：用户走 Google OAuth → `role=user`；兽医走账密 BCrypt → `role=vet`；运营走 `role=ADMIN`。三者同一 `JwtService` 签发，靠 `role` claim 区分（架构 §Authentication & Security）。**严禁**为兽医另起一套 token 体系。
- **角色门控是单 App 双角色的根**：Spring Security 按 `role` claim 路由/方法门控；前端按 `role` 决定整套导航壳。兽医不可达用户侧、用户不可达兽医工作台（架构 §Authentication & Security / §Frontend Boundaries）。
- **Admin = 跨切面 slice（G-1）**：`com.petgo.admin/` 承载 `role=ADMIN` 端点 + 极简 Thymeleaf 后台，**复用各模块 service**（此处复用 `vet.service.VetAccountService`），不建独立系统、不直接碰 vet repository。本 Story 落地的 Admin 兽医账号 CRUD 是后续 5.6 评分查看、5.7 封禁的同一 Admin 落点。

### 会话状态机专项（Epic 5 全程上下文，本 Story 仅触达账号侧）

> Epic 5 核心是会话状态机 `WAITING → IN_PROGRESS → PENDING_CLOSE → CLOSED / INTERRUPTED`（超时 1min、评分门 30min 为迁移触发器，全在 Java 侧编排腾讯 IM，后端不持长连接；兽医在线态/队列在 Redis；IM→OSS 存档桥接经 `ConsultClosedEvent`）。
> **本 Story 不碰状态机、不碰 IM、不碰 Redis 在线态**——它只建「兽医是谁、怎么登录、谁能开户」。但 `vet_accounts.status` 的 `BANNED` 语义与 5.7 的封禁→会话 `INTERRUPTED` 同源：本 Story 落「BANNED 不可登录」，5.7 在此基础上加「封禁时中断进行中会话」。

### 强制护栏（架构 §Enforcement —— 违反即返工）

- **禁 MQ / 通用缓存层 / 新中间件**：本 Story 无异步需求，勿顺手加。
- **Redis 收窄**：本 Story 仅用 Redis 做登录限流（已有 `RedisRateLimiter`），**不**把兽医账号信息塞 Redis 当缓存。
- **角色门控不可绕过**：`role` claim 是唯一权威，前端守卫是体验、后端门控是安全，两者都要。
- **凭证全 env 注入、绝不入库**；密码明文绝不落库/日志。
- **模块间经 service / 事件通信**：Admin 经 `VetAccountService`，不跨模块直访 repository。

### 范围边界（防 scope creep —— 本 Story 明确不做）

- ❌ 不做兽医工作台 Tab Bar / 待接单·进行中·历史·我的（Story 5.2）。
- ❌ 不做在线/离线切换、Redis 在线态、接单队列（Story 5.2）。
- ❌ 不做咨询发起 / `consult_sessions` 表 / WAITING 状态 / 超时排队（Story 5.3）。
- ❌ 不做腾讯 IM 会话与对话界面（Story 5.5）。
- ❌ 不做封禁时进行中会话的中断处理（Story 5.7，本 Story 仅「BANNED 不可登录」）。
- ✅ 只做：`vet_accounts` 表 + BCrypt + 兽医账密登录(`role=vet`) + 双向角色门控 + Admin 兽医账号 CRUD/重置/封禁开关 + 登录页小字入口（无忘记密码）。

### Project Structure Notes

- 后端：`com.petgo.vet.{web,service,domain,repository,dto,event}`（本 Story 落 `domain/{VetAccount,VetStatus}`、`repository/VetAccountRepository`、`service/VetAccountService`、`web/VetMeController`）；登录端点落 `com.petgo.auth.web`（与用户登录并列）；`com.petgo.admin/{web,service}`（兽医 CRUD 页，**挂在 3.1 首建的 Admin shell 上**）；`shared/security`（补 `VET` claim；`USER`@1.3、`ADMIN`@3.1 已引入）。Flyway `db/migration/V<n>__init_vet_accounts.sql`（序号按 dev 执行顺序单调分配，见 sprint-status 头注）。
- 前端：`lib/features/auth/{data,domain,presentation}`（兽医登录页 + 调用 + role 分流）；`lib/features/vet/`（本 Story 仅落 `VetWorkbenchShell` 占位 + `GET /api/v1/vet/me` 探活，Tab 内容 5.2 填）。
- 用户侧问诊在 `features/consult`（5.3+），兽医侧在 `features/vet`——本 Story 已为这条隔离边界落地 role 分流。

### References

- [Source: architecture.md#Authentication & Security] — 兽医 BCrypt + `role=vet` JWT、与 Google 流程隔离、单 App 角色门控、密钥 env 注入。
- [Source: architecture.md#Gap Analysis / Validation Issues Addressed (G-1)] — `com.petgo.admin/`（role=ADMIN + Thymeleaf）承载兽医账号 CRUD，复用模块 service。
- [Source: 3-1-运营后台地基与种子内容发布.md] — **依赖**：Admin shell（`/admin/**` 门控 + `role=ADMIN` + `admin/layout.html` + ADMIN 账号种子）首建于 Story 3.1（Epic 3 < Epic 5），本 Story 复用、不重复建。
- [Source: architecture.md#Complete Project Directory Structure] — `vet/`、`admin/`（隐含于 G-1）、`shared/security/{JwtService,RoleGuard,SecurityConfig}`、`features/auth`、`features/vet`。
- [Source: architecture.md#Architectural Boundaries] — `/api/v1` 默认需 JWT、模块间经 service/事件、禁跨 repository。
- [Source: architecture.md#Naming Patterns] — DB snake_case 复数表 + UPPER_SNAKE 枚举 + Flyway `V<序号>__`；Java 分层后缀；JSON camelCase。
- [Source: epics.md#Story 5.1] — 三条原始 AC（Admin 开户 / 兽医登录隔离 + role=vet / 角色门控）。
- [Source: epics.md#Epic 5] — Epic 目标与 FR-29 定位。
- [Memory: spec-page-state-completeness] — 显式覆盖 BANNED 不可登录、登录失败防枚举、无忘记密码等非 happy-path。

## Dev Agent Record

### Agent Model Used

云端 dev agent（Epic 5 批量）

### Debug Log References

### Completion Notes List

**L0 绿（云端已验）**：
- 后端 `./mvnw -B -DskipTests package` 通过；新增 `VetAccountServiceTest`（BCrypt 落库非明文、登录防枚举、BANNED 不可登录、唯一校验）+ 既有 `AuthServiceTest` 适配新构造器，单测全绿。
- 前端 `flutter analyze` 零问题；`flutter test` 全绿（新增 `test/vet/vet_login_test.dart`：兽医登录入口 + 账密表单 + 无忘记密码 + 双语）。

**实现要点**：
- `JwtService` 原已支持 `role` claim（Role 枚举 USER/VET/ADMIN 在 Epic 1 已齐备），本 Story 仅新增 `issueAccessToken(subjectId, Role)` 重载供兽医签发，**未新起 token 体系**。ADMIN 种子账号沿用 3.1 的 `AdminBootstrap`（env 注入），本 Story 不重复种。
- refresh 多主体：`refresh_tokens` 新增 `subject_type`(USER/VET) 列（V13 ALTER），`rotateRefresh` 按主体类型分派，**防兽医 refresh 在 `/auth/refresh` 被误签为同 id 的用户 token**（安全修复）。
- Admin 兽医 CRUD 挂在 3.1 Admin shell（`/admin/vets` 页 + 导航启用），经 `VetAccountService` 不直访 repository。
- 登录防枚举：账号不存在也走一次假 BCrypt 比对（防时序侧信道），失败统一 401「账号或密码错误」。

**待本地验收（L1）**：需 Docker（pg+redis）真跑：
- J1 Admin 开户 → 兽医 App 登录闭环（库内查 `password_hash` 为 BCrypt 非明文）。
- J2 角色门控双向：vet token 访问用户写端点 → 403 / user token 访问 `/api/v1/vet/me` → 403。**注意**：后端 user 写端点当前为 `authenticated()`，vet→user 的 403 仅在 `/api/v1/vet/**`（VET 专属）侧已硬门控；用户侧写端点的 `hasRole('USER')` 收紧建议在 L1 一并验证/补强（前端 role 分流已隔离，后端补强为纵深防御）。
- J3 重置密码（旧密码 401 / 新密码 200）+ 封禁后登录 401。
- Redis 限流（`rl:auth:vet:<ip>` 每分钟 10 次）需 redis 真跑。

**待本地验收（L2）**：真机/模拟器跑兽医登录页视觉 + 登录后跳工作台壳显示 displayName + 用户/兽医导航壳隔离（深链越权被守卫挡回）。

### File List

**后端（新增）**：
- `db/migration/V13__init_vet_accounts.sql`
- `vet/domain/{VetAccount,VetStatus}.java`、`vet/repository/VetAccountRepository.java`、`vet/service/VetAccountService.java`、`vet/dto/VetMeResponse.java`、`vet/web/VetMeController.java`
- `auth/domain/SubjectType.java`、`auth/dto/{VetLoginRequest,VetLoginResponse}.java`、`auth/web/VetAuthController.java`
- `admin/dto/CreateVetForm.java`、`admin/service/{AdminVetService,VetAdminView}.java`、`templates/admin/vets.html`
- `test/.../vet/service/VetAccountServiceTest.java`

**后端（修改）**：
- `shared/security/JwtService.java`（+vet 签发重载）、`shared/security/SecurityConfig.java`（`/api/v1/vet/**` hasRole VET）
- `auth/domain/RefreshToken.java`（+subjectType）、`auth/service/AuthService.java`（vet 登录 + refresh 分派）
- `admin/web/AdminWebController.java`（+vet CRUD 端点）、`templates/admin/layout.html`（启用兽医账号导航）
- `test/.../auth/service/AuthServiceTest.java`、`test/.../admin/web/AdminWebControllerTest.java`（构造器适配）

**前端（新增）**：
- `features/vet/domain/vet_login_response.dart`、`features/vet/data/vet_repository.dart`
- `features/vet/presentation/{vet_login_page,vet_workbench_shell}.dart`
- `test/vet/vet_login_test.dart`

**前端（修改）**：
- `core/network/api_paths.dart`（+vet 路径）、`core/router/app_router.dart`（+vet 路由 + role 守卫）
- `features/auth/domain/auth_state.dart`（+applyVetLogin/isVet）、`features/auth/presentation/login_page.dart`（+兽医登录入口）
- `l10n/app_en.arb`、`l10n/app_id.arb`（+vet 文案）
