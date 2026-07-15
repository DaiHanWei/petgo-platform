-- Story 9.8（A-6 / AB-1.1-02A）：虚拟账号（第四类账号）落地。号 V82（本分支当前 max V81；决策 E2）。
-- ⚠️ merge 时与 9-7（标识审核，其他分支）复核撞号顺延。
--
-- 复用 users 表 + account_type 枚举（REAL/VIRTUAL）：虚拟账号无 google 身份/无密码/无登录，复用 content_posts.author_id。
-- 生产已灌 20 虚拟作者（google_sub 前缀 seed-tailtopia-），回填为 VIRTUAL 与 id1-20 一致。

ALTER TABLE users
    ADD COLUMN account_type    VARCHAR(16) NOT NULL DEFAULT 'REAL',
    ADD COLUMN created_by      BIGINT,                              -- 建号 admin_accounts.id（虚拟账号）
    ADD COLUMN enabled         BOOLEAN     NOT NULL DEFAULT true,   -- 虚拟账号启停（REAL 恒 true）
    ADD COLUMN published_count INT         NOT NULL DEFAULT 0;      -- 虚拟账号已发布计数

ALTER TABLE users
    ADD CONSTRAINT ck_users_account_type CHECK (account_type IN ('REAL', 'VIRTUAL'));

-- 回填：已上线种子作者（google_sub 前缀 seed-tailtopia-）→ VIRTUAL。
UPDATE users SET account_type = 'VIRTUAL' WHERE google_sub LIKE 'seed-tailtopia-%';
