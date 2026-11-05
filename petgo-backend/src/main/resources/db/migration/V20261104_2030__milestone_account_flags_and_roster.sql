-- V1.4.2 Story 2.1 · FR-154 · D-4 / D-12 / P-8 ⑤：账号级里程碑「第一次关注一个人」「第一次收藏内容」。
-- 预分配版本号 V20261104_2030（决策 E7 时间戳制；不得另取）。
--
-- 新增 code（MilestoneCatalog 末尾追加，既有 code / sort_order 一个不变；决策日志 B1：其他物种为 G-S12 / G-S13）：
--   CAT   C-S19(34) 第一次关注一个人 / C-S20(35) 第一次收藏内容
--   DOG   D-S19(34) 第一次关注一个人 / D-S20(35) 第一次收藏内容
--   OTHER G-S12(19) 第一次关注一个人 / G-S13(20) 第一次收藏内容
--
-- 🔴 ck_user_milestone_flags_key 是本表自有约束：以后新增 flag = 新起时间戳迁移 DROP + ADD 重列全集
--    （值取当前树里最后一条重建它的迁移，不凭记忆抄）。
-- 幂等：建表 IF NOT EXISTS；名册补行带 NOT EXISTS（uq_pet_milestones_pet_code 另有唯一约束兜底）。

-- ① 账号级里程碑 flag（账号内只触发一次，主键冲突即整条路径 no-op）。
CREATE TABLE IF NOT EXISTS user_milestone_flags (
    user_id     BIGINT       NOT NULL,
    flag_key    VARCHAR(32)  NOT NULL,
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now(),   -- UTC
    CONSTRAINT pk_user_milestone_flags PRIMARY KEY (user_id, flag_key),
    CONSTRAINT fk_user_milestone_flags_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_milestone_flags_key CHECK (flag_key IN ('FIRST_FOLLOW_USER', 'FIRST_COLLECT_CONTENT'))
);

-- ② 名册补行：只给「已有名册」的宠物补（照 V20261012_1105 ① 段；不是 V77「给所有宠物插」——
--    assignRoster 见到任一行就整段跳过，给无名册宠物只插两行会让它永远拿不到其余条目；
--    无名册宠物首次打开列表时会惰性生成完整名册）。按 (pet_type, code, sort_order) 显式列举，不按后缀推导。
INSERT INTO pet_milestones (pet_profile_id, code, level, trigger_type, sort_order, created_at)
SELECT p.id, v.code, 'S', 'SYSTEM_AUTO', v.sort_order, now()
FROM pet_profiles p
JOIN (VALUES ('CAT',   'C-S19', 34), ('CAT',   'C-S20', 35),
             ('DOG',   'D-S19', 34), ('DOG',   'D-S20', 35),
             ('OTHER', 'G-S12', 19), ('OTHER', 'G-S13', 20)) AS v(pet_type, code, sort_order)
  ON v.pet_type = p.pet_type
WHERE EXISTS (SELECT 1 FROM pet_milestones r WHERE r.pet_profile_id = p.id)
  AND NOT EXISTS (
      SELECT 1 FROM pet_milestones m WHERE m.pet_profile_id = p.id AND m.code = v.code);
