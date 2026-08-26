-- 用户标签「下线」（V1.1.6 Story 11.3 · AB-12A）。与装饰标签（Story 11.2）同形状、同理由。
--
-- 🔴 为什么加列而非删除：`user_tag_assignments` 有 FK 指向 `user_tags`，标签删不掉；
--    就算能删，历史分配记录也会一起消失。
--
-- 下线语义：不可再分配，**已分配的照旧生效到各自 ends_at** —— 下线是"不再发新的"。
-- 🛡 用可空时间戳而非布尔：「什么时候下线的」也一并留档。
ALTER TABLE user_tags ADD COLUMN IF NOT EXISTS retired_at TIMESTAMPTZ;

COMMENT ON COLUMN user_tags.retired_at IS
    '下线时刻；NULL = 在线。下线后不可再分配，已分配的照旧生效到各自 ends_at（Story 11.3）。';
