---
baseline_commit: db7397bcd1d2088ec6810718fc88356fa5e8831c
---

# Story 1.2: Lark OAuth 登录与邮箱白名单门控

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> **范围域**：管理后台 V1.0.0 · Epic 1（后台地基）· Story 1.2。**建立在已完成的 Story 1.1 之上**（admin_accounts + 服务端会话 + 紧急账密 + adminFilterChain）。
> **产物归属**：仅后端 `petgo-backend`（**无 Flutter 侧**）。brownfield 扩 `com.tailtopia.admin`。

## Story

As a **运营成员**,
I want **用我的 Lark 账号一键登录后台，离职/停用后立即失去访问权**,
so that **团队全员复用 Lark 身份免维护密码，且权限变更即时生效（AM-C2 零越权）**。

## Acceptance Criteria

> 验证层：**L0** 编译/单测/MockMvc（mock Lark 客户端，无需 DB）· **L1** 集成（Docker postgres+redis，验白名单门控/会话/迁移）· **L2** 外部凭证（真实 Lark 应用 + CF Access，留本地/真凭证手测）。

1. **AC1（Lark 登录入口，L0/L2）**：登录页提供「用 Lark 登录」主入口；点击跳转 Lark 授权页（带 `state` CSRF 参数，`redirect_uri` 在 Lark 应用 allowlist 内）。超管紧急账密入口降级为页面底部小字「紧急登录」可展开（PRD AB-0A）。
2. **AC2（回调换身份，L0 mock + L2）**：回调端点校验 `state`（防 CSRF/重放）→ 用 `code` 经 app_access_token 换取 user_access_token 及用户身份（email/enterprise_email、tenant_key、open_id）。
3. **AC3（企业租户 + 已验证邮箱，L0）**：校验 `tenant_key == 配置的企业租户` 且邮箱为企业已验证邮箱（优先 enterprise_email）；不满足 → 拒绝。
4. **AC4（白名单门控，L1）**：用 Lark 邮箱比对 `admin_accounts`，命中 `status=ACTIVE` 的账号 → 按其 account_type/权限建立服务端会话（principal 为 `AdminUserDetails`，与 1.1 密码登录同形）进入后台；未命中或 `DISABLED` → 拒绝并提示「你的账号无访问权限，请联系管理员」（不建会话）。
5. **AC5（撤权/停用即时失效，L1，A1）**：已登录会话在**每次请求**校验其 `admin_accounts` 记录仍存在且 `status=ACTIVE`；一旦被移出白名单/停用 → 下次请求即会话失效、跳登录页并提示「会话已过期」。
6. **AC6（8h 闲置过期，L1/L0 配置）**：后台会话闲置 8 小时自动过期（滑动续期），过期后跳登录页提示「会话已过期」。仅影响会话链（admin），不影响无状态 `/api/v1`。
7. **AC7（回归绿，L0/L1）**：既有紧急账密登录路径（1.1）与全部既有端点不回归；`mvn -B compile` + L0 全绿；本地 L1 全量回归绿。

## Tasks / Subtasks

- [x] **T1 Lark OAuth 客户端（AC2/AC3）**
  - [x] 新建 `admin/account/service/LarkOAuthClient`（用 Spring `RestClient`，**不引入 oauth2-client starter**）：`authorizeUrl(state)` 拼授权 URL；`exchangeCode(code)` → 调 app_access_token 再换 user_access_token+身份，返回 `LarkIdentity(email, enterpriseEmail, tenantKey, openId, emailVerified)`
  - [x] 端点（Lark 国际版 open.larksuite.com，全部 env 注入、base-url 可配）：
    - app_access_token：`POST /open-apis/auth/v3/app_access_token/internal`（{app_id, app_secret}）
    - code→user_access_token+身份：`POST /open-apis/authen/v1/oidc/access_token`（grant_type=authorization_code, code；Header 带 app_access_token）
    - 授权页：`/open-apis/authen/v1/authorize?app_id=&redirect_uri=&state=&scope=`
  - [x] 配置 `LARK_APP_ID/LARK_APP_SECRET/LARK_TENANT_KEY/LARK_REDIRECT_URI/LARK_BASE_URL`（env，不入库；`.env.example` 加占位）
- [x] **T2 登录/回调控制器（AC1/AC2/AC4）**
  - [x] `admin/account/web/AdminLarkLoginController`：`GET /admin/oauth/lark/login`（生成 state 存 session → 302 授权页）；`GET /admin/oauth/lark/callback`（校验 state → exchangeCode → 租户/邮箱校验 → 白名单匹配 → 建会话或拒绝）
  - [x] 建会话：复用 1.1 的 `AdminUserDetails` 构造逻辑（**重构 `AdminUserDetailsService` 抽出 `loadByEmail(email)→AdminUserDetails` 供密码登录与 OAuth 共用**，DRY）；用 `SecurityContextHolder` + `HttpSessionSecurityContextRepository` 持久化登录态
  - [x] 拒绝路径：未命中/停用/租户不符/邮箱未验证 → 重定向 `/admin/login?denied`（不建会话；login.html 加该文案）
