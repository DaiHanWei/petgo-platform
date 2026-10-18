-- V1.4.0 multi-pet Story 2.1（AD-4 · §6.4）：宠物状态 + 用户「保持正常」选择。
-- 不回填：存量全部默认 ACTIVE / false，等价于现状（单宠物账号零行为变化）。
ALTER TABLE pet_profiles ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE pet_profiles ADD CONSTRAINT ck_pet_profiles_status CHECK (status IN ('ACTIVE', 'READ_ONLY'));
ALTER TABLE pet_profiles ADD COLUMN keep_active BOOLEAN NOT NULL DEFAULT false;

-- /me/list、reconcile、findDefault 都按 (owner_id, status) 查。
CREATE INDEX IF NOT EXISTS ix_pet_profiles_owner_status ON pet_profiles (owner_id, status);

COMMENT ON COLUMN pet_profiles.status IS '宠物状态 ACTIVE / READ_ONLY；只经 PetSlotStateService.reconcile 改';
COMMENT ON COLUMN pet_profiles.keep_active IS '用户在名额管理页「Ubah pilihan」选为保持正常';
