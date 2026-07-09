-- 内容审核补充规范 story 3（评论审核 + FR-55A 巡查下架 + 通知时机）。冻结基线 V46，本 story 增量 ALTER。
-- ① comments 加审核状态 + 内容版本键（评论审核可见性收敛 + D-CM3 陈旧作废）。
--   moderation_status: 评论对他人的可见性态。存量评论全部视为已公开 → 默认 VISIBLE（grandfather）。
--     VISIBLE       正常通过，对他人可见（正常 <0.8 路径）
--     UNDER_REVIEW  三方降级挂起（fail-closed）：仅作者可见、无标签，待人工队列判定
--     TAKEN_DOWN    FR-55A 巡查下架：仅作者可见 + 「违规下架」标签
--     REJECTED      降级队列被运营拒绝 / 超时丢弃：仅作者可见（终态）
ALTER TABLE comments
    ADD COLUMN moderation_status VARCHAR(16) NOT NULL DEFAULT 'VISIBLE',
    -- 内容版本：body 每次变更 +1，供陈旧审核结果作废（D-CM3）。V1 无编辑端点故恒为 1（休眠契约）。
    ADD COLUMN content_version   INT         NOT NULL DEFAULT 1;

ALTER TABLE comments
    ADD CONSTRAINT ck_comments_moderation_status CHECK
        (moderation_status IN ('VISIBLE', 'UNDER_REVIEW', 'TAKEN_DOWN', 'REJECTED'));

-- 读路径 viewer 可见性过滤高频：VISIBLE 走公开列表；作者看自己非 VISIBLE 的走 author_id。
CREATE INDEX idx_comments_moderation ON comments (post_id, moderation_status);

-- ② manual_review_queue 扩展为多态：区分帖子 / 评论条目（原表仅帖子）。
--   存量队列项全部是帖子 → 默认 CONTENT_POST（grandfather）。
ALTER TABLE manual_review_queue
    ADD COLUMN content_type    VARCHAR(16) NOT NULL DEFAULT 'CONTENT_POST',
    -- 入队时捕获的内容版本，出结果时与当前版本比对做陈旧作废（D-CM3）。帖子/评论通用，可空。
    ADD COLUMN content_version INT;

ALTER TABLE manual_review_queue
    ADD CONSTRAINT ck_manual_review_queue_content_type CHECK
        (content_type IN ('CONTENT_POST', 'COMMENT'));
