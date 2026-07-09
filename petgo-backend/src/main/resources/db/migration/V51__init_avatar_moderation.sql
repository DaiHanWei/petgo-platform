-- 内容审核补充规范 story 5（用户/宠物头像异步图像审核 + 违规重置平台默认头像）。冻结基线 V46，本 story 增量 CREATE/ALTER。
-- 占位号 V51；CI 合并时按实际顺序单调顺延，勿硬编码撞号。
-- 护栏：枚举 varchar + UPPER_SNAKE；时间戳 timestamptz UTC；无 length=1 列（避免 Hibernate CHAR(1) → validate 全红）。

-- ① 头像审核记录（头像侧独立状态机 + 人工队列，与名称侧 name_moderation_records 并列同构，
--   不复用帖子 manual_review_queue、不改其 content_type CHECK；理由见 spec §4.1，CM2 名称/头像各自独立表）。
--   subject_id 为内部外键值（USER_AVATAR=users.id / PET_AVATAR=pet_profiles.id），绝不外露。
--   avatar_url 为送审头像 URL（版本键，D-CM3）→ 出结果/处置时与当前对象头像比对，不等即陈旧作废；严禁写入业务日志（护栏 §5.6）。
CREATE TABLE avatar_reviews (
    id           BIGSERIAL     PRIMARY KEY,
    subject_type VARCHAR(16)   NOT NULL,                          -- USER_AVATAR / PET_AVATAR
    subject_id   BIGINT        NOT NULL,                          -- users.id 或 pet_profiles.id（内部值）
    avatar_url   VARCHAR(1024) NOT NULL,                          -- 送审头像 URL（版本键；禁入日志）
    risk_score   NUMERIC(4,3),                                    -- 三方图像综合风险分 0.000–1.000；降级/未评分为 NULL
    verdict      VARCHAR(16),                                     -- PASS/PENDING_REVIEW/VIOLATION/STALE_DISCARDED/DEGRADED_QUEUED；QUEUED 时 NULL
    status       VARCHAR(16)   NOT NULL,                          -- 状态机见 spec §5.2
    priority     VARCHAR(8)    NOT NULL DEFAULT 'NORMAL',         -- NORMAL / HIGH（图像高置信违规或 ≥0.8）
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_avr_subject_type CHECK (subject_type IN ('USER_AVATAR', 'PET_AVATAR')),
    CONSTRAINT ck_avr_priority     CHECK (priority IN ('NORMAL', 'HIGH')),
    CONSTRAINT ck_avr_status       CHECK (status IN ('QUEUED', 'AUTO_PASSED', 'MANUAL_PENDING', 'RESOLVED')),
    CONSTRAINT ck_avr_verdict      CHECK (verdict IS NULL OR verdict IN (
        'PASS', 'PENDING_REVIEW', 'VIOLATION', 'STALE_DISCARDED', 'DEGRADED_QUEUED'))
);

CREATE INDEX idx_avr_subject      ON avatar_reviews (subject_type, subject_id);
-- 队列列表高频：仅人工待处置项（部分索引）。
CREATE INDEX idx_avr_pending_queue ON avatar_reviews (created_at)
    WHERE status = 'MANUAL_PENDING';

-- ② 通知类型扩展：新增 AVATAR_RESET（头像违规重置后推送，targetRef 区分用户头像/宠物头像）。
--   cm-4 的 V50 已把 NAME_RESET 加入本 CHECK；本迁移在完整列表基础上追加 AVATAR_RESET（DROP + re-ADD 全量）。
--   ⚠️ cm-7 的 V53 只加 CONTENT_REVIEW_TIMED_OUT（AVATAR_RESET 由本 story 落，勿在 V53 重复加）。
ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type CHECK (type IN (
    'VET_REPLY', 'CONSULT_CLOSED', 'CONTENT_LIKED', 'CONTENT_COMMENTED', 'NEW_CONSULT_REQUEST',
    'PET_BIRTHDAY', 'COMPANION_ANNIVERSARY', 'MILESTONE_NODE', 'CONTENT_REMOVED', 'REPORT_REVIEWED',
    'CONTENT_REVIEW_APPROVED', 'CONTENT_REVIEW_REJECTED', 'NAME_RESET', 'AVATAR_RESET'));
