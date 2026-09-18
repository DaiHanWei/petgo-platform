-- 工作线：V1.3.0 场所表对齐（feat/1.3.0-places-align）。
-- 规格：_bmad-output/implementation-artifacts/specs/spec-v130-places-schema-alignment.md
--
-- 背景：后台（ops，V20260909_1749__init_places.sql）与 App（batch-b1）各建过一套场所表。
-- CROSS-STORY-DECISIONS 定的是「后台定 schema，App 只读写」—— b1 那 5 个建表迁移已删除
-- （从未在任何环境执行过，2026-09-18 核实），App 需要而后台表没有的列在这里 ALTER 追加。
--
-- 决策（2026-09-18 拍板）：
--   D1 place_type 取 App 的 7 值（后台迁移注释原话：「值域由 App 端 FR-112 定」）
--   D2 city 保持必填；App 标记时由服务端 PlaceCityResolver 填默认城市（本版 Jakarta）
--   D3 删掉 5 个计数缓存列，后台改实时统计（App 的 Redis 态度计数不受影响）
--   D5 照片仍存 object_key（App 侧改为解析 URL → key）

-- ── places ──────────────────────────────────────────────────────────────────
-- D3：计数改实时统计。缓存列只有后台写路径维护，App 的评论 / 照片写入不会动它们，留着只会越偏越远。
ALTER TABLE places DROP CONSTRAINT IF EXISTS ck_places_counts;
ALTER TABLE places
    DROP COLUMN photo_count,
    DROP COLUMN comment_count,
    DROP COLUMN checkin_count,
    DROP COLUMN recommend_count,
    DROP COLUMN not_recommend_count;

-- D1：类型值域由 App 定，现在全集已知，补上 CHECK（后台 5-1 story 的遗留待办）。
ALTER TABLE places ADD CONSTRAINT ck_places_type CHECK (place_type IN (
    'CAFE', 'RESTAURANT', 'PARK', 'MALL', 'HOTEL', 'PET_SERVICE', 'OTHER'));

-- App 列表「按最新」的 keyset 分页（created_at DESC, id DESC）。
CREATE INDEX ix_places_active_recent
    ON places (created_at DESC, id DESC)
    WHERE status = 'ACTIVE' AND deleted_at IS NULL;
-- App「按距离」：经纬度范围粗筛（AD-2：两列各一条索引，不装地理扩展）。
CREATE INDEX ix_places_active_lat ON places (lat) WHERE status = 'ACTIVE' AND deleted_at IS NULL;
CREATE INDEX ix_places_active_lng ON places (lng) WHERE status = 'ACTIVE' AND deleted_at IS NULL;

-- ── place_photos ────────────────────────────────────────────────────────────
-- App 的补图是先发后审（Story 1.9）；标记时那批与运营录入的算首批图（is_original）；
-- og_eligible 只给干净 PASS 与运营录入（站外预览卡撤不回来）。
ALTER TABLE place_photos
    ADD COLUMN moderation_status VARCHAR(24) NOT NULL DEFAULT 'VISIBLE',
    ADD COLUMN sort_order        INT         NOT NULL DEFAULT 0,
    ADD COLUMN is_original       BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN og_eligible       BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN updated_at        TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX ix_place_photos_place_order
    ON place_photos (place_id, sort_order, id)
    WHERE deleted_at IS NULL;
CREATE INDEX ix_place_photos_uploader ON place_photos (uploader_user_id);

-- ── place_comments ──────────────────────────────────────────────────────────
-- App 的态度是可选的（只发文字、不表态）。
ALTER TABLE place_comments ALTER COLUMN attitude DROP NOT NULL;
ALTER TABLE place_comments DROP CONSTRAINT ck_place_comments_attitude;
ALTER TABLE place_comments ADD CONSTRAINT ck_place_comments_attitude
    CHECK (attitude IS NULL OR attitude IN ('RECOMMEND', 'NOT_RECOMMEND'));

ALTER TABLE place_comments
    ADD COLUMN moderation_status VARCHAR(24) NOT NULL DEFAULT 'VISIBLE',
    ADD COLUMN content_version   INT         NOT NULL DEFAULT 1,
    ADD COLUMN updated_at        TIMESTAMPTZ NOT NULL DEFAULT now();

-- App 评论区 keyset 分页 + 态度计数回算 + 注销级联按作者找。
CREATE INDEX ix_place_comments_place_created
    ON place_comments (place_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX ix_place_comments_place_attitude
    ON place_comments (place_id, attitude)
    WHERE deleted_at IS NULL;
CREATE INDEX ix_place_comments_author ON place_comments (author_user_id);