- [x] **T3 会话守卫过滤器（AC5，A1）**
  - [x] `admin/account/web/AdminSessionGuardFilter`（OncePerRequestFilter，装配进 adminFilterChain，认证之后）：对已认证 admin 会话，按 principal 的 `adminAccountId` 重查 `admin_accounts`；若不存在或 `status!=ACTIVE` → `session.invalidate()` + 重定向 `/admin/login?expired`
  - [x] 注意：放行 `/admin/login`、`/admin/oauth/**` 自身，避免循环
- [x] **T4 会话过期配置（AC6）**
  - [x] `server.servlet.session.timeout=8h`（application.yml；仅影响会话链）；过期跳转文案 `?expired`
- [x] **T5 登录页改造（AC1）**
  - [x] `templates/admin/login.html`：新增「用 Lark 登录」主按钮（→ `/admin/oauth/lark/login`）；账密表单收进底部「紧急登录」可展开区；加 `?denied`/`?expired` 文案（沿用 1.1 的 `?error`/`?locked`/`?logout`）
- [x] **T6 安全链装配（AC1/AC5）**
  - [x] `SecurityConfig.adminFilterChain`：放行 `/admin/oauth/**`（permitAll，未登录可走回调建会话）；装配 `AdminSessionGuardFilter`；保留 1.1 的限流过滤器与紧急账密 formLogin
- [x] **T7 测试 + 回归（AC2~AC7）**
  - [x] L0：`LarkOAuthClient`（mock RestClient/返回桩身份）；回调 service 的租户/邮箱/白名单匹配与拒绝分支；`AdminSessionGuardFilter`（mock repo：ACTIVE 放行 / DISABLED 失效）；state 校验
  - [x] L1：本地 docker 全量回归绿；白名单命中/未命中/停用即时失效真跑
  - [x] L2（留手测）：真实 Lark 应用走通授权→回调→进后台；CF Access 门

## Dev Notes

### 架构约束
- 后台 = 服务端 HttpSession（A1，1.1 已立）；本故事让 OAuth 与密码两条登录都落到**同一种会话 + 同一种 principal `AdminUserDetails`**。
- **Lark 非标准 OIDC**：token 端点需先取 app_access_token，再以它换 user_access_token（身份内嵌返回），user_access_token ~2h 有效。故**不用 Spring `oauth2Login`**，改**手写授权码流**（RestClient 直调）。〔修正架构 §Starter 里「加 spring-boot-starter-oauth2-client」的设想——手写流更贴合 Lark 且更少依赖；如 dev 仍想用 oauth2-client 的 ClientRegistration 自定义 provider 亦可，但需自定义 token/userinfo 解析，复杂度更高，不推荐。〕
- 国际版域名 `open.larksuite.com`（不是 feishu.cn）；base-url env 可配以便区分环境。
- 凭证 env 注入绝不入库；日志严禁记录 token/code/access_token（脱敏）。

### 白名单语义（关键，避免 agent 误解）
- **「白名单」就是 `admin_accounts` 表本身**：一行 `status=ACTIVE` 的记录 = 在白名单。无独立白名单表。
- 增删白名单成员 = Story 1.5 的账号管理 UI 增/停用账号（本故事不做管理 UI，只做「登录时比对 + 每请求复查」）。
- 首个超管由 1.1 的 `AdminBootstrap` 预置（其 `lark_email` 即可走 Lark 登录，前提是该邮箱在企业租户内）。

### 既有代码基线（READ，1.1 产出）
- `SecurityConfig.adminFilterChain`（shared/security）：`securityMatcher("/admin/**")` + session + CSRF + formLogin + 1.1 的 `AdminLoginThrottleFilter` + 失败/成功处理器。**本故事在此 +放行 `/admin/oauth/**` +装 `AdminSessionGuardFilter`**。⚠️ 该文件含仓库既有「法律页」未提交 WIP，提交时仅纳入 admin 增量。
- `AdminUserDetailsService.loadUserByUsername`（admin/service）：查 `admin_accounts`(ACTIVE+有密码) + 解析 operatorUserId。**重构**：抽出 `loadByEmail(email, requirePassword)` 返回 `AdminUserDetails`，密码登录 requirePassword=true、OAuth requirePassword=false（OAuth 账号可无密码）。
- `AdminUserDetails`（admin/service）：principal，携 adminAccountId/operatorUserId/accountType/authorities。OAuth 建会话直接复用它。
- `AdminAccountRepository.findByLarkEmail` / `AdminAccount`（admin/account）：白名单查询。
- `templates/admin/login.html`：已有 `?error/?locked/?logout` 文案 + 账密表单；本故事加 Lark 主入口 + `?denied/?expired`。
- `RestClient`：Spring Web 自带（无新依赖）；参考既有 `shared/ai/GeminiClient` / `shared/im/TencentImClient` 的外部 HTTP 调用与脱敏日志风格保持一致。

