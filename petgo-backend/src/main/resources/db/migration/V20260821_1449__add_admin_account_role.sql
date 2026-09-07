-- 后台岗位角色（管理后台优化 · 需求 2）：admin_accounts 增加 role 列。
--
-- 角色是加在既有 permission_code 之上的一层，不改动 admin_account_permissions 结构，
-- 也不改任何 @PreAuthorize 表达式。角色→权限码的映射写在代码里（com.tailtopia.admin.account.domain.AdminRole），
-- 本列只记「这个账号是什么岗位」，登录时按它解析权限码。
--
-- Flyway 号段说明（重要）：电商线当前占 V101–V125，且按《待发-4-Flyway改号方案》
-- 计划改号到 V140–V164；其它在世分支最高到 V106。取 V165 是为了在「改号已执行」与
-- 「改号未执行」两种情形下都不撞号（决策 E2：序号按执行顺序单调分配，勿复用号段）。

ALTER TABLE admin_accounts ADD COLUMN role VARCHAR(32);

-- 回填存量账号：
--   · 超管 → SUPER_ADMIN（隐式全权，与 account_type 一致）
--   · 其余 STAFF → CUSTOM，即「权限仍由 admin_account_permissions 的勾选行决定」，
--     与本次改动前的行为逐位相同 —— 存量账号零行为变化。
UPDATE admin_accounts SET role = 'SUPER_ADMIN' WHERE account_type = 'SUPER_ADMIN';
UPDATE admin_accounts SET role = 'CUSTOM'      WHERE role IS NULL;

ALTER TABLE admin_accounts ALTER COLUMN role SET NOT NULL;

ALTER TABLE admin_accounts ADD CONSTRAINT ck_admin_accounts_role
    CHECK (role IN ('SUPER_ADMIN', 'OPS_MANAGER', 'OPERATIONS', 'FULFILLMENT',
                    'SUPPORT', 'FINANCE', 'CUSTOM'));

-- role 与 account_type 必须自洽：SUPER_ADMIN 角色 ⇔ SUPER_ADMIN 类型。
-- 应用层由 AdminRole.accountType() 单向推导保证；这里再加一道库级兜底，防手工改库改出矛盾组合。
ALTER TABLE admin_accounts ADD CONSTRAINT ck_admin_accounts_role_type
    CHECK ((role = 'SUPER_ADMIN') = (account_type = 'SUPER_ADMIN'));

COMMENT ON COLUMN admin_accounts.role IS
    '后台岗位角色；CUSTOM 表示按 admin_account_permissions 逐码授权，其余按 AdminRole 模板解析';
