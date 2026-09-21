-- V1.3.0 后台线 Story 1.5：账号引用「自定义角色」时 admin_accounts.role = ROLE_TEMPLATE
-- （权限来自 role_id → admin_role_permissions；解析见 RolePermissionResolver）。
-- 改 CHECK 必须重列全集（CLAUDE.md 规则）：上一版全集见 V20260821_1449（七值），本支加 ROLE_TEMPLATE。
-- ck_admin_accounts_role_type CHECK ((role='SUPER_ADMIN') = (account_type='SUPER_ADMIN')) 不变，ROLE_TEMPLATE 对应 STAFF 自洽。

ALTER TABLE admin_accounts DROP CONSTRAINT ck_admin_accounts_role;
ALTER TABLE admin_accounts ADD CONSTRAINT ck_admin_accounts_role
    CHECK (role IN ('SUPER_ADMIN', 'OPS_MANAGER', 'OPERATIONS', 'FULFILLMENT',
                    'SUPPORT', 'FINANCE', 'CUSTOM', 'ROLE_TEMPLATE'));

COMMENT ON COLUMN admin_accounts.role IS
    '后台岗位角色；CUSTOM 按 admin_account_permissions 逐码授权；ROLE_TEMPLATE 引用自定义角色（role_id）；其余按预置角色解析';