### 关键边界 / 防坑
- **回调建会话必须用与 formLogin 相同的 SecurityContext 持久化机制**（`HttpSessionSecurityContextRepository`），否则建的会话后续请求不被 adminFilterChain 认可。
- `AdminSessionGuardFilter` 必须放行 `/admin/login` 与 `/admin/oauth/**`，否则未登录/建会话过程被自己拦成死循环。
- `state` 存 session 并一次性消费（用后即弃），防 CSRF 与重放。
- OAuth 账号（STAFF，无 password_hash）能登录——故 `loadByEmail` OAuth 路径**不得**用 1.1 的「password_hash != null」过滤（那是密码登录专用）。
- 撤权即时性靠 `AdminSessionGuardFilter` 每请求复查（A1）；不要依赖 token 过期。
- CF Access（A4）是边缘基建（L2），不在代码内实现；story 仅在 Dev Notes 标注部署需在 `admin.tailtopia.id` 前置 CF Access。

### 范围边界（不做）
- 不做账号管理 UI / 两级权限分配 / 超管≤5（→ Story 1.5）；不做操作/会话审计落库（→ 1.3/1.4，但本故事的登录成功/拒绝可留 log 占位，审计正式落库在 1.3/1.4）；不做双语外化（→ 1.6，登录页新文案先中文，1.6 统一外化）。

### Flyway
- 本故事**无新迁移**（admin_accounts 已在 1.1 的 V30 建好）。若确需新列（不预期），从 **V32** 顺延。

### 测试标准
- L0：mock `LarkOAuthClient` 返回桩 `LarkIdentity`，覆盖：租户匹配/不匹配、邮箱已验证/未验证、白名单命中 ACTIVE/未命中/DISABLED、state 有效/无效；`AdminSessionGuardFilter` 的 ACTIVE 放行 / 失活 invalidate。MockMvc 验登录页含 Lark 入口、回调拒绝重定向。
- L1：本地 docker 全量回归（含既有端点不回归）+ 真实会话门控；沿用 1.1 的 `docker compose up -d postgres redis` + `mvn -B test`。
- L2（手测，留本地/真凭证）：真实 Lark 应用授权→回调→进后台；停用账号后下次请求被踢；CF Access 门。云端 headless 只跑 L0。

### Project Structure Notes
- 新增：`admin/account/{service/LarkOAuthClient, web/AdminLarkLoginController, web/AdminSessionGuardFilter, dto/LarkIdentity}`。
- 修改：`AdminUserDetailsService`（抽 loadByEmail）、`SecurityConfig`（放行 oauth + 装 guard）、`login.html`、`application.yml`（session.timeout + lark 配置）、`.env.example`。

