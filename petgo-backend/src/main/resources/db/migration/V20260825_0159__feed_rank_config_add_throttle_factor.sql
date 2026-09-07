-- V1.1.6 Story 17.1 · AC5：限流系数落进既有的推荐参数单行表。时间戳版本号（决策 E6）。
--
-- ⚠️ 这一条**反转**了 16.4 写在 FeedRankConfig javadoc 里的「限流系数刻意不在本表」。
--    那句话当时的理由是「归 Epic 17」（延后），不是「不该在这里」——
--    与之并列的荣誉加成有真正的理由（它的唯一事实源是
--    ContentTagQueryService.RANK_WEIGHT_MULTIPLIER，存两份会改一处漏一处）。
--    限流系数**没有第二个事实源**，而它就是同一个打分公式里的一个乘法因子，
--    放进同一张单行表可以直接复用 FEED_RANK 的 diff 审计与配置页，不新增配置类型。
--
-- 🛡 CHECK 把系数夹在 (0, 1) 开区间里，两头都是有意的：
--    ≥ 1 就不是降权（等于没处置）；
--    = 0 会让分数恒为 0 ⇒ 永远排不进推荐序 ⇒ 事实上等于从首页下架，
--    而 AC2 明令「降权不是下架」。把这条写进 CHECK，是让那条 AC 在**配置层面**
--    也没法被绕过，而不是只靠运营手不抖。
ALTER TABLE feed_rank_config
    ADD COLUMN throttle_factor DOUBLE PRECISION NOT NULL DEFAULT 0.2;

ALTER TABLE feed_rank_config
    ADD CONSTRAINT ck_feed_rank_throttle_factor
        CHECK (throttle_factor > 0 AND throttle_factor < 1);
