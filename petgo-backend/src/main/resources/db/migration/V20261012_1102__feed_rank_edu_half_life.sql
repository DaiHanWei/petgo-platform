-- V1.3.4 Story 1.2（FR-122.3 / 122.4，AD-3）：科普（KNOWLEDGE）内容在首页推荐打分中的新鲜度半衰期（小时）。
-- 新鲜度 = 1 / (1 + 发布至今小时数 / 半衰期)。KNOWLEDGE 用本列（默认 720 = 30 天）；DAILY / GROWTH_MOMENT 仍写死 24。
-- 🔴 本列不在后台「算法参数」页露出（PRD OQ-1）：调整由工程直接改库，下一次生成推荐序列即生效（无缓存）。
-- 🛡 CHECK > 0：半衰期是分母，0 会除零、负数会让越老的内容分越高。
-- DOUBLE PRECISION 对齐实体 double（numeric ↔ double 会被 ddl-auto=validate 拒绝启动）。
ALTER TABLE feed_rank_config
    ADD COLUMN edu_freshness_half_life_hours DOUBLE PRECISION NOT NULL DEFAULT 720;

ALTER TABLE feed_rank_config
    ADD CONSTRAINT ck_feed_rank_edu_half_life_positive CHECK (edu_freshness_half_life_hours > 0);

COMMENT ON COLUMN feed_rank_config.edu_freshness_half_life_hours IS
    '科普内容推荐新鲜度半衰期（小时），默认 720；不在后台露出，工程直接改库即生效';
