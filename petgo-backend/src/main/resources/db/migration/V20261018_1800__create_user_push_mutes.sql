-- V1.4.0 ④ 推送开关与收藏 · Story 1.1 AC1（架构增量 AD-2）：推送分类开关的服务端存储。
--
-- 只存「关闭」的分类：无行 = 开启。分类枚举见 notify.preference.PushCategory。
--
-- 🔴 ck_user_push_mutes_category 是本线自有约束（不是地基两条共享约束 ck_notifications_type /
--    ck_payment_intents_purpose 之一）。它只列 8 个可关分类，ORDER_ACCOUNT（订单与账号）被排除 ——
--    库里不可能出现「订单与账号已关闭」。
-- ⚠️ 以后新增可关分类 = 新起一条时间戳迁移 DROP + ADD 重列全集（CLAUDE.md 第 5 条「改 CHECK 约束必须重列全集」），
--    永远不要只加一个值；UserPushMutesMigrationTest 会比对本 CHECK 与 PushCategory 中可关分类的集合。
--
-- ON DELETE CASCADE 只是兜底（测试库 deleteAll 不被阻断）：注销是就地匿名化、users 行不删，
-- CASCADE 在注销路径上永不触发 —— 注销清理走 PushPreferenceService.deleteByUserId 显式删除。
--
-- 无 JPA 实体（JdbcTemplate 读写），与 ddl-auto=validate 无关。

CREATE TABLE user_push_mutes (
    user_id     BIGINT       NOT NULL,
    category    VARCHAR(24)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_push_mutes PRIMARY KEY (user_id, category),
    CONSTRAINT fk_user_push_mutes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_push_mutes_category CHECK (category IN
        ('LIKE', 'COMMENT', 'MENTION', 'FOLLOWER', 'GIFT', 'PET_REMINDER', 'ACHIEVEMENT', 'PROMO'))
);

-- 运营公告按批预取「关闭了 PROMO 的人」（Story 1.2 mutedAmong）。
CREATE INDEX idx_user_push_mutes_category_user ON user_push_mutes (category, user_id);
