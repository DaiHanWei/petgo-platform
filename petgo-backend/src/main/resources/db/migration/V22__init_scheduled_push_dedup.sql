-- Story 6.7（PRD V1.0.0 新增，决策 F5）：定时类系统推送去重标记表。
-- @Scheduled 每日扫描 + @Async 逐条投递；本表唯一约束 = 去重单一事实源（禁 Redis/MQ 当去重源）。
-- node_key 语义：生日=年份(如 2026，按年去重)；纪念日=节点天数(30/100/365)；里程碑节点=节点 id(如 FIRST_BIRTHDAY)。
-- Flyway 序号按执行顺序顺延（决策 E2）。表归 notify 模块。时间戳 UTC。

CREATE TABLE scheduled_push_marks (
    id              BIGSERIAL    PRIMARY KEY,
    pet_profile_id  BIGINT       NOT NULL,
    push_kind       VARCHAR(32)  NOT NULL,   -- PET_BIRTHDAY|COMPANION_ANNIVERSARY|MILESTONE_NODE
    node_key        VARCHAR(32)  NOT NULL,   -- 生日=年份 / 纪念日=天数 / 里程碑=节点 id
    pushed_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),  -- UTC
    CONSTRAINT uq_scheduled_push_marks UNIQUE (pet_profile_id, push_kind, node_key)
);
