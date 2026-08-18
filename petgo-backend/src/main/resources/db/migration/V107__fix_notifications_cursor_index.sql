-- 工作线：V1.1.6（本分支迁移从 V105 起，V101–V104 归 hex/v1.1.4）
--
-- ⚠️ 本迁移与配套代码是**从 stag 搬过来的**（Shawn 2026-08-18 的 NOTIFY-CURSOR-TIE，
--    原号 V125）。同一处修复因此会在两条线上各有一个号，**合并时会出现两份内容相同的迁移** ——
--    到时删掉后落地的那一个即可，两边都用 `CREATE INDEX IF NOT EXISTS` / `DROP INDEX IF EXISTS`，
--    重复执行也不会出错。
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
