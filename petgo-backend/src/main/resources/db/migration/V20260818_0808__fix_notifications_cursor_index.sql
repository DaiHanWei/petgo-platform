-- 工作线：V1.4.0 精选自营电商（独占号段 V101–V139）
-- ⚠️ 本迁移改的是【共享模块 notify】的索引，不是电商表 —— 号从本工作线的独占段里取，
--    是为了保证与另两条线不撞号（并行契约 §1：号段独占，不按模块归属分配）。
--    本次改动经用户明确要求（sprint-status action_items: NOTIFY-CURSOR-TIE）。
--
-- 🔴 为什么要改索引：通知中心的游标分页从「只按 created_at」改成了复合游标
--    (created_at, id)，ORDER BY 随之变成 created_at DESC, id DESC。
--    原索引只有 (recipient_user_id, created_at DESC)，同刻记录多时 Postgres 需要
--    额外排序才能定序；把 id 加进索引后，翻页恢复为纯索引扫描 + LIMIT。
--
--    原缺陷本身（同一毫秒内的记录被整批跳过 → 用户永久看不到那几条通知）是代码问题，
--    见 NotificationRepository#findPageBefore 的注释；本文件只补上配套的索引。

CREATE INDEX IF NOT EXISTS idx_notifications_recipient_created_id
    ON notifications (recipient_user_id, created_at DESC, id DESC);

-- 旧索引是新索引的严格前缀，完全冗余（留着只是白占写入成本与磁盘）。
DROP INDEX IF EXISTS idx_notifications_recipient_created;
