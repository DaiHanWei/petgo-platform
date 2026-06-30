-- Story 3.2（管理后台 Epic 3）：普通用户停用/激活状态列（AB-UA-02）。
-- 实测最大号+1 = V36（V35=failed_consult_requests）。决策 E2 加列走 ALTER、冻结既有迁移。
-- 与「删除/注销」（物理删 + deletedAt）正交：status 承载可逆的「停用/激活」。
-- VARCHAR(16)（非 CHAR(1) 坑）；NOT NULL DEFAULT 'ACTIVE'（存量用户默认正常）。
ALTER TABLE users ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
