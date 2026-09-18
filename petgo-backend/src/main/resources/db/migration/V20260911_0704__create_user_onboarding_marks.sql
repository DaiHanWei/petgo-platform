-- V1.3.0 批次 A · Story 5.4（AD-A21）：用户**一次性引导标记**的键值表。
--
-- 🔴 **键值形态，不是每个引导一列**（AD-A21.1）。
--    每来一个一次性引导就往 users 表加一个 boolean 列，是这张表存在的唯一理由 ——
--    批次 C 的性格测试入口引导会用**第二个键**（AD-A21.3：禁止与本批次共用）。
--
-- ✅ **按账号存，不按设备**（Dai 2026-09-10 拍板）。
--    ⚠️ 这与 v1.1.6 AD-14.1「一次性引导标记走本地 prefs、按设备」**不同**，且 AD-14.8
--    的手机号软引导是一个**账号级关切却仍按设备存**的反例 —— 所以「账号级关切 ⇒ 按账号存」
--    这个推论在本项目并不自动成立。本条仍取按账号的实际理由：
--    AD-14.8 有服务端字段（users.phone 非空）天然兜底，标记存哪都不会重复打扰；
--    **本条没有任何服务端事实可兜底** —— 除了这个标记本身，没有别的东西能说明
--    「他已经知道 KTP 挪走了」。两条并存，各管各的场景，不得互相套用。
--
-- 🔴 **表里不含任何 PII**（AC1）：只有 user_id、一个内部键名、一个时刻。
--    键名是我们自己定义的常量（如 ktp_moved），不是用户输入，也不描述用户。
--
-- 注销级联（AC7，CLAUDE.md 安全攸关 D1）：纯个人数据，随注销**物理删除**，
-- 入口在 OnboardingMarkDeletionService。
CREATE TABLE user_onboarding_marks (
    id            BIGSERIAL   PRIMARY KEY,
    user_id       BIGINT      NOT NULL,
    -- 引导键（内部常量，一个引导一个键）。
    mark_key      VARCHAR(64) NOT NULL,
    -- 首次触发时刻（UTC）。看过即置位，之后不再弹。
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 🛡 一个用户 + 一个键 = 一行，永远。置位的幂等落在这条约束上，
    --    不靠「先查有没有再插」（那是典型的并发双写）。
    CONSTRAINT uq_user_onboarding_marks UNIQUE (user_id, mark_key),
    CONSTRAINT fk_user_onboarding_marks_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX ix_user_onboarding_marks_user ON user_onboarding_marks (user_id);

COMMENT ON TABLE user_onboarding_marks IS
    '用户一次性引导标记（键值形态，按账号）。无 PII：只有 user_id + 内部键名 + 时刻';
