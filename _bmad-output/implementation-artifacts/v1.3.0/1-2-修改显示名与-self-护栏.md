---
baseline_commit: 4301227a
branch: feat/1.3.0-ops-ui-refactor
epic: 1
story: 1.2
ad: [AD-1]
decisions: [D-2]
depends_on: [1-1-账号变更版本号与自动踢重登]
---

# Story 1.2: 修改显示名与 self 护栏

Status: review

> 自包含 story，可本地或云端 L0 执行。与用户沟通用中文。执行纪律见根 `CLAUDE.md`（后端→前端→联调、AC 标 L0/L1/L2、`mvn -B clean package`）。
> 本 story **纯后台**（服务层 + Controller + 现有账号页模板 + 三语 key），无迁移、无 App 端改动。
> **依赖 1-1**：不需要它的版本号（改名不 bump），但代码基线含 1-1 对 `AdminAccountService` 的改动，合并顺序须在 1-1 之后。

## Story

As a 超级管理员或持 `admin.create_account` 的运营，
I want 在账号列表行内直接改某人的显示名，且系统不允许我停用自己或改自己的角色，
so that 人员改名不用重建账号，也不会误操作把自己锁在门外。

## Acceptance Criteria

**AC1 · 改名端点**
**Given** 账号列表页 `/admin/accounts`（现有 `admin-accounts.html`）
**When** 点某行「改名」→ 行内展开输入框（预填当前显示名）→ 提交 `POST /admin/accounts/{id}/rename`（表单字段 `displayName`）
**Then** 门控 `hasRole('SUPER_ADMIN') or hasAuthority('admin.create_account')`（与创建 / 改权限 / 改角色同门槛 `CREATE_AUTH`）`[L1]`
**And** 校验：trim 后非空且 ≤100 字符；否则 flash error `admin.err.account.displayNameRequired` / **新增** `admin.err.account.displayNameTooLong`，不落任何变更 `[L1]`
**And** 与旧值相同 → 幂等 no-op（不审计、不 flash error，flash notice 照常）`[L1]`
**And** 成功后 PRG 回本页，横幅 **新增** `admin.flash.account.renamed`：「已将账号 #{0} 显示名改为 {1}（对方重新登录后顶栏显示新名）」`[L2]`

**AC2 · 审计**
**Then** 同事务 `AdminAuditService.record(actor, AuditActions.ACCOUNT_RENAMED, "ADMIN_ACCOUNT", id, "显示名 旧名 → 新名（邮箱）")`；`AuditActions` **新增** `ACCOUNT_RENAMED` `[L1]`
**And** 🛡 **不递增 `security_version`**（改名不影响权限与身份，D-2；顶栏显示名在对方下次登录后自然更新）`[L0]`

**AC3 · self 护栏（服务层）**
**Given** 操作者账号 id == 目标账号 id
**When** 调 `deactivate(id, actorId)` 或 `changeRole(id, role, actorId)`
**Then** 抛 `AppException.validation(...).code("admin.err.account.selfDeactivate")` / `.code("admin.err.account.selfRoleChange")`，**在既有护栏（最后超管、超管上限、幂等）之前判断**，不落任何变更、不审计 `[L1]`
**And** `reactivate` / `updatePermissions` / `rename` 不加 self 护栏（对自己重新激活无意义——已登录必然 ACTIVE；改自己权限为 CUSTOM 账号既有能力，本版不动）`[L0]`
**And** 🛡 服务层是安全边界；控制器与模板的禁用态只是体验 `[L0]`

**AC4 · self 护栏（页面回显）**
**Given** 列表渲染到当前登录者自己那一行
**Then** 「停用」与「改角色」按钮渲染为 `disabled` + `title` 注明原因（**新增** key `admin.accounts.selfGuard`：「不能对自己执行此操作」）；UI 稿 7-7 防呆 F7-8 `[L2]`
**And** 其他行不受影响；「改名」对自己可用 `[L2]`

**AC5 · 既有护栏回归**
**Then** 「最后一个在职超管不可停用 / 降级」（A3）、超管上限 5、幂等 early-return、`changeRole` 权限 carry 语义全部不变；既有测试 `AdminAccountServiceTest`、`AdminAccountRoleServiceTest`、`AdminAccountManagementIntegrationTest`、`AdminAccountAccessControlTest` 全绿 `[L0/L1]`

**AC6 · 三语**
**Then** 新增 key（`admin.accounts.rename`、`admin.accounts.rename.save`、`admin.accounts.rename.cancel`、`admin.accounts.selfGuard`、`admin.flash.account.renamed`、`admin.err.account.displayNameTooLong`、`admin.err.account.selfDeactivate`、`admin.err.account.selfRoleChange`）在 `messages.properties` / `messages_zh_CN` / `messages_en` / `messages_id` 四包齐备，key 集合相等 `[L0]`

