-- V1.5.0 pet-species Story 1.2（架构 D-4）：里程碑 roster 退役列。
-- 大类改变时旧清单的行置 retired_at（不删：完成记录、徽章、分享页、时间线、已发成长值全保留）；来回改时复活。
-- 读路径只读活跃行（retired_at IS NULL）；删档照旧删全部行。

ALTER TABLE pet_milestones ADD COLUMN retired_at TIMESTAMPTZ;
CREATE INDEX idx_pet_milestones_active ON pet_milestones (pet_profile_id) WHERE retired_at IS NULL;
COMMENT ON COLUMN pet_milestones.retired_at IS '大类改变后旧清单的行（V1.5.0 FR-207）；非空=不在当前清单，完成记录保留';
