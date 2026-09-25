-- 用户每日活跃（飞书群日报「昨日日活」口径，2026-09-25）。
--
-- 为什么不用 users.last_active_at：它只存「最后一次」、且按 UTC 日至多刷一次 ——
--   ① 昨天来过、今天 09:00 前又来的人，时间戳已被覆盖成今天 → 昨日日活少算；
--   ② 日报按印尼自然日切日，UTC 刷新粒度对不上。
-- 这里每人每个 WIB 自然日一行，由 UserActivityFilter 在每个已认证 /api/v1 请求上
-- INSERT … ON CONFLICT DO NOTHING（同日重复请求只是一次主键查找，不产生写）。
--
-- ⚠️ 历史无法回填：上线当天之前没有逐日记录，日报对更早的日期显示「—」而不是编一个数。
-- 🔒 只存 user_id 与日期，无 PII；注销删号时随 users 级联删除。

CREATE TABLE user_active_days (
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    active_date DATE        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, active_date)
);

-- 日报按日期聚合（count by active_date）。
CREATE INDEX ix_user_active_days_date ON user_active_days (active_date);

COMMENT ON TABLE user_active_days IS
    '用户每日活跃：每人每个 WIB 自然日一行（UserActivityFilter 写入）。仅用于日报 DAU 口径。';
COMMENT ON COLUMN user_active_days.active_date IS '活跃日（Asia/Jakarta 自然日）';