---

## Tasks / Subtasks

- [x] **T1 · 服务层 `rename`**（AC1 / AC2）
  - [x] `AdminAccountService` 新增 `@Transactional public void rename(long accountId, String displayName, long actorAccountId)`
  - [x] 校验顺序：findById（`admin.err.account.notFound`）→ trim → 空 → 超长（>100，与列 `display_name VARCHAR(100)` 一致）→ 与旧值相同 return → `a.setDisplayName(v)` → save → 审计
  - [x] `AdminAccount` 目前**没有 `setDisplayName`**（只有 `setPasswordHash` / `setStatus` / `setRole`），需新增；沿用「setter 最小暴露」风格
  - [x] `AuditActions` 加 `ACCOUNT_RENAMED = "ACCOUNT_RENAMED"`，放 `ACCOUNT_*` 段
  - [x] 🔴 审计 summary **不记密码 / 不记 PII 以外的东西**：只记「旧名 → 新名（邮箱）」，与既有 `ACCOUNT_ROLE_CHANGED` 文案风格一致（邮箱在既有审计里已作标识出现，沿用）

- [x] **T2 · self 护栏**（AC3）
  - [x] `deactivate`：`findById` 之后、幂等判断之前加 `if (accountId == actorAccountId) throw AppException.validation("不能停用自己的账号").code("admin.err.account.selfDeactivate");`
  - [x] `changeRole`：`findById` 之后、`newRole == null` 判断之后、幂等判断之前加同型判断 `.code("admin.err.account.selfRoleChange")`
  - [x] 🔴 放在幂等判断**之前**：对自己「改成同一个角色」也应被拒（语义是「不能对自己操作」，不是「没变化」）

- [x] **T3 · Controller**（AC1）
  - [x] `AdminAccountAdminController` 新增 `@PostMapping("/admin/accounts/{id}/rename") @PreAuthorize(CREATE_AUTH)`，参数 `@RequestParam("displayName") String displayName`，try/catch `AppException` → `flash.addFlashAttribute("error", msg.resolve(e))`，成功 `flash notice msg.get("admin.flash.account.renamed", id, displayName)`，`return "redirect:/admin/accounts"`——与既有五个 POST 完全同范式（PRG）
  - [x] `populate(model)` 增 `model.addAttribute("selfId", admin.getAdminAccountId())`：`accounts(...)` 方法签名加 `@AuthenticationPrincipal AdminUserDetails admin`

- [x] **T4 · 模板**（AC1 / AC4）
  - [x] `admin-accounts.html` 操作列 `.account-actions` 内新增「改名」`<details>`（与既有「改权限」`<details class="perm-details">` 同结构）：summary 按钮 `#{admin.accounts.rename}`，内含 `<input name="displayName" th:value="${a.displayName}" maxlength="100" required>` + 保存钮 + 取消（`<button type="button" onclick="this.closest('details').removeAttribute('open')">`），`sec:authorize` 同 `CREATE_AUTH`
  - [x] 停用表单与改角色表单：`th:with="isSelf=${a.id == selfId}"`，按钮 `th:disabled="${isSelf}" th:title="${isSelf} ? #{admin.accounts.selfGuard} : null"`；改角色的 `<select>` 也 `th:disabled`
  - [x] 🔴 停用表单带 `data-confirm`：禁用按钮时 `admin.js` 的 data-confirm 不会触发（按钮 disabled 不产生 submit），无需改 JS
  - [x] 本 story **不**套模板 B（Epic 6 Story 6.5 的事）；只在现有页面样式上加行内操作

- [x] **T5 · i18n**（AC6）
  - [x] 四包各加 8 个 key（见 AC6），印尼语参考既有 `admin.accounts.*` / `admin.err.account.*` 措辞
  - [x] 手工 diff 三包 key 集合（`scripts/ci/check-i18n-keys.sh` 由 Story 2.2 建）

- [x] **T6 · 测试**（AC5）
  - [x] L0 `AdminAccountServiceTest`：`renamePersistsTrimmedNameAndAudits`、`renameRejectsBlank`、`renameRejectsOver100`、`renameSameValueIsNoOp`（verify auditService 未调用）、`renameDoesNotBumpSecurityVersion`
  - [x] L0 `AdminAccountRoleServiceTest` / `AdminAccountServiceTest`：`cannotDeactivateSelf`、`cannotChangeOwnRole`（含「改成同角色也拒」）；`lastSuperAdmin*` 两条既有测试仍过
  - [x] L0 `AdminAccountAccessControlTest`：`renameNeedsCreateAccountAuthority`（无权 403；`admin.create_account` 200/302；SUPER_ADMIN 302）
  - [x] L1 `AdminAccountManagementIntegrationTest`：改名后 `list()` 回显新名、审计表新增 `ACCOUNT_RENAMED` 一行、`security_version` 不变
  - [x] L2（本地）：登录为 A，A 行「停用」「改角色」禁用带 title；B 行可用

