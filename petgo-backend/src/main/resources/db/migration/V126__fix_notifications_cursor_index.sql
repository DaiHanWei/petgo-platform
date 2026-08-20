-- 工作线：V1.1.6（本分支迁移 V105、V106、V108–V112，本文件例外见下）
--
-- 🔴 2026-08-20 改号 V107 → V126：与 stag 撞号。
--    stag / hex/v1.1.4 的 `V107__backfill_view_tickets_permission.sql` **已 applied 在
--    petgo_stag 库上**，同号不同内容。两个文件一旦同处一棵树，Flyway 直接
--    「Found more than one migration with version 107」启动即崩；只留一个也会
--    checksum 校验失败。既成事实的那一侧动不了（动它＝已应用的迁移凭空消失 + 权限回填重跑），
--    所以由本文件让号。
--    取 126 而非 108：**全仓所有分支 100–125 都已占用**（1.1.4 占 101–104、107；
--    本线 105–112；shawn/oneline-ecommerce 占 113–125）。out-of-order=true 已开，
--    本迁移只建索引、与 V108–V112 无先后依赖，排在最后无副作用。
--
-- ⚠️ 本迁移与配套代码是**从 stag 搬过来的**（Shawn 2026-08-18 的 NOTIFY-CURSOR-TIE，
--    原号 V125）。同一处修复因此会在两条线上各有一个号，**合并时会出现两份内容相同的迁移** ——
--    到时删掉后落地的那一个即可，两边都用 `CREATE INDEX IF NOT EXISTS` / `DROP INDEX IF EXISTS`，
--    重复执行也不会出错。
--
-- ⚠️ 已经跑过旧号 V107 的**本地库**（不含 staging，那边从未 applied 过它）会报
--    「Detected applied migration not resolved locally: 107」而起不来。
--    该库是开发用的一次性数据，重建即可：DROP DATABASE / CREATE DATABASE 后重跑。
--    索引本身幂等，重建库或在旧库上应用 V126 都不会出错。
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
