---
baseline_commit: 4301227a
branch: feat/1.3.0-ops-ui-refactor
epic: 1
story: 1.3
ad: [AD-1]
decisions: [D-1, D-21]
depends_on: [1-1-账号变更版本号与自动踢重登]
---

# Story 1.3: 换绑 Lark 邮箱

Status: ready-for-dev

> 自包含 story，可本地或云端 L0 执行。与用户沟通用中文。执行纪律见根 `CLAUDE.md`。
> 本 story **纯后台**（服务层 + Controller + 现有账号页模板 + 三语 key + 一支迁移改唯一约束），无 App 端改动。
> **依赖 1-1**：换绑成功后必须调 `AdminAccountService.bumpSecurityVersion(accountId)`，让旧持有人下一次请求被 `AdminSessionGuardFilter` 踢到 `/admin/login?relogin`。

## Story

As a 超级管理员，
I want 把一个账号绑定的 Lark 邮箱换成新邮箱，旧邮箱立刻失去访问权，
so that 人员邮箱变更或账号移交不用停旧建新、权限重配、审计断裂。

## Acceptance Criteria

**AC1 · 端点与门控**
**Given** 操作者为 SUPER_ADMIN
**When** 账号列表行内点「换绑邮箱」→ 输入新邮箱 → 二次确认弹层复述「旧邮箱 → 新邮箱，旧邮箱立即失去访问权限」→ 提交 `POST /admin/accounts/{id}/rebind-email`（字段 `newEmail`）
**Then** 门控 `hasRole('SUPER_ADMIN')`（**仅超管**，比 `CREATE_AUTH` 更严，新常量 `REBIND_AUTH`）；非超管 403，模板不渲染该按钮 `[L1]`

**AC2 · 校验**
**Then** 新邮箱 trim、格式校验与创建同口径（`@Email` 语义：服务层用同一正则或 `jakarta.validation` 校验器）；空 → `admin.err.account.emailRequired`；格式错 → **新增** `admin.err.account.emailInvalid` `[L1]`
**And** 与目标账号当前邮箱相同（忽略大小写）→ 幂等 no-op `[L1]`
**And** 🛡 与任何 **`status=ACTIVE`** 账号邮箱重复（忽略大小写）→ `admin.err.account.emailExists`；**已停用账号的邮箱视为已释放**，可被换绑复用（D-21）`[L1]`
**And** 目标账号不存在 → `admin.err.account.notFound`；目标账号已停用 → **新增** `admin.err.account.rebindDisabled`（停用账号先激活再换绑，避免「换到一个进不去的号」）`[L1]`

**AC3 · bootstrap 超管不可换绑**
**Given** 目标账号的 `lark_email` 等于 env `ADMIN_BOOTSTRAP_EMAIL`（`AdminBootstrap` 用同一 env 建号）
**Then** 拒绝，**新增** `admin.err.account.bootstrapRebind`；页面该行按钮禁用并注明 `[L1]`

**AC4 · 生效**
**When** 校验通过
**Then** 同事务：`lark_email` 更新 → `bumpSecurityVersion`（1-1 方法）→ 审计 `ACCOUNT_EMAIL_REBOUND`（summary「Lark 邮箱 旧 → 新」）→ `AdminAlertService.alertSuperAdmins(AuditActions.ACCOUNT_EMAIL_REBOUND, actorId)` `[L1]`
**And** 旧持有人的会话在下一次请求被踢到 `/admin/login?relogin`（由 1-1 过滤器实现，本 story 只保证 bump）`[L1]`
**And** 旧邮箱的 Lark OAuth 登录立即命中不到白名单（`loadByEmail(old, false)` 抛 `UsernameNotFoundException`）；新邮箱可登录且权限、角色、审计历史全部保留 `[L1]`
**And** 成功横幅 **新增** `admin.flash.account.emailRebound`：「已将账号 #{0} 的 Lark 邮箱换绑为 {1}，旧邮箱会话已失效」`[L2]`

**AC5 · 唯一约束放宽（D-21 的库级配套）**
**Given** 现状 `uq_admin_accounts_lark_email UNIQUE (lark_email)` 是**全表唯一**，与「已停用邮箱可复用」冲突
**When** 执行迁移 `V<yyyyMMdd_HHmm>__relax_admin_accounts_email_unique.sql`
**Then** 删除全表唯一约束，改为**部分唯一索引** `uq_admin_accounts_lark_email_active ON admin_accounts (lower(lark_email)) WHERE status = 'ACTIVE'` `[L1]`
**And** 🛡 `createAccount` 的重复邮箱校验同步改为「仅对 ACTIVE 账号比对」，并在服务层保留显式校验（部分索引是兜底）`[L1]`
**And** 🔴 `AdminBootstrap.findByLarkEmail(bootstrapEmail)`、`AdminUserDetailsService.loadByEmail` 等现有 `findByLarkEmail` 调用点：Repository 方法返回 `Optional` 假设唯一——改约束后可能出现「同邮箱一个 ACTIVE + 一个 DISABLED」，须把这些调用改为 `findByLarkEmailIgnoreCaseAndStatus(email, ACTIVE)` 或在服务层过滤，**逐处核对**（见 Dev Notes）`[L1]`