- [x] **T7 · 云端执行须知**
  - [x] 云端只跑 L0：`mvn -B clean package`（surefire 单测）；L1 集成测试需 Docker postgres + redis，留本地
  - [x] Completion Notes 标注「L1/L2 待本地验收」

---

## Dev Notes

### 🎯 本 story 在 Epic 1 里的位置

1-1 已把「账号变更 → 踢重登」机制做好。本 story 是 AB-16A 四项里的两项：① 改显示名、③ self 护栏。换绑邮箱（②）在 1-3。三者都落 `AdminAccountService` + `AdminAccountAdminController` + `admin-accounts.html`，**分 story 是为了每条独立可测，不是因为文件不同**——实现时注意不要互相覆盖对方的改动（先合 1-1，再 1-2，再 1-3）。

### 🔴 改名为什么不 bump 版本号

D-2 拍板「权限重新登录后生效」，顶栏显示名与角色徽标同理允许滞后。显示名不参与任何鉴权、审计里记的是邮箱而非显示名，bump 只会无谓地把对方踢下线。**不要**在 `rename` 里调 `bumpSecurityVersion`。

### 🔴 self 护栏为什么放服务层且在幂等判断之前

- 控制器 / 模板层的 `disabled` 谁都能绕（直接 POST）。安全边界只能是 `AdminAccountService`。
- `changeRole` 现有顺序：findById → `newRole == null` → `oldRole == newRole` return → 超管上限 / 最后超管 → 写。self 判断插在 `newRole == null` 之后、幂等 return 之前——否则「给自己选当前角色再保存」会静默通过，语义上不该。
- `deactivate` 现有顺序：findById → `status == DISABLED` return → 最后超管 → 写。self 判断插在 findById 之后即可（已登录的自己必然 ACTIVE，幂等分支到不了，但放前面语义更直白）。
- 「最后一个在职超管」护栏（A3）在多超管场景下防不住自伤，这正是 PRD AB-16A ③ 的动机；两道护栏并存，各管各的。

### 现状代码要点（读过的文件）

| 文件 | 现状 | 本 story 改什么 | 必须保留 |
|---|---|---|---|
| `admin/account/service/AdminAccountService.java` | `list` / `createAccount` / `changeRole` / `updatePermissions` / `deactivate` / `reactivate` / `assertSuperAdminCap` / `sanitizePermissions`；1-1 加了 `bumpSecurityVersion` | + `rename`；`deactivate` / `changeRole` 各加 self 判断 | 所有既有护栏与顺序、审计文案、1-1 的 bump 调用点 |
| `admin/account/domain/AdminAccount.java` | 无 `setDisplayName` | + `setDisplayName(String)` | 工厂方法、`setRole` 同步 accountType |
| `admin/account/web/AdminAccountAdminController.java` | 五个 POST（create / permissions / role / deactivate / reactivate）全 PRG + `msg.resolve(e)`；`populate` 塞 accounts / allPermissions / permissionGroups / roles / rolePermissions | + `rename` POST；`populate` 加 `selfId`；`accounts()` 取 principal | `CREATE_AUTH` / `VIEW_AUTH` / `DEACTIVATE_AUTH` 三个表达式与既有映射 |
| `templates/admin/admin-accounts.html` | 操作列：改角色 `<form>`（select + 按钮）、改权限 `<details>`、停用（`data-confirm`）/ 重新激活 | + 改名 `<details>`；停用 / 改角色 self 禁用态 | 创建表单、角色权限预览、`sec:authorize` 表达式 |
| `admin/audit/service/AuditActions.java` | `ACCOUNT_CREATED / ROLE_CHANGED / DEACTIVATED / REACTIVATED`、`PERMISSION_GRANTED / REVOKED`、`ACCOUNT_WARNED / SUSPENDED / REPORT_DISMISSED` | + `ACCOUNT_RENAMED` | — |
| `shared/i18n/Messages.java` | `msg.get(key, args...)` / `msg.resolve(AppException)` | 无 | — |
| i18n 四包 | `admin.accounts.*`、`admin.flash.account.*`、`admin.err.account.*` | + 8 key | — |

### `AppException` 用法（与既有一致）

`AppException.validation("中文兜底文案").code("admin.err.account.xxx", args...)`；控制器 `msg.resolve(e)` 按当前语言取 key。新 key 必须四包都有，否则 `resolve` 回落到英文/兜底并可能在 L1 抛 `NoSuchMessageException`（取决于 `Messages` 的 fallback 策略——**实现前读一眼 `shared/i18n/Messages.java`**）。

### 测试标准

