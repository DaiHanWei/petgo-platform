-- V1.4.0 ① Tailrent Level（AD-15 / FR-144.5 · Story 3.4）：首页推荐流的作者等级加权。
-- Lv15 及以上真实作者的帖子在推荐打分末尾乘本系数（内容分 × 曝光衰减 × 荣誉加成 × 限流系数 × 作者系数），默认 1.05。
-- Lv15 门槛写死在代码（PRD 拍板，不做参数）；虚拟号 / 官方号不参与成长值、不吃加权（AC12）。
-- 后台「算法参数」页露出（K1），改动写 config_change_logs（config_type = FEED_RANK，已在 CHECK 全集内，不动共享约束）。
-- 🛡 CHECK [1.00, 2.00]：< 1 就成了降权，与「权益」相反；上限 2 防误填 10 之类把推荐流整个压给少数人。
-- ⚠️ 偏离架构的 NUMERIC(4,2)：用 DOUBLE PRECISION 对齐实体 double（numeric ↔ double 会被 ddl-auto=validate 拒绝启动，
--    同 V20260824_1622 / V20261012_1102 的既有口径）。
ALTER TABLE feed_rank_config
    ADD COLUMN author_level_boost DOUBLE PRECISION NOT NULL DEFAULT 1.05;

ALTER TABLE feed_rank_config
    ADD CONSTRAINT ck_feed_rank_author_level_boost
        CHECK (author_level_boost >= 1.00 AND author_level_boost <= 2.00);

COMMENT ON COLUMN feed_rank_config.author_level_boost IS
    '作者等级加权：Lv15 及以上真实作者在首页推荐流的乘法系数，默认 1.05，1 = 不加权；Lv15 门槛写死在代码';
