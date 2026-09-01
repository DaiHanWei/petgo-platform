-- 生命周期推送引擎（留存运营作战手册 · 抓手 1）。
-- 目标：把「泛泛的回来看看」换成按用户生命周期定时触发、且理由具体、带宠物名的推送，
-- 打 D1 留存 9.6% → 20%。四个节点：D1 次日 / D3 / D7 周回顾 / 7 天未回召回。
--
-- 🔴 复用 Story 6.7 既有推送地基（@Scheduled 日扫 + @Async 逐条 + 去重表唯一约束），
--    不引入 MQ / 调度中间件 / 通用缓存层（架构 §Enforcement）。
-- 🔴 去重键落在 user 维度（Story 6.7 的 scheduled_push_marks 是 pet_profile 维度，
--    覆盖不了「注册了但没建档」这一层 —— 而那一层恰好是 557 人 / 85.8% 的大头）。

-- ---------- ① 最后活跃时间（流失判定的唯一依据） ----------
-- users 此前没有任何「最后活跃」信号：兽医侧有 Redis ZSET presence，用户侧没有对应物。
-- 没有它，「7 天未回」无从判定，召回推送就只能靠注册天数瞎猜。
ALTER TABLE users ADD COLUMN last_active_at TIMESTAMPTZ;

COMMENT ON COLUMN users.last_active_at IS
    '最后活跃时刻（UTC）。任意已认证 /api/v1 请求每日至多刷新一次（UserActivityFilter）。'
    '仅用于生命周期推送的流失判定与服务端 DAU 口径，不对外暴露、不进 Feed 投影。';

-- 回填：既有用户按 updated_at 兜底（比 created_at 更接近真实活跃）。
-- ⚠️ 回填后大量老用户会立刻满足「7 天未回」→ 首次日扫可能一次性命中数百人。
--    投递上限（petgo.lifecycle-push.daily-cap）与总开关（enabled，默认关）是这里的安全阀，
--    运营按手册节奏手动开、按天放量，不允许一次性轰炸。
UPDATE users SET last_active_at = updated_at WHERE last_active_at IS NULL;

-- 日扫按 last_active_at 过滤流失用户。
CREATE INDEX ix_users_last_active_at ON users (last_active_at);

-- ---------- ② 生命周期推送去重标记 ----------
-- 唯一约束 (user_id, push_kind, node_key) 是「该节点仅推一次」的单一事实源
-- —— 禁用 Redis/MQ 当去重源（并发/重扫由 DB 约束兜底，at-most-once：
--    宁可漏推一条也绝不重复打扰，重复打扰换来的是卸载）。
CREATE TABLE lifecycle_push_marks (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    push_kind  VARCHAR(32) NOT NULL,             -- NotificationType 名（LIFECYCLE_D1 等）
    node_key   VARCHAR(32) NOT NULL,             -- D1/D3/D7 固定 'ONCE'；召回为 'yyyy-MM'（每月至多一次）
    variant    VARCHAR(32) NOT NULL,             -- 命中分层（CREATE_PROFILE/RECORD/FEED/REVIEW），供运营复盘
    pushed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_lifecycle_push_marks UNIQUE (user_id, push_kind, node_key)
);

COMMENT ON TABLE lifecycle_push_marks IS
    '生命周期推送去重标记（留存手册抓手 1）。唯一约束 (user_id, push_kind, node_key) '
    '= 「该节点仅推一次」的单一事实源。variant 记录命中分层，供「召回漏斗」周复盘取数。';

-- 按天取「昨日各节点推了多少条」——手册每日 SOP「看召回漏斗」的取数入口。
CREATE INDEX ix_lifecycle_push_marks_pushed_at ON lifecycle_push_marks (pushed_at, push_kind);

-- ---------- ③ 站内推送类型 ----------
-- 🔴 沿用 V97 的处置：重列全集，不在旧列表上想当然地追加（漏抄会让既有类型发不出去）。
ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type CHECK (type IN (
    'VET_REPLY', 'CONSULT_CLOSED', 'CONTENT_LIKED', 'CONTENT_COMMENTED', 'NEW_CONSULT_REQUEST',
    'PET_BIRTHDAY', 'COMPANION_ANNIVERSARY', 'MILESTONE_NODE', 'CONTENT_REMOVED', 'REPORT_REVIEWED',
    'CONTENT_REVIEW_APPROVED', 'CONTENT_REVIEW_REJECTED',
    'NAME_RESET', 'AVATAR_RESET', 'CONTENT_REVIEW_TIMED_OUT',
    'REFUND_REJECTED', 'TICKET_RESOLVED', 'CSAT_SURVEY', 'IDENTITY_REQUIRE_MODIFY',
    'ACCOUNT_WARNED', 'ACCOUNT_SUSPENDED',
    'SHOP_ORDER_SHIPPED', 'SHOP_ORDER_EXCEPTION', 'SHOP_RETURN_UPDATED',
    'REPURCHASE_FOOD_LOW',
    -- V1.1.6 Story 6.1（FR-76，hex 线 V20260819_0156）：S/M 级里程碑达成，写通知中心不发推送。
    -- 🔴 本条**必须**在这里：hex 线那份清单写在 0819，本迁移写在 0821、跑在它后面，
    --    重列全集时漏抄它 = 把它悄悄踢掉。petgo_stag 一旦已有该类行，重建约束即 23514
    --    「is violated by some row」→ flywayInitializer 建 bean 失败 → **启动崩**。
    --    同类事故已发生过两次（V97 修 V72 丢 3 值；ae51a8a5 修电商线丢 ACCOUNT_* 两值）。
    'MILESTONE_SM_NODE',
    -- 留存手册抓手 1：生命周期四节点。深链目标由 target_ref 携带的 variant 决定
    -- （同 NAME_RESET/AVATAR_RESET 范式：单一类型 + variant 分流，不为每个落点新增一个枚举）。
    'LIFECYCLE_D1', 'LIFECYCLE_D3', 'LIFECYCLE_D7', 'LIFECYCLE_WINBACK'));
