-- 专题线 video · Story 1.3（AD-1 / AD-2 / AD-6）：视频帖的媒体列与文件公开状态列。
-- 存量行一律 media_type='IMAGE'，视频列与 ACL 列为 NULL（DEFAULT 'IMAGE' 在 PG 11+ 为元数据操作，不重写表）。
-- 小整数列用 INTEGER（Hibernate int → int4），不用 SMALLINT。

ALTER TABLE content_posts
    ADD COLUMN media_type           VARCHAR(8) NOT NULL DEFAULT 'IMAGE',
    ADD COLUMN video_object_key     VARCHAR(256),
    ADD COLUMN video_duration_ms    INTEGER,
    ADD COLUMN video_width          INTEGER,
    ADD COLUMN video_height         INTEGER,
    ADD COLUMN video_size_bytes     BIGINT,
    ADD COLUMN media_acl_desired    VARCHAR(8),
    ADD COLUMN media_acl_applied    VARCHAR(8),
    ADD COLUMN media_acl_desired_at TIMESTAMPTZ;

COMMENT ON COLUMN content_posts.media_type IS '媒体类型 IMAGE/VIDEO；视频帖 image_urls=[封面]';
COMMENT ON COLUMN content_posts.video_object_key IS '视频对象 key（<keyPrefix>video/<userId>/<随机>.mp4），仅 VIDEO';
COMMENT ON COLUMN content_posts.video_duration_ms IS '视频时长（毫秒，客户端上报，3000–90000），仅 VIDEO';
COMMENT ON COLUMN content_posts.video_width IS '视频显示宽（按显示方向），仅 VIDEO';
COMMENT ON COLUMN content_posts.video_height IS '视频显示高（按显示方向），仅 VIDEO';
COMMENT ON COLUMN content_posts.video_size_bytes IS '视频实际大小（字节，发布时 HEAD 取得），仅 VIDEO';
COMMENT ON COLUMN content_posts.media_acl_desired IS '视频与封面期望 ACL（由实体按 desiredOf 推导），仅 VIDEO';
COMMENT ON COLUMN content_posts.media_acl_applied IS '视频与封面已生效 ACL（仅对账器条件 UPDATE 写），仅 VIDEO';
COMMENT ON COLUMN content_posts.media_acl_desired_at IS 'desired 最近一次变化时刻(UTC)，对账卡住告警计时起点';

ALTER TABLE content_posts
    ADD CONSTRAINT ck_content_posts_media_type CHECK (media_type IN ('IMAGE', 'VIDEO'));
ALTER TABLE content_posts
    ADD CONSTRAINT ck_content_posts_media_acl_desired
    CHECK (media_acl_desired IS NULL OR media_acl_desired IN ('PUBLIC', 'PRIVATE'));
ALTER TABLE content_posts
    ADD CONSTRAINT ck_content_posts_media_acl_applied
    CHECK (media_acl_applied IS NULL OR media_acl_applied IN ('PUBLIC', 'PRIVATE'));
ALTER TABLE content_posts
    ADD CONSTRAINT ck_content_posts_video_consistency
    CHECK (media_type = 'IMAGE' OR (video_object_key IS NOT NULL
           AND media_acl_desired IS NOT NULL AND media_acl_applied IS NOT NULL));

-- 对账器只扫「期望 ≠ 已生效」的视频帖。
CREATE INDEX idx_content_posts_media_acl_pending ON content_posts (id)
    WHERE media_type = 'VIDEO' AND media_acl_desired <> media_acl_applied;

-- review_reason 取值全集重建（上一次重建：V52，三值）：新增 VIDEO_FORCED（名单外视频帖强制人工审核）。
ALTER TABLE content_posts
    DROP CONSTRAINT ck_content_posts_review_reason;
ALTER TABLE content_posts
    ADD CONSTRAINT ck_content_posts_review_reason
    CHECK (review_reason IS NULL OR review_reason IN ('RISK_HIGH', 'DEGRADED_FAILCLOSED', 'REPORT_P0', 'VIDEO_FORCED'));
