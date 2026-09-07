-- 装饰标签「下线」（V1.1.6 Story 11.2 · AB-10C）。
--
-- 🔴 为什么必须加一列、不能用删除代替：
--   `content_tag_assignments` 有 FK 指向 `content_tags`，标签删不掉；
--   就算能删，历史分配记录也会一起消失，运营再也查不到"这条内容当时挂的是什么标签"。
--
-- 下线语义（Story 11.2 AC1）：
--   ✅ 不可再被分配（新打标时拒绝）
--   ✅ **已分配的照旧生效到各自 ends_at** —— 下线是"不再发新的"，不是"把已发的追回"。
--      真要立刻全部失效，运营应逐条取消分配；那是另一个动作，刻意不合并。
--
-- 🛡 沿用本模块既有取舍：**不落状态列**，用"下线时刻"这一个可空时间戳表达
--   （NULL = 在线）。这样"什么时候下线的"也一并留档，而一个布尔值只能回答"是不是"。
ALTER TABLE content_tags ADD COLUMN IF NOT EXISTS retired_at TIMESTAMPTZ;

COMMENT ON COLUMN content_tags.retired_at IS
    '下线时刻；NULL = 在线。下线后不可再分配，已分配的照旧生效到各自 ends_at（Story 11.2）。';
