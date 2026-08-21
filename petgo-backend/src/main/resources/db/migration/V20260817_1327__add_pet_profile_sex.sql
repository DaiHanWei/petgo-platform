-- V1.1.6 Story 1.1：宠物性别落地。修的是一个已知缺陷 —— 编辑页的性别选择器 2026 年就画好了、
-- 能选能显示，唯独选完不提交不入库（pet_profile_edit_page.dart 有注释明写「仅前端占位」）。
--
-- 可空：NULL = 未填。⚠️ 存量宠物一律 NULL，**不做任何回填** —— 性别是用户自己填的事实，
-- 猜一个填进去比留空更糟。前端对 NULL 已有「请选择」占位态，展示零破损。
--
-- ⚠️ 列长取 16 而非更短：既有迁移（V101 user_hide_relations.source）已记录过
-- Hibernate 对短 varchar 推断成 CHAR(1) 的坑，此处沿用同一下限。
-- 枚举落库 varchar + UPPER_SNAKE（MALE / FEMALE），不用 ordinal（CLAUDE.md 命名映射链）。
--
-- ⚠️ 与身份证的性别是**两个独立字段，永不联动**：id_cards.gender 是建卡时冻结的快照，
-- 且**参与身份码生成**（CardNumberService.allocateCardNo → TT+DDMMYY+SP+XXXX，性别编在号里）。
-- 改档案性别若联动，已发出的身份码会与卡面对不上、甚至撞唯一约束。两者取值域也不同
-- （身份证三值含 UNKNOWN，本列两值 + NULL）。
--
-- 🔴 号说明（2026-08-21 E6 改号）：原号 V105，按决策 E6 改为时间戳号（取创建时刻）。
-- ⚠️ 旧号 V105 已 applied 在 petgo_stag（2026-08-18 部署），改号后 stag 会以新号重跑本文件——
-- 因此下方 DDL 全部幂等（IF NOT EXISTS / 吞 duplicate_object），重跑 no-op；旧号行靠 ignore-missing 容忍。
ALTER TABLE pet_profiles ADD COLUMN IF NOT EXISTS sex VARCHAR(16);

DO $$
BEGIN
    ALTER TABLE pet_profiles
        ADD CONSTRAINT ck_pet_profiles_sex CHECK (sex IS NULL OR sex IN ('MALE', 'FEMALE'));
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
