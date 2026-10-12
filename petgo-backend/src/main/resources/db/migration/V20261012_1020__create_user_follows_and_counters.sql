-- V1.3.4 follow · Story 1.1 —— 用户相互关注（FR-140，架构 delta AD-1，决策 T-4）。
--
-- · user_follows：一行 = follower 关注了 followee。复合主键即幂等键（关注 = INSERT … ON CONFLICT DO NOTHING）。
--   两列 FK ON DELETE CASCADE 只为防将来真删 users 行；⚠️ 注销是就地匿名化、users 行不删，
--   CASCADE 永远不会触发 —— 注销清理由应用层 FollowService.purgeUser 显式完成（AD-1 / Story 1.1 AC7）。
-- · 两条列表索引按 T-4 建三列版本（keyset 分页 created_at DESC + 对端 id DESC 定序）；
--   follower 侧那条兼作「每日新增上限」计数（follower_id = ? AND created_at >= 当日 WIB 00:00）。
-- · users 两计数列是冗余计数：只由关注 / 取关 / 拉黑取关 / 注销清理在同一事务内条件 UPDATE ±1
--   （GREATEST(…, 0) 兜底），实体映射只读（insertable = false, updatable = false），禁止读出来 ±1 再 save。

CREATE TABLE user_follows (
    follower_id BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    followee_id BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (follower_id, followee_id),
    CONSTRAINT ck_user_follows_not_self CHECK (follower_id <> followee_id)
);

-- 粉丝列表：WHERE followee_id = ? ORDER BY created_at DESC, follower_id DESC（T-4）。
CREATE INDEX idx_user_follows_followee_created
    ON user_follows (followee_id, created_at DESC, follower_id DESC);
-- 关注列表 + 每日新增上限计数：WHERE follower_id = ? ORDER BY created_at DESC, followee_id DESC（T-4）。
CREATE INDEX idx_user_follows_follower_created
    ON user_follows (follower_id, created_at DESC, followee_id DESC);

COMMENT ON TABLE user_follows IS
    '用户关注关系（V1.3.4 FR-140，AD-1）。一行 = follower 关注 followee；复合主键即幂等键。'
    '注销是就地匿名化，FK CASCADE 不会触发，由 FollowService.purgeUser 显式清理并扣减对端计数。';
COMMENT ON COLUMN user_follows.created_at IS '关注时刻（UTC）；列表排序与每日新增上限（WIB 自然日）依据。';

ALTER TABLE users
    ADD COLUMN following_count INT NOT NULL DEFAULT 0,
    ADD COLUMN follower_count  INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN users.following_count IS
    '我关注的人数（冗余计数，AD-1）。只由关注 / 取关 / 拉黑取关 / 注销清理在同事务内条件 UPDATE ±1，GREATEST(…,0) 兜底；实体只读映射。';
COMMENT ON COLUMN users.follower_count IS
    '关注我的人数（冗余计数，AD-1）。只由关注 / 取关 / 拉黑取关 / 注销清理在同事务内条件 UPDATE ±1，GREATEST(…,0) 兜底；实体只读映射。';
