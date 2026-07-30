-- Story 2.2（V1.1 Epic 2）：AI 分诊详建付费解锁态。给既有 triage_tasks（V12/Story 4.1）加两列。
-- ⚠️ 表名纠偏：架构文档写「ALTER triage_records」，但真实表是 triage_tasks（不存在 triage_records）。
-- 接 2-1 的 V63 顺延 V64（决策 E2：加列一律新迁移 ALTER，不动旧迁移；2-1 占 V63 故下游 +1 单调顺延）。
-- 两列可空：历史行 + PENDING/FAILED 行 unlock_source 保持 NULL = 无解锁语义（「生成失败不建记录」）。
-- VARCHAR(16) 非长度=1，无 CHAR(1) 坑。unlock_source 一经写入 FREE_QUOTA/PAID 不可覆盖（应用层 unlock() 守卫）。
ALTER TABLE triage_tasks ADD COLUMN unlock_source  VARCHAR(16);
ALTER TABLE triage_tasks ADD COLUMN unlock_channel VARCHAR(16);
ALTER TABLE triage_tasks ADD CONSTRAINT ck_triage_tasks_unlock_source
    CHECK (unlock_source IS NULL OR unlock_source IN ('LOCKED', 'FREE_QUOTA', 'PAID'));
ALTER TABLE triage_tasks ADD CONSTRAINT ck_triage_tasks_unlock_channel
    CHECK (unlock_channel IS NULL OR unlock_channel IN ('QRIS', 'DANA', 'PAWCOIN'));
