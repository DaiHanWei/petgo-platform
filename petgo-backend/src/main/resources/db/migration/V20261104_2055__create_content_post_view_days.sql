-- V1.4.2 social-topic · Story 4.1 · 迁移 ③（架构 D-3 / P-8 ③ · PRD FR-155）：帖子按天浏览去重。
--
-- 一行 = 某观看者在某个 WIB 日打开过某帖详情（同一天多次只算一行）。
--   view_date 是 WIB 日期（ScheduleWindow.WIB），不是 UTC 日期。
--   viewer_key 与 content_post_views.viewer_key 同格式：'u:<userId>' / 'a:<匿名会话id>'。
--
-- 只供话题热度（FR-155 AC4，4.2 热度任务读），不在后台 / App 展示；
--   后台「浏览次数 / 浏览人数」两列仍读 content_post_views，口径不变。
-- 不回填（存量无按天数据）；保留 30 天，由 ContentViewDayCleanupJob 每日清理（DELETE WHERE view_date < cutoff）。
-- 无 JPA 实体：写入走原生 INSERT … ON CONFLICT DO NOTHING（同 content_post_views 的 upsertView 理由）。

CREATE TABLE content_post_view_days (
    post_id    BIGINT      NOT NULL,
    view_date  DATE        NOT NULL,       -- WIB 日期（ScheduleWindow.WIB），不是 UTC 日期
    viewer_key VARCHAR(40) NOT NULL,       -- 与 content_post_views.viewer_key 同格式
    CONSTRAINT pk_content_post_view_days PRIMARY KEY (post_id, view_date, viewer_key),
    CONSTRAINT fk_content_post_view_days_post FOREIGN KEY (post_id) REFERENCES content_posts (id) ON DELETE CASCADE
);

-- 清理任务 DELETE WHERE view_date < ?
CREATE INDEX idx_content_post_view_days_date ON content_post_view_days (view_date);
