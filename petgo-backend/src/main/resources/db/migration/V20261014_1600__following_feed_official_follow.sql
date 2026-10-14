-- V1.3.4 following 流 · 官方号自动关注 —— 来源列 + 官方号取关表 + 存量回填。
--
-- · user_follows.source：USER=用户主动关注 / SYSTEM=系统自动关注官方号（ADMIN 角色账号）。
--   每日新增关注上限只数 USER 来源（UserFollowRepository.countCreatedSince）。
-- · official_follow_optouts：用户主动取关官方号的记录，自动关注（含本迁移回填）不得再把这对关回去。
-- · 回填：每个活跃 USER 关注每个活跃 ADMIN（source=SYSTEM），排除已取关对与 holder 拉黑（BLOCK）的对；
--   随后按表整体重算 following_count（全部 USER）/ follower_count（全部 ADMIN），不做 ±1。
-- 可重跑：ON CONFLICT DO NOTHING + 重算计数（幂等）。
-- source 用 VARCHAR(16)（非 CHAR(1)，避开 Hibernate CHAR(1) 坑）。

ALTER TABLE user_follows
    ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'USER';

ALTER TABLE user_follows
    ADD CONSTRAINT ck_user_follows_source CHECK (source IN ('USER', 'SYSTEM'));

COMMENT ON COLUMN user_follows.source IS
    '关注来源：USER=用户主动 / SYSTEM=系统自动关注官方号；每日新增上限只数 USER。';

CREATE TABLE official_follow_optouts (
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    official_id BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, official_id)
);

COMMENT ON TABLE official_follow_optouts IS
    '用户主动取关官方号的记录（V1.3.4）；自动关注与存量回填均须跳过这些对。';

-- 存量回填。
INSERT INTO user_follows (follower_id, followee_id, created_at, source)
SELECT u.id, o.id, now(), 'SYSTEM'
  FROM users u
  CROSS JOIN users o
 WHERE u.role = 'USER' AND u.deleted_at IS NULL AND u.status = 'ACTIVE'
   AND o.role = 'ADMIN' AND o.deleted_at IS NULL AND o.status = 'ACTIVE'
   AND u.id <> o.id
   AND NOT EXISTS (SELECT 1 FROM official_follow_optouts x
                    WHERE x.user_id = u.id AND x.official_id = o.id)
   AND NOT EXISTS (SELECT 1 FROM user_hide_relations h
                    WHERE h.holder_id = u.id AND h.target_id = o.id AND h.source = 'BLOCK')
ON CONFLICT (follower_id, followee_id) DO NOTHING;

-- 按表重算计数（不做 ±1）。
UPDATE users u
   SET following_count = (SELECT count(*) FROM user_follows f WHERE f.follower_id = u.id)
 WHERE u.role = 'USER';

UPDATE users u
   SET follower_count = (SELECT count(*) FROM user_follows f WHERE f.followee_id = u.id)
 WHERE u.role = 'ADMIN';