**AC6 · 回归与三语**
**Then** 既有测试全绿；新增 key（`admin.accounts.rebind`、`admin.accounts.rebind.newEmail`、`admin.accounts.rebind.confirm`、`admin.accounts.rebind.bootstrapGuard`、`admin.flash.account.emailRebound`、`admin.err.account.emailInvalid`、`admin.err.account.rebindDisabled`、`admin.err.account.bootstrapRebind`）四包齐备 `[L0/L1]`

---

## Tasks / Subtasks

- [ ] **T1 · 迁移：唯一约束改部分索引**（AC5）
  - [ ] 新建 `V<yyyyMMdd_HHmm>__relax_admin_accounts_email_unique.sql`（取创建时刻；上一支动本表的是 `V20260821_1449__add_admin_account_role.sql`，注释风格照它）：
    ```sql
    -- AB-16A 换绑 Lark 邮箱（D-21）：已停用账号的邮箱视为已释放，可被换绑 / 新建复用。
    -- 全表唯一改为「仅 ACTIVE 唯一」的部分索引；服务层仍显式校验，索引是兜底。
    ALTER TABLE admin_accounts DROP CONSTRAINT uq_admin_accounts_lark_email;
    CREATE UNIQUE INDEX uq_admin_accounts_lark_email_active
        ON admin_accounts (lower(lark_email)) WHERE status = 'ACTIVE';
    ```
  - [ ] `ddl-auto=validate` 不校验索引/约束名，只校验列 —— 本迁移不改列，validate 不受影响
  - [ ] 跑 `bash scripts/ci/check-flyway-versions.sh origin/main`

- [ ] **T2 · Repository**（AC2 / AC5）
  - [ ] `AdminAccountRepository` 新增 `Optional<AdminAccount> findByLarkEmailIgnoreCaseAndStatus(String email, AdminAccountStatus status)` 与 `boolean existsByLarkEmailIgnoreCaseAndStatusAndIdNot(String email, AdminAccountStatus status, long id)`
  - [ ] 🔴 全库 grep `findByLarkEmail(` 的调用点，逐处判断：登录（`loadByEmail`）与 Bootstrap 只关心 ACTIVE → 改用新方法；`createAccount` 重复校验 → 改为 ACTIVE 口径。旧方法若无调用可删，有调用但语义需要「任意状态」则保留并加注释

- [ ] **T3 · 服务层 `rebindEmail`**（AC2～AC4）
  - [ ] `AdminAccountService` 新增 `@Transactional public void rebindEmail(long accountId, String newEmail, long actorAccountId)`
  - [ ] 注入 `AdminAlertService`（构造器加参数；同时更新 `AdminAccountServiceTest` / `AdminAccountRoleServiceTest` 的构造）与 `@Value("${ADMIN_BOOTSTRAP_EMAIL:}") String bootstrapEmail`（与 `AdminBootstrap` 同一 env 名）
  - [ ] 顺序：findById → status 必须 ACTIVE → bootstrap 邮箱拒绝 → trim + 格式 → 同值 no-op → ACTIVE 唯一性（排除自身 id）→ 写 `larkEmail` → `bumpSecurityVersion` → 审计 → 告警
  - [ ] `AdminAccount` 新增 `setLarkEmail(String)`（现状无此 setter）
  - [ ] `AuditActions` 加 `ACCOUNT_EMAIL_REBOUND`
  - [ ] 🛡 日志：`AdminAlertService.alertSuperAdmins` 只记 event + actorId，不记邮箱；`rebindEmail` 内不打含邮箱的应用日志（审计 summary 记邮箱是既有约定，允许）

