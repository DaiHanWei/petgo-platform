-- 管理后台兽医账户头像（运营在后台上传，落公开桶①，存对外 CDN URL）。
-- 决策 E2：按 db/migration 实测最大号+1 = V58。加列不改既有迁移（冻结）。
-- nullable（存量兽医无头像 → App 回退首字母占位）。长度 1024 与 users.avatar_url 同。
ALTER TABLE vet_accounts ADD COLUMN avatar_url VARCHAR(1024);
