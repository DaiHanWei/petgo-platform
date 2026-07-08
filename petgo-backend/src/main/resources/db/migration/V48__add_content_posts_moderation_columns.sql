-- 内容审核 story 2（帖子审核可见性新模型 + 激活 FR-12A）。冻结基线 V46，本 story 增量 ALTER。
-- content_posts 加审核元数据：风险分（story1 阿里云评分落库）、入队原因、内容版本键（D-CM3 陈旧作废）。
ALTER TABLE content_posts
    ADD COLUMN moderation_risk_score NUMERIC(4,3),          -- [0.000,1.000] 三方风险分；<0.8 直发不必落，可空
    ADD COLUMN review_reason         VARCHAR(24),           -- 入队原因 UPPER_SNAKE：RISK_HIGH / DEGRADED_FAILCLOSED；非挂起为空
    ADD COLUMN content_version       INTEGER NOT NULL DEFAULT 1;  -- D-CM3 版本键；编辑一次 +1；审核结果绑定此版本

-- review_reason 取值约束（varchar + CHECK，UPPER_SNAKE；允许 NULL）
ALTER TABLE content_posts
    ADD CONSTRAINT ck_content_posts_review_reason
    CHECK (review_reason IS NULL OR review_reason IN ('RISK_HIGH', 'DEGRADED_FAILCLOSED'));