- [ ] **T4 · Controller + 模板**（AC1 / AC3）
  - [ ] `AdminAccountAdminController` 加常量 `REBIND_AUTH = "hasRole('SUPER_ADMIN')"`，`@PostMapping("/admin/accounts/{id}/rebind-email") @PreAuthorize(REBIND_AUTH)`，PRG 范式同既有
  - [ ] `populate` 增 `bootstrapEmail`（注入同一 env）供模板判断禁用态；`AdminAccountView` 不改（模板用 `a.larkEmail == bootstrapEmail` 比较即可）
  - [ ] `admin-accounts.html` 操作列加「换绑邮箱」`<details>`（`sec:authorize="hasRole('SUPER_ADMIN')"`）：邮箱输入 + 保存钮；表单 `th:data-confirm="#{admin.accounts.rebind.confirm}"`——🔴 现有 `data-confirm` 文案是静态 key，**无法复述新旧邮箱**：本 story 在 `admin.js` 里为该表单加一段小逻辑，把 `data-confirm` 文案中的 `{0}` `{1}` 用当前行邮箱与输入框值替换后再 `confirm()`（`admin-core.js` 拆分是 Story 2.2 的事，这里只加最小改动，写清注释供 2.2 搬迁）
  - [ ] bootstrap 行：按钮 `disabled` + `title="#{admin.accounts.rebind.bootstrapGuard}"`

- [ ] **T5 · i18n**（AC6）：四包各加 8 key；印尼语参考既有措辞

- [ ] **T6 · 测试**
  - [ ] L0 `AdminAccountServiceTest`：`rebindEmailUpdatesBumpsAuditsAndAlerts`（verify `bumpSecurityVersion` 路径、`auditService.record(ACCOUNT_EMAIL_REBOUND)`、`alertService.alertSuperAdmins`）、`rebindRejectsBootstrapEmail`、`rebindRejectsDisabledTarget`、`rebindRejectsInvalidFormat`、`rebindRejectsActiveDuplicate`、`rebindAllowsDisabledDuplicate`（D-21）、`rebindSameEmailIsNoOp`
  - [ ] L0 `AdminAccountAccessControlTest`：`rebindNeedsSuperAdmin`（`admin.create_account` 也 403）
  - [ ] L1 `AdminAccountManagementIntegrationTest`：换绑后 `loadByEmail(old,false)` 抛 `UsernameNotFoundException`、`loadByEmail(new,false)` 成功且权限集合不变、`security_version` +1、审计行存在；**部分唯一索引**：建 A(ACTIVE, x@y) → 停用 → 建 B(ACTIVE, x@y) 成功；再建 C(ACTIVE, x@y) 被拒
  - [ ] L2（本地）：换绑后旧持有人另一浏览器刷新 → 跳 `?relogin`

- [ ] **T7 · 云端执行须知**：云端只跑 L0；L1 需 Docker，留本地；Completion Notes 标注

---

## Dev Notes

### 🎯 换绑 = 身份移交，三件事必须在同一事务

改邮箱、bump 版本号、写审计三者任一失败都回滚——否则会出现「邮箱换了但旧人还在线」或「换了没留痕」。告警（`alertSuperAdmins`）现状只是写一条 `securityAlert` 日志（读过代码：`AdminAlertService` 就是 `securityAlert.warn(...)`），放事务内也无副作用。

### 🔴 唯一约束是本 story 最容易漏的地方

现状 `V32` 建的 `uq_admin_accounts_lark_email UNIQUE (lark_email)` 是全表唯一。D-21 要求「已停用邮箱可复用」，两者直接冲突——不改约束，`rebindEmail` 在 DB 层会被拒。改成部分唯一索引后，`findByLarkEmail` 这类**假设唯一**的 Repository 方法可能返回多行（一 ACTIVE + N DISABLED），Spring Data 会抛 `IncorrectResultSizeDataAccessException`。所以 T2 的「逐处核对调用点」不是可选项。读过的调用点：

- `AdminUserDetailsService.loadByEmail`：`adminAccounts.findByLarkEmail(email).filter(ACTIVE)` → 改 `findByLarkEmailIgnoreCaseAndStatus(email, ACTIVE)`，语义不变。
- `AdminBootstrap`：`adminAccounts.findByLarkEmail(bootstrapEmail).ifPresentOrElse(...)` → 同上；bootstrap 账号自身永远 ACTIVE（本 story 拒绝换绑它，Epic 内也没人能停用最后超管）。
- `AdminAccountService.createAccount`：`findByLarkEmail(email).isPresent()` → 改 ACTIVE 口径（PRD AB-16A ② 与 D-21 是一致的：新建也允许复用已停用邮箱）。
- 其他包若有引用（grep 确认），按同一原则处理。

### 🔴 bootstrap 超管的识别

`AdminBootstrap` 用 `@Value("${ADMIN_BOOTSTRAP_EMAIL:}")` 建号/更新，账号表里**没有**「是否 bootstrap」标记。本 story 用同一 env 值做比对（忽略大小写、trim），不新加列——env 为空时（stag/本地可能未配）该护栏自然不生效，测试里用 `ReflectionTestUtils.setField` 或构造器注入一个值来覆盖。

