-- V1.4.0 multi-pet Story 1.4：解除「一账号一宠物」。
-- 前提（AD-1 / V-2）：全部按账号取唯一宠物的读口已在 Story 1.1 ~ 1.3 改完，PetOwnerLookupGuardTest 绿。
-- 唯一约束顺带提供的 owner_id 索引随之消失，补一个 (owner_id, created_at, id) 普通索引，
-- 覆盖 findAllByOwnerIdOrderByCreatedAtAscIdAsc / countByOwnerId / 默认宠物查询。
ALTER TABLE pet_profiles DROP CONSTRAINT IF EXISTS uq_pet_profiles_owner_id;
CREATE INDEX IF NOT EXISTS idx_pet_profiles_owner_created ON pet_profiles (owner_id, created_at, id);
