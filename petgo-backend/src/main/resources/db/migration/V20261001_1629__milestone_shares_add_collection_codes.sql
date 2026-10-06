-- V1.3.2 batch-a Story 5.2（AD-15 · C-10）：里程碑分享页 KOLEKSI 按 code 出专属徽章。
--
-- 旧分享只存了级别串 collection_levels（V30），没有 code，**不回填**：
--   collection_codes IS NULL → H5 按 collection_levels 旧样式渲染（旧链接保持原样，C-10）；
--   非空 → 按 code 出图（素材缺失的格回落旧圆点）。
-- 大徽章本来就有 milestone_shares.code，不依赖本列。
ALTER TABLE milestone_shares ADD COLUMN collection_codes VARCHAR(400) NULL;

COMMENT ON COLUMN milestone_shares.collection_codes IS
    '新分享才写：分享当时已解锁合集的完整 code 列表，逗号分隔、按合集顺序；为空 = 旧分享，H5 按 collection_levels 旧样式渲染（C-10）';
