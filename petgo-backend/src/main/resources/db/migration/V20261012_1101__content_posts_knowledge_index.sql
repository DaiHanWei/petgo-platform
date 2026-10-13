-- V1.3.4 Story 1.1（FR-122 / AD-2）：首页教育类独立候选池的部分索引。
-- 教育池 = 近 90 天全部 KNOWLEDGE 公开内容（按 created_at DESC 取上限 1000）。存量约 280~450 条，索引极小；
-- 没有它，查询会退化为扫 idx_content_posts_feed 的全部近 90 天行再按 type 过滤。
-- ⚠️ 不用 CONCURRENTLY：Flyway 迁移在事务里执行，CONCURRENTLY 不能在事务内运行；表量级下普通建索引锁表时间可忽略。
CREATE INDEX idx_content_posts_knowledge_feed
    ON content_posts (created_at DESC, id DESC)
    WHERE type = 'KNOWLEDGE' AND deleted_at IS NULL;
