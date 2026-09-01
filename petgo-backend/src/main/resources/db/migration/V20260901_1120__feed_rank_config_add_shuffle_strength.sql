-- 2026-09-01（产品拍板）：下拉刷新要「明显换一批」。
-- 此前刷新只换快照种子、种子不参与排序，排序确定性 ⇒ 刷新前后内容几乎不变
-- （唯一变化来源是曝光降权，且对游客不生效）。现在让种子以「确定性抖动」参与打分：
-- 最终分 ×（1 − 抖动幅度 × rand01(种子, 内容id)）。同一种子内排序稳定（翻页快照契约不变），
-- 换种子（下拉刷新）即换排序。
-- 0 = 关闭抖动（回到纯分数排序）；1 = 最强抖动。默认 0.8（要的就是「明显」）。
ALTER TABLE feed_rank_config
    ADD COLUMN shuffle_strength DOUBLE PRECISION NOT NULL DEFAULT 0.8;

ALTER TABLE feed_rank_config
    ADD CONSTRAINT ck_feed_rank_shuffle_range CHECK (shuffle_strength >= 0 AND shuffle_strength <= 1);

COMMENT ON COLUMN feed_rank_config.shuffle_strength IS
    '刷新抖动幅度 0–1：0=关闭（纯分数排序），越大刷新换得越狠。默认 0.8';
