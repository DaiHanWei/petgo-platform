-- V1.3.0 批次 A · Story 1.4（AD-A1）：里程碑「庆祝过没有」的记账列 + 存量回填。
--
-- ⚠️ **订正 PRD §3.2**：PRD 原文写「后端 `pet_milestones` 新增 `celebrated_at`」，AD-A1.1 已订正。
--    `pet_milestones` 是**每宠物的目录实例**（建档即全量铺行，含大量未完成项），把庆祝时刻放那里
--    等于给每一行未完成的里程碑都挂一个恒空的列；而且「完成」与「已庆祝」是同生命周期的两个事实，
--    拆到两张表会让每次判定都要 join。完成态在 `milestone_completions`（与 pet_milestones 1:1，
--    受 uq_milestone_completions_milestone 约束），**列加在这里**。
--
-- 语义：可空 TIMESTAMPTZ（UTC）。NULL = 已完成但从未展示过庆祝；非空 = 展示过庆祝的那一刻。
--       「已完成且未庆祝」= 本表存在行 AND celebrated_at IS NULL。**这是全链路唯一判据**（AD-A1.3）。
--
-- 🔴 **回填必须与建列同在这一支迁移里，不可拆两次上线**（AD-A1.4）：
--    列刚建出来时全表都是 NULL，也就是「全体老用户的每一条历史成就都算未庆祝」。
--    只要中间存在哪怕一次发布，那段时间里进里程碑列表页的老用户就会被补弹一堆陈年旧成就 ——
--    而庆祝是不可撤回的（弹过就弹过了）。所以两条语句同生共死。
--
-- ⚠️ 通用宠物被错点亮的那 3 条脏完成行（AD-A4 的历史遗留，决策 A-9 明确**不清理**）
--    会被本次回填一并标记为「已庆祝」。**这是期望结果** —— 它们本就不该被补弹。

ALTER TABLE milestone_completions
    ADD COLUMN celebrated_at TIMESTAMPTZ;

-- 存量回填：上线前已完成的一律视为「当时就庆祝过了」，取其 completed_at 作为庆祝时刻。
-- 幂等：WHERE celebrated_at IS NULL，重跑对新产生的未庆祝行**也只会命中一次**，
-- 但正常情况下本迁移只跑一次（Flyway 版本表保证）。
UPDATE milestone_completions
SET celebrated_at = completed_at
WHERE celebrated_at IS NULL;

COMMENT ON COLUMN milestone_completions.celebrated_at IS
    '庆祝页展示过的时刻（UTC）。NULL=已完成但未庆祝过，是补庆祝的唯一判据；非空不再覆盖（幂等）';
