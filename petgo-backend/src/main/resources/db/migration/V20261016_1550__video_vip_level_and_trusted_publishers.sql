-- 专题线 video · Story 1.1 —— 会员等级与视频免审名单（PRD-video FR-1 / FR-2 / FR-22，架构 AD-2）。
--
-- · users.vip_level：会员等级，默认 1；≥ 10 才可发视频（FR-4）。只能由运营在后台设置（决策 V-34/V-35 上下文）。
--   实体只读映射（insertable = false, updatable = false）：User 无 @DynamicUpdate，整行 save 的路径会把旧值写回，
--   故只由 VipLevelService 的 native UPDATE 修改。INTEGER 而非 SMALLINT（Hibernate int → int4，validate 才能过）。
-- · video_trusted_publishers：视频免审名单（决策 V-25）。免审只认本表，与会员等级相互独立、
--   与 1.3.4 的 role = ADMIN 官方作者 shim 无关。created_by_admin_id 只记 id、不设外键（同审计日志口径）。
--   FK ON DELETE CASCADE 仅防将来真删 users 行（注销是就地匿名化，不会触发）。

ALTER TABLE users
    ADD COLUMN vip_level INTEGER NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_users_vip_level CHECK (vip_level BETWEEN 1 AND 99);

COMMENT ON COLUMN users.vip_level IS
    '会员等级（专题线 video FR-1），默认 1，取值 1–99；≥ 10 可发视频。只由后台 VipLevelService 的 native UPDATE 修改，实体只读映射。';

CREATE TABLE video_trusted_publishers (
    user_id             BIGINT      PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    created_by_admin_id BIGINT      NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_video_trusted_publishers_created ON video_trusted_publishers (created_at DESC, user_id DESC);

COMMENT ON TABLE video_trusted_publishers IS
    '视频免审名单（专题线 video FR-22，决策 V-25）。名单内账号发视频帖时视频、封面、正文免审；名单外但等级 ≥ 10 的账号强制人工审核。由后台 video.trusted.manage 权限维护，改动写审计。';
COMMENT ON COLUMN video_trusted_publishers.created_by_admin_id IS '加入名单的后台账号 id（admin_accounts.id，不设外键）。';
