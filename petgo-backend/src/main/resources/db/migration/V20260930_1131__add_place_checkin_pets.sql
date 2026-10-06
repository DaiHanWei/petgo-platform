-- V1.3.2 batch-a · Story 1.1 —— 场所打卡：扩 place_checkins + 新建 place_checkin_pets（架构 delta AD-4 / AD-5）。
--
-- · place_checkins 加 public_token（对外标识）/ origin_place_id（打卡当时的场所，永不改）/ visit_date（WIB 自然日）。
--   存量行先回填再 SET NOT NULL（空壳表，prod 预期 0 行；stag 可能有后台测试数据）。
-- · 唯一约束放在 place_checkin_pets 且键用 origin_place_id：admin 合并场所只改 place_checkins.place_id，
--   键若用 place_id 合并当天会撞约束让合并失败；origin_place_id 永不改 → 合并永不冲突。
--   「今日已打卡」业务判定另按当前 place_id 查（服务层），本约束只做并发兜底。
-- · 坐标不落库（AD-4）：两张表都没有经纬度列。

ALTER TABLE place_checkins
    ADD COLUMN public_token    VARCHAR(32),
    ADD COLUMN origin_place_id BIGINT REFERENCES places (id),
    ADD COLUMN visit_date      DATE;

-- 存量回填：token 用 md5 截 32 位（唯一且不可枚举即可；新行由应用层 SecureRandom base62 生成）。
UPDATE place_checkins
   SET origin_place_id = place_id,
       visit_date      = (checked_at AT TIME ZONE 'Asia/Jakarta')::date,
       public_token    = substr(md5(random()::text || id::text || clock_timestamp()::text), 1, 32)
 WHERE public_token IS NULL;

ALTER TABLE place_checkins
    ALTER COLUMN public_token    SET NOT NULL,
    ALTER COLUMN origin_place_id SET NOT NULL,
    ALTER COLUMN visit_date      SET NOT NULL,
    ADD CONSTRAINT uq_place_checkins_public_token UNIQUE (public_token);

COMMENT ON TABLE place_checkins IS
    '场所打卡记录（V1.3.2 FR-112 §8）。合并场所时由 admin 合并事务内 reassignPlace 改 place_id 归并；'
    '「章」= 某宠物在某场所（当前 place_id）全部打卡的聚合，不另建章表（AD-4 / AD-5）。坐标不落库。';
COMMENT ON COLUMN place_checkins.public_token IS '对外不可枚举标识（32 位 base62）；自增 id 不外露。';
COMMENT ON COLUMN place_checkins.origin_place_id IS '打卡当时的场所 id，永不改（合并只改 place_id）；供 place_checkin_pets 唯一约束。';
COMMENT ON COLUMN place_checkins.visit_date IS '打卡的雅加达（WIB, UTC+7）自然日，只用于「每天限一次」；时间线排序仍用 checked_at。';

CREATE TABLE place_checkin_pets (
    checkin_id      BIGINT NOT NULL REFERENCES place_checkins (id) ON DELETE CASCADE,
    pet_profile_id  BIGINT NOT NULL REFERENCES pet_profiles (id),
    origin_place_id BIGINT NOT NULL,
    visit_date      DATE   NOT NULL,
    PRIMARY KEY (checkin_id, pet_profile_id),
    CONSTRAINT uq_place_checkin_pets_pet_place_day UNIQUE (pet_profile_id, origin_place_id, visit_date)
);
CREATE INDEX idx_place_checkin_pets_pet ON place_checkin_pets (pet_profile_id);

COMMENT ON TABLE place_checkin_pets IS
    '打卡 ↔ 宠物（多对多，界面按单宠）。origin_place_id / visit_date 冗余自 place_checkins，只为 UNIQUE(宠物, 原场所, WIB 日) 并发兜底。'
    '删档时由 PlaceCheckinDeletionService 先删本表行、再删已无关联宠物的 place_checkins（AD-17）。';
