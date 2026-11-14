-- V1.5.0 健康记录提醒 Story 2.2（架构增量 health-reminder §0 Z-8、§1.4，PRD FR-161 Check-up / FR-164）：
-- 兽医问诊「到访」记录——体检到期的 base date 来源之一（另一来源是 VET_VISIT 健康记录）。AI 分诊不写。
--
-- 为什么另建表：会话表 consult_sessions 不存宠物，宠物在 consult_orders.pet_profile_id；health_events 的 VET_CONSULT
-- 行只在用户选择存档时才写，不能当「所有已结束问诊」用。运行时由 ConsultClosedEvent 监听写入（一次会话一行，幂等）。
--
-- 回填列名核对（2026-11-14）：consult_orders.consult_session_id（V88）、consult_orders.pet_profile_id（V66）；
-- 结束时刻 = ConsultSession.terminalAt() 对 CLOSED 会话的口径 COALESCE(pending_close_started_at, updated_at)（V17）。
-- JOIN pet_profiles 过滤指向已删宠物的旧订单，避免 FK 失败。visit_date 为 WIB 日历日。
-- 🔴 不碰 ck_notifications_type。

CREATE TABLE health_vet_consult_visits (
    session_id     BIGINT PRIMARY KEY,           -- consult_sessions.id（一次会话一行，幂等）
    pet_profile_id BIGINT NOT NULL REFERENCES pet_profiles (id) ON DELETE CASCADE,
    visit_date     DATE   NOT NULL,              -- WIB
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_health_vet_consult_visits_pet ON health_vet_consult_visits (pet_profile_id, visit_date DESC);

-- 一次性回填：已结束（CLOSED）的兽医会话，宠物取该会话最新一张问诊单上的 pet_profile_id
INSERT INTO health_vet_consult_visits (session_id, pet_profile_id, visit_date)
SELECT s.id, o.pet_profile_id, ((COALESCE(s.pending_close_started_at, s.updated_at)) AT TIME ZONE 'Asia/Jakarta')::date
FROM consult_sessions s
JOIN LATERAL (SELECT co.pet_profile_id FROM consult_orders co
              JOIN pet_profiles p ON p.id = co.pet_profile_id
              WHERE co.consult_session_id = s.id
              ORDER BY co.id DESC LIMIT 1) o ON TRUE
WHERE s.status = 'CLOSED'
ON CONFLICT (session_id) DO NOTHING;
