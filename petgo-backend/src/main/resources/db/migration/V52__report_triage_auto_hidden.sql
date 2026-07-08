-- 内容审核 cm-6（举报处置增强：P0/P1/P2 阈值分级 + P0 自动预处置 + 举报者隐藏）。
-- 冻结基线 V46；本 story 增量 ALTER，实际号按 CI 合并顺序单调顺延。ddl-auto=validate：schema 归 Flyway。
-- 举报者隐藏无需新列（由 content_reports 存在性推导，见 spec §4.1）。

-- 举报驱动 P0 自动预处置：帖子从 PUBLISHED 转入 cm-2「仅作者可见待判」挂起态的时刻(UTC)。
-- 用途：① 兼作 2h SLA 计时起点；② 区分「发布时高风险挂起(cm-2)」与「已发布后举报驱动挂起(本 story)」，供后台队列展示来源。
-- NULL = 未因举报被预处置。挂起态可见性本身复用 cm-2 的 content_posts.status(UNDER_REVIEW)，不在此新增。
ALTER TABLE content_posts
    ADD COLUMN report_hidden_at TIMESTAMPTZ;

COMMENT ON COLUMN content_posts.report_hidden_at IS
    '举报驱动 P0 自动预处置转挂起(仅作者可见待判)的时刻(UTC);NULL=未预处置;2h SLA 起点+来源区分';

-- 扩 review_reason CHECK 取值域：新增 REPORT_P0（举报驱动 P0 预处置来源），区别于 cm-2 的发布时挂起原因。
ALTER TABLE content_posts
    DROP CONSTRAINT ck_content_posts_review_reason;
ALTER TABLE content_posts
    ADD CONSTRAINT ck_content_posts_review_reason
    CHECK (review_reason IS NULL OR review_reason IN ('RISK_HIGH', 'DEGRADED_FAILCLOSED', 'REPORT_P0'));

-- 举报者维度反查索引（供 Feed/详情「排除当前查看者已举报的帖」相关子查询：reporter 前导）。
-- 与 uq_content_reports_reporter_post(post_id, reporter_id) 前导列相反，覆盖 reporter 前导的查询。
CREATE INDEX idx_content_reports_reporter ON content_reports (reporter_id, post_id);
