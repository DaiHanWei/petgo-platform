-- Story 2.3 R2（F9 事件日期口径分离）：content_posts 加列 event_date。
-- 仅 type=GROWTH_MOMENT 有值（成长日历「事件日期」，决定档案侧时间线/日历显示位置）；
-- 日常/科普恒为 null。与 created_at（决定 Feed/「我的发布」排序）解耦。
--
-- 序号说明（决策 E2）：原 story 设想「并入 V4 CREATE TABLE」前提为「2-3 首建该表且未实现」。
-- 实际 V4 已落库且后叠 V5–V25，ddl-auto=validate 禁止 retroactive 改 V4（破坏 Flyway 校验和），
-- 故按执行顺序单调分配新序号 V26 起 ALTER。

ALTER TABLE content_posts ADD COLUMN event_date DATE;

COMMENT ON COLUMN content_posts.event_date IS '成长日历事件日期；仅 GROWTH_MOMENT 有值，与 created_at 排序解耦（F9）';
