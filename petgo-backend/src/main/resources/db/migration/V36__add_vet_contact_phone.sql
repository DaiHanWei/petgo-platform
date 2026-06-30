-- Story 2.3（管理后台 Epic 2）：兽医联系手机号（运营私下联系用，非登录凭证）。
-- CLAUDE.md/决策 E2：按 db/migration 实测最大号+1 = V34（V33=vet_qualifications；规划表的 V38 为旧编号，不照搬）。
-- 加列不改既有迁移（冻结）。nullable（存量兽医无值）。长度 32 含国家码/分隔符余量。
ALTER TABLE vet_accounts ADD COLUMN contact_phone VARCHAR(32);