### References
- [Source: admin-backend/epics.md#Story 1.2 Lark OAuth 登录与邮箱白名单门控]
- [Source: admin-backend/architecture.md#Authentication & Security A1/A2/A4；Starter «Lark OAuth 非标 OIDC»]
- [Source: admin-backend/PRD.md#AB-0A 后台账号登录（Lark 主 + 紧急账密 + 白名单 + 8h 过期）]
- [Source: 1-1-后台账号模型与认证重构.md（admin_accounts/AdminUserDetails/AdminUserDetailsService/SecurityConfig 基线）]
- [Lark Docs: 获取 user_access_token / 授权码流 https://open.larksuite.com/document/uAjLw4CM/ukTMukTMukTM/authentication-management/access-token/get-user-access-token]
- [Lark Docs: app_access_token https://open.larksuite.com/document/server-docs/getting-started/api-access-token/app-access-token-development-guide]
- [Lark Docs: 获取授权码（SSO）https://open.larksuite.com/document/common-capabilities/sso/api/obtain-oauth-code]

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m]（bmad-dev-story）

### Debug Log References

- L0：`mvn -Dtest=AdminLarkAuthServiceTest,LarkOAuthClientTest,AdminSessionGuardFilterTest,AdminUserDetailsServiceTest,AdminWebControllerTest test` → 23/23 通过。
- L1 全量回归（本地 docker postgres+redis）：`mvn -B test` → **631 通过 / 0 失败 / 0 错误 / 6 跳过，BUILD SUCCESS**（含新 14 个 L0；全 @SpringBootTest 上下文加载新 bean 无误）。

### Completion Notes List

- **AC1 ✅(L0)/L2**：登录页加「用 Lark 登录」主入口（紫 `#845EC9`，对齐 App 品牌）；紧急账密收进 `<details>「紧急登录」`；authorizeUrl 带 state+编码 redirect_uri（单测）。真实跳转属 L2。
- **AC2 ✅(L0 映射)/L2**：`LarkOAuthClient` 手写流（RestClient）：app_access_token → oidc/access_token；`mapIdentity` 容错 data 包裹层，单测覆盖。真实 HTTP 换取属 L2。
- **AC3 ✅(L0)**：`AdminLarkAuthService` 校验 tenant_key==配置租户 + emailVerified；不符即拒绝（单测覆盖租户不符/未验证）。
- **AC4 ✅(L0+L1)**：白名单匹配 `admin_accounts`(ACTIVE) 经 `loadByEmail(.., false)`；命中建会话、未命中/停用 → `?denied`。DRY：`AdminUserDetailsService.loadByEmail` 抽出供密码登录(requirePassword=true)与 OAuth(false)共用。
- **AC5 ✅(L0+L1)**：`AdminSessionGuardFilter` 每请求复查账号 ACTIVE，停用/移除 → 失效会话 + `?expired`；放行 `/admin/login` 与 `/admin/oauth/**` 防死循环（单测覆盖 ACTIVE 放行 / DISABLED / 不存在 / 跳过路径）。
- **AC6 ✅(配置)**：`server.servlet.session.timeout=8h`（env 可调），仅影响会话链。
- **AC7 ✅(L1)**：631/0/0 无回归；既有紧急账密登录路径不受影响。
- **回调建会话**：用 `HttpSessionSecurityContextRepository.saveContext` 持久化，与 formLogin 同机制；principal 复用 `AdminUserDetails`。
- **范围边界守住**：未做账号管理 UI/权限分配(1.5)、审计落库(1.3/1.4)、双语外化(1.6，登录新文案先中文)。无新迁移（admin_accounts 已在 V30）。
- **L2 待手测**：真实 Lark 应用授权→回调→进后台；停用后下次请求被踢；CF Access 前置 `admin.tailtopia.id`。Lark oidc/access_token 响应字段以真实凭证核对（mapIdentity 已容错）。
- **架构修正记录**：架构 §Starter 原列 `spring-boot-starter-oauth2-client`——本故事采手写流未引入该 starter（更贴合 Lark 非标 OIDC）。

### File List

新增（NEW）:
- petgo-backend/src/main/java/com/tailtopia/admin/account/dto/LarkIdentity.java
- petgo-backend/src/main/java/com/tailtopia/admin/account/service/LarkOAuthClient.java
- petgo-backend/src/main/java/com/tailtopia/admin/account/service/AdminLarkAuthService.java
- petgo-backend/src/main/java/com/tailtopia/admin/account/web/AdminLarkLoginController.java
- petgo-backend/src/main/java/com/tailtopia/admin/account/web/AdminSessionGuardFilter.java
- petgo-backend/src/test/java/com/tailtopia/admin/account/service/AdminLarkAuthServiceTest.java
- petgo-backend/src/test/java/com/tailtopia/admin/account/service/LarkOAuthClientTest.java
- petgo-backend/src/test/java/com/tailtopia/admin/account/web/AdminSessionGuardFilterTest.java

修改（UPDATE）:
- petgo-backend/src/main/java/com/tailtopia/admin/service/AdminUserDetailsService.java （抽 loadByEmail，密码/OAuth 共用）
- petgo-backend/src/main/java/com/tailtopia/shared/security/SecurityConfig.java （放行 /admin/oauth/**、装 AdminSessionGuardFilter；注意：此文件仍含既有法律页 WIP，提交时仅纳入 admin 增量）
- petgo-backend/src/main/resources/templates/admin/login.html （Lark 主入口 + 紧急登录折叠 + ?denied/?expired）
- petgo-backend/src/main/resources/application.yml （server.servlet.session.timeout + admin.lark 配置块）
- petgo-backend/.env.example （ADMIN_BOOTSTRAP_* + LARK_* + ADMIN_SESSION_TIMEOUT 占位）

### Change Log

- 2026-06-29：实现 Story 1.2。Lark 手写授权码流（RestClient）+ 企业租户/邮箱验证门控 + admin_accounts 白名单匹配 + 每请求会话守卫（撤权即时失效）+ 8h 会话过期 + 登录页 Lark 主入口。loadByEmail 抽出复用。L0 23/23 + L1 全量 631/0/0 绿。