### 🔴 data-confirm 复述新旧值

PRD 要求确认弹层「复述旧邮箱 → 新邮箱」。现状 `admin.js` 的 `data-confirm` 是读静态属性文案直接 `confirm()`。最小改动：约定 `data-confirm` 文案可含 `{0}` `{1}` 占位，JS 在 submit 前从 `data-confirm-args`（`th:attr` 拼「旧邮箱」）与表单内 `input[name=newEmail]` 取值替换。这是通用能力，Story 2.2 拆 `admin-core.js` 时保留。

### 现状代码要点（读过的文件）

| 文件 | 现状 | 本 story 改什么 | 必须保留 |
|---|---|---|---|
| `db/migration/V32__init_admin_accounts.sql` | `uq_admin_accounts_lark_email UNIQUE (lark_email)` | 新迁移 DROP + 部分唯一索引 | 不改 V32（已应用） |
| `admin/account/repository/AdminAccountRepository.java` | `findByLarkEmail`、`countByAccountTypeAndStatus`、`findByAccountTypeAndStatus`… | + 两个按状态查询方法 | 既有方法签名（若仍有调用） |
| `admin/account/service/AdminAccountService.java` | 构造器 3 个依赖；`createAccount` 全表重复校验；1-1 加 `bumpSecurityVersion`；1-2 加 `rename` + self 护栏 | + `rebindEmail`；构造器 + `AdminAlertService` + bootstrapEmail；`createAccount` 重复校验改 ACTIVE 口径 | 其余全部 |
| `admin/account/domain/AdminAccount.java` | 无 `setLarkEmail` | + `setLarkEmail` | — |
| `admin/service/AdminUserDetailsService.java` | `loadByEmail` 用 `findByLarkEmail(...).filter(ACTIVE)` | 改用按状态查询 | 解析顺序（1-4 才改） |
| `admin/service/AdminBootstrap.java` | `findByLarkEmail(bootstrapEmail)` | 改用按状态查询 | 建号/更新逻辑 |
| `admin/account/web/AdminAccountAdminController.java` | 三个门控常量 | + `REBIND_AUTH`、`rebind-email` POST、`populate` 加 bootstrapEmail | 既有端点 |
| `templates/admin/admin-accounts.html` | 操作列四类操作（1-2 加改名） | + 换绑 `<details>`（仅超管）+ bootstrap 禁用态 | — |
| `static/admin/admin.js` | `data-confirm` 静态文案 `confirm()` | + `{0}` `{1}` 占位替换 | 其余行为 |
| `admin/audit/service/AdminAlertService.java` | `alertSuperAdmins(String event, Long actorAccountId)` 写 `securityAlert` 日志 | 无 | — |
| `admin/audit/service/AuditActions.java` | — | + `ACCOUNT_EMAIL_REBOUND` | — |

### 测试标准

- 单测 mock 范式同 `AdminAccountServiceTest`；新增 `AdminAlertService` mock。
- 部分唯一索引只能在 L1 真库验证（H2 不支持 `WHERE` 部分索引语法差异——项目 L1 用真 Postgres，无此问题）。
- 本地 L1 跑法：flush Redis DB0 → 重建 scratch 库 → `DB_NAME` env。

### Project Structure Notes

- 改动落 `admin/account/**`、`admin/service/{AdminUserDetailsService,AdminBootstrap}`、`AuditActions`、模板、`admin.js`、i18n、一支迁移；不新建模块。
- 不动 `SecurityConfig`、不动过滤器（1-1 已就位）。

### References

- [Source: _bmad-output/planning-artifacts/v1.3.0/PRD-v1.3.0-admin.md#2. AB-16A ②]
- [Source: _bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-admin-delta.md#AD-1 / Authentication & Security（换绑）]
- [Source: _bmad-output/planning-artifacts/v1.3.0/决策日志-admin.md D-1 / D-21]
- [Source: _bmad-output/planning-artifacts/v1.3.0/epics-v1.3.0-admin.md#Story 1.3]
- [Source: _bmad-output/planning-artifacts/v1.3.0/validation-admin-2026-09-09/review-adversarial-general.md #1 #27]
- [Source: petgo-backend/src/main/resources/db/migration/V32__init_admin_accounts.sql]
- [Source: petgo-backend/src/main/java/com/tailtopia/admin/service/AdminBootstrap.java]
- [Source: petgo-backend/src/main/java/com/tailtopia/admin/audit/service/AdminAlertService.java]

## Dev Agent Record

### Agent Model Used

（dev-story 填写）

### Debug Log References

### Completion Notes List

### File List
