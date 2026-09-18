-- V1.3.0 后台线 Story 1.4：AB-21A 角色配置（架构 AD-4 方案 A，决策 D-9）。
--
-- 岗位角色的权限从 AdminRole 枚举迁入表，运营可在后台编辑（Story 1.5）；
-- CUSTOM（账号级逐码勾选，admin_account_permissions）机制保留并存；
-- SUPER_ADMIN 与 OPS_MANAGER 继续硬编码（D-13）。本支只做 DDL，数据由 V20260909_1141 灌入。

CREATE TABLE admin_roles (
    id          BIGSERIAL    PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL,                 -- SYSTEM: OPERATIONS|FULFILLMENT|SUPPORT|FINANCE；CUSTOM: role-<id>
    name        VARCHAR(60)  NOT NULL,                 -- 自定义角色运营自填单语；SYSTEM 角色填中文兜底
    name_key    VARCHAR(80),                           -- SYSTEM 角色三语 key（role.<CODE>），CUSTOM 为 NULL
    role_type   VARCHAR(8)   NOT NULL,                 -- SYSTEM | CUSTOM
    created_by  BIGINT,                                -- admin_accounts.id；SYSTEM 行为 NULL
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_admin_roles_code UNIQUE (code),
    CONSTRAINT ck_admin_roles_type CHECK (role_type IN ('SYSTEM', 'CUSTOM'))
);

COMMENT ON TABLE admin_roles IS '后台岗位角色（Story 1.4）：SYSTEM 预置四岗 + 运营自建 CUSTOM；权限见 admin_role_permissions';

CREATE TABLE admin_role_permissions (
    role_id         BIGINT      NOT NULL REFERENCES admin_roles (id) ON DELETE CASCADE,
    permission_code VARCHAR(64) NOT NULL,              -- <模块>.<动作>，须属 AdminPermissions.ALL
    PRIMARY KEY (role_id, permission_code)
);

-- 有账号引用的角色不能删（Story 1.5 服务层先校验，DB RESTRICT 兜底）。
ALTER TABLE admin_accounts ADD COLUMN role_id BIGINT REFERENCES admin_roles (id) ON DELETE RESTRICT;
CREATE INDEX idx_admin_accounts_role_id ON admin_accounts (role_id);

COMMENT ON COLUMN admin_accounts.role_id IS
    '岗位角色表引用（Story 1.4）；SUPER_ADMIN/OPS_MANAGER/CUSTOM 为 NULL，权限解析见 RolePermissionResolver';
