-- 2026-08-19 决策（KOL KTP 卡快照头像丢失事故后拍板，见 CROSS-STORY-DECISIONS F21）：
-- ① OSS 对象任何情况不再物理删除（代码层 MediaDeletionService 改保留）——id_cards 快照长期引用
--    档案头像对象，物理删把快照打成死链且不可恢复（桶未开版本控制）。
-- ② 档案删除时卡按付费态分流：付费卡（hd_unlocked）恒可见（展示快照）；未付费卡打标隐藏，
--    若后续支付回调到账翻转 hd_unlocked → 自动重新可见（防支付时间差）。
--
-- profile_deleted_at：非空 = 建卡所属档案已被删除。可见性 = hd_unlocked OR 本列为空。
ALTER TABLE id_cards ADD COLUMN profile_deleted_at TIMESTAMPTZ;

-- 存量回填：卡建立时点早于用户现存档案（= 档案删过重建）、或用户已无档案（含从未建档就独立建卡的
-- 罕见边角，按「档案不存在」同义处理）→ 视为档案已删打标。付费卡照打标（可见性规则保其恒可见）。
UPDATE id_cards c
SET profile_deleted_at = now()
WHERE NOT EXISTS (
    SELECT 1 FROM pet_profiles p
    WHERE p.owner_id = c.user_id AND p.created_at <= c.created_at
);