- 单测沿用 `AdminAccountServiceTest` 的 mock 范式（`accounts` / `permissions` / `auditService` 三个 mock）；`AdminAccountRoleServiceTest` 里已有 `changeRoleIsIdempotent`，self 测试并列其后。
- 访问控制沿用 `AdminAccountAccessControlTest`（MockMvc + `@WithMockUser` authorities）。
- L1 沿用 `AdminAccountManagementIntegrationTest extends ApiIntegrationTest`（真库）；本地 L1 跑法：flush Redis DB0 → 重建 scratch 库 → `DB_NAME` env。

### Project Structure Notes

- 全部改动落既有 `admin/account/**`、`admin/audit/service/AuditActions.java`、`templates/admin/admin-accounts.html`、i18n 四包；不新建类（`rename` 是方法不是类）。
- 不动 `SecurityConfig`、不动 `AdminSessionGuardFilter`、不动迁移。

### References

- [Source: _bmad-output/planning-artifacts/v1.3.0/PRD-v1.3.0-admin.md#2. AB-16A ①③④]
- [Source: _bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-admin-delta.md#AD-1 / Authentication & Security]
- [Source: _bmad-output/planning-artifacts/v1.3.0/决策日志-admin.md D-2]
- [Source: _bmad-output/planning-artifacts/v1.3.0/epics-v1.3.0-admin.md#Story 1.2]
- [Source: _bmad-output/planning-artifacts/v1.3.0/ui-v1.3.0-admin.html 帧 7-7]
- [Source: petgo-backend/src/main/java/com/tailtopia/admin/account/service/AdminAccountService.java]
- [Source: petgo-backend/src/main/java/com/tailtopia/admin/account/web/AdminAccountAdminController.java]
- [Source: petgo-backend/src/main/resources/templates/admin/admin-accounts.html]

## Dev Agent Record

### Agent Model Used

claude-fable-5-1（云端 headless session，2026-09-09）

### Debug Log References

- 云端 L0：`./mvnw -B clean package`（排除 191 个需真库的 Spring 上下文测试类）→ 1662 tests, 0 failures, BUILD SUCCESS。
- `AdminAccountServiceTest` 24/24（含 rename 5 条 + self 2 条）、`AdminAccountAccessControlTest` 7/7（含 rename 门控 2 条）。
- 四包 key 集合 diff 为空；flyway-guard 树内无重号（本 story 无迁移）。
- 复审（code-review low）无发现。

### Completion Notes List

- **L1/L2 待本地验收**：① `AdminAccountManagementIntegrationTest.renameShowsInListAuditsAndKeepsSecurityVersion`（真库：改名回显、`ACCOUNT_RENAMED` 审计、`security_version` 不变）；② 页面：登录为 A，A 行「停用」「改角色」（含 select）禁用带 title，B 行可用，「改名」对自己可用；③ 改名成功横幅 `admin.flash.account.renamed` 三语；④ 直接 POST 自己的 deactivate/role 被服务层拒（flash error 三语）。
- self 护栏放服务层：`deactivate` 在 findById 之后、幂等之前；`changeRole` 在 `newRole == null` 之后、幂等之前（对自己改成同角色也拒）。`reactivate` / `updatePermissions` / `rename` 不加。
- `rename` 不 bump 安全版本号（D-2）；同值幂等不审计；审计 summary 只记「旧名 → 新名（邮箱）」。
- 控制器 `accounts()` 签名加 `@AuthenticationPrincipal AdminUserDetails admin`（`selfId` 入模型），既有 `AdminAccountAccessControlTest.viewList` 同步传 principal。
- 模板改名用 `<details class="perm-details rename-details">` 复用既有改权限面板样式，本 story 不套模板 B（6.5 的事）。

### File List

- petgo-backend/src/main/java/com/tailtopia/admin/account/domain/AdminAccount.java（+ setDisplayName）
- petgo-backend/src/main/java/com/tailtopia/admin/account/service/AdminAccountService.java（+ rename、self 护栏）
- petgo-backend/src/main/java/com/tailtopia/admin/account/web/AdminAccountAdminController.java（+ rename POST、selfId）
- petgo-backend/src/main/java/com/tailtopia/admin/audit/service/AuditActions.java（+ ACCOUNT_RENAMED）
- petgo-backend/src/main/resources/templates/admin/admin-accounts.html
- petgo-backend/src/main/resources/i18n/messages{,_zh_CN,_en,_id}.properties（+8 key）
- petgo-backend/src/test/java/com/tailtopia/admin/account/service/AdminAccountServiceTest.java
- petgo-backend/src/test/java/com/tailtopia/admin/account/web/AdminAccountAccessControlTest.java
- petgo-backend/src/test/java/com/tailtopia/admin/account/AdminAccountManagementIntegrationTest.java
