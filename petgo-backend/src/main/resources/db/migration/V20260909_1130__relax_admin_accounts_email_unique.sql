-- V1.3.0 后台线 Story 1.3：换绑 Lark 邮箱（AB-16A ②，D-21）。
--
-- 已停用账号的邮箱视为已释放，可被换绑 / 新建复用。V32 建的 uq_admin_accounts_lark_email 是全表唯一，
-- 与此冲突：改为「仅 ACTIVE 唯一」的部分唯一索引（忽略大小写）。服务层仍显式校验，索引只是兜底。
-- 不改列，ddl-auto=validate 不受影响。

ALTER TABLE admin_accounts DROP CONSTRAINT uq_admin_accounts_lark_email;

CREATE UNIQUE INDEX uq_admin_accounts_lark_email_active
    ON admin_accounts (lower(lark_email)) WHERE status = 'ACTIVE';

COMMENT ON INDEX uq_admin_accounts_lark_email_active IS
    'Lark 邮箱仅在 ACTIVE 账号间唯一（D-21：已停用邮箱可复用）';
