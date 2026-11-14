-- V1.5.0 onboarding-recall Story 5.1（FR-180 / FR-181 · 架构 delta §3.2 ⑤ · OR-13 ~ OR-15）：关注更新推送三表。
-- 无 FK、无 JPA 实体（JdbcTemplate 读写，同 pet_diary_notify_log）；PetDiaryNotifyLogCleanupJob 一并清理
-- wib_date < 今天(WIB) − 30 天 的行。push_outcome 不加 CHECK（取值只在 Java 常量收口）。
-- 幂等可重放（IF NOT EXISTS）。🔴 不改 ck_notifications_type（地基已含 USER_POST_PUBLISHED）。

-- 每条 USER_POST_PUBLISHED 通知一行：发出数 / 推送数 / 未推原因（PRD §4 取数）
CREATE TABLE IF NOT EXISTS user_post_notify_log (
    follower_id  BIGINT      NOT NULL,
    post_id      BIGINT      NOT NULL,
    author_id    BIGINT      NOT NULL,
    wib_date     DATE        NOT NULL,
    push_outcome VARCHAR(16) NOT NULL,   -- PUSHED / AUTHOR_DUP / CAP_REACHED / MUTED / OLD_APP / NO_PUSH_ACCT / FAILED
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_post_notify_log PRIMARY KEY (follower_id, post_id)
);
CREATE INDEX IF NOT EXISTS idx_user_post_notify_log_date ON user_post_notify_log (wib_date);

-- 同收件人 × 同作者 × WIB 日最多推 1 条（ON CONFLICT DO NOTHING 抢位）
CREATE TABLE IF NOT EXISTS user_post_push_daily (
    follower_id BIGINT NOT NULL,
    author_id   BIGINT NOT NULL,
    wib_date    DATE   NOT NULL,
    CONSTRAINT pk_user_post_push_daily PRIMARY KEY (follower_id, author_id, wib_date)
);
CREATE INDEX IF NOT EXISTS idx_user_post_push_daily_date ON user_post_push_daily (wib_date);

-- 关注 + 追更两类合计每人每 WIB 日推送名额（原子 UPSERT 计数）
CREATE TABLE IF NOT EXISTS follow_update_push_quota (
    user_id  BIGINT NOT NULL,
    wib_date DATE   NOT NULL,
    used     INT    NOT NULL DEFAULT 0,
    CONSTRAINT pk_follow_update_push_quota PRIMARY KEY (user_id, wib_date)
);
CREATE INDEX IF NOT EXISTS idx_follow_update_push_quota_date ON follow_update_push_quota (wib_date);
