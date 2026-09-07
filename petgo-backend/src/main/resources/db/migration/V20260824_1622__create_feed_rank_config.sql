-- V1.1.6 Story 16.4（FR-95）：首页推荐算法打分参数外化。时间戳版本号（决策 E6）。
--
-- 🔴 这不是优化项。FR-95 与首页点赞（FR-93）**同批发版** ⇒ 开发阶段线上不存在 source=feed
-- 的点赞数据 ⇒ P95、评论权重、0.6/0.4 在开发阶段无从校准 ⇒ **第一次校准必然发生在发版之后**
-- （OQ-B1）。写死意味着那次校准要走一次完整发版流程 —— 而它必然会发生，不是可能。
--
-- 种子值 = Story 16.1/16.2 的 yml 默认值（行为零变化）。
-- ⚠️ 权重列用 DOUBLE PRECISION 而不是 NUMERIC：实体侧是 double，
--    ddl-auto=validate 会因 numeric ↔ float(53) 不匹配**直接拒绝启动**（本 story 撞过一次）。
--    打分权重不需要十进制精确存储，对齐 Java 类型比省几个字节重要。
-- ⚠️ 荣誉加成（1.3）**不在本表**：既有 ContentTagQueryService.RANK_WEIGHT_MULTIPLIER 是唯一事实源，
--    再存一份就会出现"改了一处没改另一处"。限流系数（0.2）归 Epic 17，同理不在此。

CREATE TABLE feed_rank_config (
    id                     BIGINT      PRIMARY KEY,
    -- 打分公式：内容分 = freshness_weight × 新鲜度 + interaction_weight × 互动度
    freshness_weight       DOUBLE PRECISION NOT NULL,
    interaction_weight     DOUBLE PRECISION NOT NULL,
    -- 互动度 = ln(1 + 赞 + comment_weight × 评) / ln(1 + interaction_p95)
    comment_weight         DOUBLE PRECISION NOT NULL,
    -- 🔴 动态值：近 30 天互动量的 95 分位，由后台扫描定期重算（不是一次写死的常数）。
    --    0 = 尚未算出（冷启动）；重算失败时**沿用上一次的值**，绝不回落 0。
    interaction_p95        DOUBLE PRECISION NOT NULL,
    p95_recomputed_at      TIMESTAMPTZ  NULL,
    -- 已曝光内容的乘数（降权不是硬过滤）与曝光记录保留窗口
    exposure_decay         DOUBLE PRECISION NOT NULL,
    seen_window_days       INTEGER      NOT NULL,
    -- 配比窗口大小，以及窗口内的属性配比 / 物种配比
    window_size            INTEGER      NOT NULL,
    attr_fun_quota         INTEGER      NOT NULL,
    attr_edu_quota         INTEGER      NOT NULL,
    attr_life_quota        INTEGER      NOT NULL,
    species_main_quota     INTEGER      NOT NULL,
    species_other_quota    INTEGER      NOT NULL,
    species_general_quota  INTEGER      NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_feed_rank_singleton CHECK (id = 1),
    -- 🛡 表级兜底（业务层再校验一遍并给人话报错）：
    --    两项配比之和都必须等于窗口大小，否则窗口凑不满或溢出 —— 而那**不会报错**，
    --    只会让首页节奏莫名其妙，且极难被想到去查配置。
    CONSTRAINT ck_feed_rank_attr_sum
        CHECK (attr_fun_quota + attr_edu_quota + attr_life_quota = window_size),
    CONSTRAINT ck_feed_rank_species_sum
        CHECK (species_main_quota + species_other_quota + species_general_quota = window_size),
    CONSTRAINT ck_feed_rank_nonneg CHECK (
        freshness_weight >= 0 AND interaction_weight >= 0 AND comment_weight >= 0
        AND interaction_p95 >= 0 AND exposure_decay >= 0
        AND seen_window_days >= 1 AND window_size >= 2
        AND attr_fun_quota >= 0 AND attr_edu_quota >= 0 AND attr_life_quota >= 0
        AND species_main_quota >= 0 AND species_other_quota >= 0 AND species_general_quota >= 0
    ),
    -- 🛡 曝光衰减必须 ≤ 1：> 1 就是"看过的排更前面"，与这一维的意图完全相反。
    CONSTRAINT ck_feed_rank_decay_le_one CHECK (exposure_decay <= 1)
);

INSERT INTO feed_rank_config (
    id, freshness_weight, interaction_weight, comment_weight,
    interaction_p95, p95_recomputed_at, exposure_decay, seen_window_days, window_size,
    attr_fun_quota, attr_edu_quota, attr_life_quota,
    species_main_quota, species_other_quota, species_general_quota)
VALUES (1, 0.6, 0.4, 2.0, 0, NULL, 0.3, 7, 10, 5, 3, 2, 6, 2, 2);

COMMENT ON COLUMN feed_rank_config.interaction_p95 IS
    '近 30 天互动量 95 分位，定期重算；0=尚未算出（此时由候选池现算兜底）。重算失败沿用上次值。';
