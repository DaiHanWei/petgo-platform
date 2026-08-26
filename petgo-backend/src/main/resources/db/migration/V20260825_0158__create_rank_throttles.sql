-- V1.1.6 Story 17.1：限流（降权）记录表。时间戳版本号（决策 E6）。
--
-- 一行 = 对某条内容或某个账号执行过的一次限流。
--
-- 🛡 这是**降权**不是下架（AC2）：本表不碰 content_posts 的任何列 ——
--    不改 status、不改 visibility、不写 deleted_at。被限流的内容仍是 PUBLISHED，
--    直链 / 作者主页 / 话题聚合页照常可访问，只是在推荐序打分里乘一个 <1 的系数。
--    🔴 审核相关的既有代码路径都是「改状态」，顺手复用就会把降权做成下架。
--
-- 🛡 对用户不可见、不通知（AC3）：本表没有任何字段供用户侧读取，
--    也不与 notifications 产生关联。告知会引导删帖重发这类对抗行为。
--
-- ⚠️ 「到期自动解除」不靠定时任务，而是**由查询条件构成**：
--    生效 = lifted_at IS NULL AND (expires_at IS NULL OR expires_at > now)。
--    这样 AC4 的「解除后立即回 1.0、不残留」是结构上成立的；
--    换成扫描器反而会留下「已到期但还没被扫到」这段残留窗口，正是那条 AC 要防的。
CREATE TABLE rank_throttles (
    id          BIGSERIAL    PRIMARY KEY,

    -- POST = 只作用于这一条内容；ACCOUNT = 作用于该账号全部已发布内容。
    -- 🔴 ACCOUNT 的覆盖面按 target_id 在**打分时**展开，所以限流期内新发的内容
    --    自动同样受限（AC1）—— 不需要任何回填，也没有「重新发一遍就绕过」的口子。
    scope       VARCHAR(16)  NOT NULL,
    -- scope=POST 时是 content_posts.id；scope=ACCOUNT 时是 users.id。
    -- 刻意不加外键：两种含义的列加不了单一外键，而加了反而会让「内容被删除」
    -- 连带删掉治理留痕。
    target_id   BIGINT       NOT NULL,

    duration    VARCHAR(16)  NOT NULL,
    -- 永久限流为 NULL。与 duration 的一致性由下面的 CHECK 硬保证。
    expires_at  TIMESTAMPTZ,
    -- 手动提前解除的时刻（AC4）。非空即已解除，与是否到期无关。
    lifted_at   TIMESTAMPTZ,
    lifted_by   BIGINT,

    -- 都可空：限流不一定由某条工单触发（也可能是运营主动巡查）。
    operator_id BIGINT,
    report_id   BIGINT,
    reason      VARCHAR(500),

    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_rank_throttle_scope    CHECK (scope IN ('POST', 'ACCOUNT')),
    CONSTRAINT ck_rank_throttle_duration CHECK (duration IN ('DAYS_7', 'DAYS_30', 'PERMANENT')),
    -- 🛡 永久 ⇔ 无到期时刻。写反了（永久却带到期时刻 / 限期却没有）会让系数
    --    在某个时点悄悄变化，而没有任何报错。
    CONSTRAINT ck_rank_throttle_permanent
        CHECK ((duration = 'PERMANENT') = (expires_at IS NULL)),
    CONSTRAINT ck_rank_throttle_lifted
        CHECK ((lifted_at IS NULL) = (lifted_by IS NULL))
);

-- 打分链路的取数形状：按 (scope, target_id) 批量 in 查询，再在应用层过滤生效条件。
-- ⚠️ 生效条件含 now()，不是 immutable，进不了部分索引 —— 但本表是治理动作，
--    量级在几十到几百行，覆盖索引足够。
CREATE INDEX ix_rank_throttles_scope_target ON rank_throttles (scope, target_id);
