-- 场所举报工单（V1.3.0 batch-b1 Story 1.5 · AC5 · FR-112.3「举报入口（复用五类选项）」）。
--
-- 🔴 **独立建表，不改既有 content_reports、不用多态外键** —— 与 AD-8 对场所评论的判据完全一致：
--    既有 `content_reports.post_id` 是 NOT NULL 且语义绑死内容帖，共表就得把它改成可空再加场所列，
--    那正是 v1.1.6 AD-10 已经否过的多态外键。
--    「复用五类选项」复用的是 **reason 取值域与那张抽屉的文案**（AC5 要求一字不改），
--    不是复用那张表。
--
-- 🔴 **无自动下架**：与内容举报同一条口径（Story 3.7 / FR-25）——写工单 status=PENDING 进运营队列，
--    处置在后台 AB-17A。场所被下架走 places.status，与本表无关。
--
-- reason_type / status 的取值域**与 content_reports 同源**（同一个 Java 枚举
-- ReportReason / ReportStatus），所以这里不再单独写 CHECK：写了就是第二份清单，
-- 而两份清单迟早对不上（ck_notifications_type 已经教过四次）。
CREATE TABLE IF NOT EXISTS place_reports (
    id          BIGSERIAL PRIMARY KEY,
    place_id    BIGINT       NOT NULL,
    reporter_id BIGINT       NOT NULL,
    reason_type VARCHAR(16)  NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 同一个人对同一个场所只留一条（重复举报在 service 层幂等，不报错）。
-- 没有这条约束的话，一个人连点五次举报就是五张工单，运营队列会被同一件事刷满。
CREATE UNIQUE INDEX IF NOT EXISTS uq_place_reports_reporter_place
    ON place_reports (place_id, reporter_id);

-- 运营队列按状态 + 时间取（后台 AB-17A）。
CREATE INDEX IF NOT EXISTS ix_place_reports_status_created
    ON place_reports (status, created_at DESC);

COMMENT ON TABLE  place_reports             IS '场所举报工单（Story 1.5）；独立于 content_reports，无自动下架';
COMMENT ON COLUMN place_reports.place_id    IS '被举报场所 places.id（站内标识，不外露）';
COMMENT ON COLUMN place_reports.reason_type IS '五类举报原因，取值域同 content_reports（Java 枚举 ReportReason）';
COMMENT ON COLUMN place_reports.status      IS 'PENDING / … 由运营流转，取值域同 ReportStatus';
