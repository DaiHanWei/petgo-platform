-- V1.3.0 batch-b1 · Story 3.2「@ 选择器与插入」AC4：正文与评论里的 @ **存 userId 不存昵称**
-- （AD-10 Rule 4 / 决策 B1-D12）。存昵称的话对方改名后历史 @ 全部失效、点不动，
-- 也无法判断拉黑关系 —— 所以文本里只留可读的「@昵称」，可点的那一份身份放在这一列。
--
-- 🔴 为什么是 JSONB 列而不是一张 mentions 关系表
--   1. v1.1.6 AD-10 的先例是「**不用多态外键**」。一张 mentions(target_type, target_id) 表正是那个被否掉的形状；
--      要守住它就得建 post_mentions + comment_mentions 两张表，而每张表里只有 ≤5 行 bigint。
--   2. 这份数据**从不被独立查询**：渲染（Story 3.3）与发通知（Story 3.4）都是拿着帖子/评论那一行在手时才用它，
--      没有「按被 @ 的人反查内容」的需求（那是被 @ 通知列表，走 notifications 表，不走这里）。
--   3. 上限硬定在 5（AC5），不会长成无界数组。
--   仓内同形先例：content_posts.image_urls / image_sizes、places.tags 都是 JSONB 数组。
--
-- ⚠️ **存量一律 NULL，零回填**（AD-10 Rule 6：存量文本不回溯解析）。NULL 与 '[]' 在读取侧等价，
--    实体里统一归一成空表，不要为了"好看"去 UPDATE 存量行。
ALTER TABLE content_posts ADD COLUMN mentioned_user_ids JSONB;
ALTER TABLE comments      ADD COLUMN mentioned_user_ids JSONB;

COMMENT ON COLUMN content_posts.mentioned_user_ids IS
    '正文里 @ 到的 userId 数组（≤5，Story 3.2 AC4/AC5）。存量为 NULL，不回溯解析。';
COMMENT ON COLUMN comments.mentioned_user_ids IS
    '评论里 @ 到的 userId 数组（≤5，Story 3.2 AC4/AC5）。存量为 NULL，不回溯解析。';
