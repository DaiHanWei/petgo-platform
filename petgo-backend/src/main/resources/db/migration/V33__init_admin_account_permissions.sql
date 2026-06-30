-- Story 1.1（管理后台 Epic 1）：后台账号模块权限表 admin_account_permissions。
-- 两级权限模型的 STAFF 模块授权载体（permission_code，如 vet.view / content.takedown）。
-- 本故事仅建表（为 1.5 预留）；JPA 实体与填充逻辑在 Story 1.5 引入（ddl-auto=validate 对无实体的表不校验）。
-- SUPER_ADMIN 隐式全权、不进本表。

CREATE TABLE admin_account_permissions (
    account_id      BIGINT      NOT NULL,
    permission_code VARCHAR(64) NOT NULL,                 -- <模块>.<动作> 全小写点分（附录 B）
    PRIMARY KEY (account_id, permission_code),
    CONSTRAINT fk_admin_account_permissions_account
        FOREIGN KEY (account_id) REFERENCES admin_accounts (id) ON DELETE CASCADE
);
