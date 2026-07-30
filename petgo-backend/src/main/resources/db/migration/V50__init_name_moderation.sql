-- 内容审核补充规范 story 4（昵称/宠物名异步审核 + 违规重置默认编码名）。冻结基线 V46，本 story 增量 CREATE/ALTER。
-- 护栏：枚举 varchar + UPPER_SNAKE；时间戳 timestamptz UTC；无 length=1 列（避免 Hibernate CHAR(1) → validate 全红）。

-- ① 名称审核记录（名称侧自己的审核状态机 + 人工队列，不复用帖子 manual_review_queue，理由见 spec §5.1）。
--   target_ref_id 为内部外键值（昵称=users.id / 宠物名=pet_profiles.id），绝不外露；对外走各自 token。
--   submitted_value 为送审名称原文（审核证据，可能含 PII）→ 仅存本列，严禁写入业务日志（护栏 §5.6）。
CREATE TABLE name_moderation_records (
    id              BIGSERIAL    PRIMARY KEY,
    target_type     VARCHAR(16)  NOT NULL,                       -- NICKNAME / PET_NAME
    target_ref_id   BIGINT       NOT NULL,                       -- users.id 或 pet_profiles.id（内部值）
    revision        BIGINT       NOT NULL,                       -- 该 target 的审核版本号，每次新提交 +1（陈旧作废版本键）
    submitted_value TEXT         NOT NULL,                       -- 送审名称原文（审核证据；禁入日志）
    status          VARCHAR(24)  NOT NULL,                       -- 状态机见 spec §5.2
    priority        VARCHAR(8)   NOT NULL DEFAULT 'NORMAL',      -- NORMAL / HIGH（≥0.8）
    risk_score      NUMERIC(4,3),                                -- 三方评分 0.000–1.000；降级/未评分为 NULL
    decided_by      BIGINT,                                      -- 人工处置的 admin_accounts.id（自动过/降级为空）
    decided_at      TIMESTAMPTZ,
    decision_reason VARCHAR(64),                                 -- 违规类别枚举（仅运营记录，不外泄用户）
    retry_count     INTEGER      NOT NULL DEFAULT 0,             -- 异步调三方重试次数
    submitted_at    TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_nmr_target_type CHECK (target_type IN ('NICKNAME', 'PET_NAME')),
    CONSTRAINT ck_nmr_priority    CHECK (priority IN ('NORMAL', 'HIGH')),
    CONSTRAINT ck_nmr_status      CHECK (status IN (
        'SCORING', 'AUTO_PASSED', 'MANUAL_PENDING', 'RESOLVED_PASS',
        'RESOLVED_VIOLATION', 'SUPERSEDED', 'FAILED_TO_QUEUE'))
);

CREATE INDEX idx_nmr_target        ON name_moderation_records (target_type, target_ref_id);
CREATE INDEX idx_nmr_status        ON name_moderation_records (status);
-- 队列列表高频：仅人工待处置项（部分索引）。
CREATE INDEX idx_nmr_pending_queue ON name_moderation_records (submitted_at)
    WHERE status = 'MANUAL_PENDING';

-- ② 昵称/宠物名当前是否为违规重置生成的默认编码名（is_system_default_name）。
--   违规重置 ≠ 注销匿名化（D-CM4）：此列仅标记违规重置的 user_<hex>/Pet_<hex> 真实名，与 7.3「已注销用户」展示层无关。
ALTER TABLE users         ADD COLUMN is_system_default_name BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE pet_profiles  ADD COLUMN is_system_default_name BOOLEAN NOT NULL DEFAULT false;

-- ③ 通知类型扩展：新增 NAME_RESET（昵称/宠物名违规重置后推送，targetRef 区分昵称/宠物）。
--   CM3 决策：名称重置用单一 NAME_RESET 类型（非双型），targetRef 区分。cm-4 先于 cm-7 实现，故 NAME_RESET
--   由本迁移落；cm-7 的 V53 只加 AVATAR_RESET/CONTENT_REVIEW_TIMED_OUT，勿重复加 NAME_RESET。
ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type CHECK (type IN (
    'VET_REPLY', 'CONSULT_CLOSED', 'CONTENT_LIKED', 'CONTENT_COMMENTED', 'NEW_CONSULT_REQUEST',
    'PET_BIRTHDAY', 'COMPANION_ANNIVERSARY', 'MILESTONE_NODE', 'CONTENT_REMOVED', 'REPORT_REVIEWED',
    'CONTENT_REVIEW_APPROVED', 'CONTENT_REVIEW_REJECTED', 'NAME_RESET'));
