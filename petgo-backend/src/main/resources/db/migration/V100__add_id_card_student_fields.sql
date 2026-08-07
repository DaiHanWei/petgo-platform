-- bug 20260730-429：学生卡新增「学校/学院」用户填写字段（快照体系：卡面字段建卡时冻结落库）。
-- 可空：null → 前端渲染趣味默认（同 V96 趣味字段口径）。存量卡自然为 NULL，展示零变化。
ALTER TABLE id_cards ADD COLUMN school VARCHAR(40);
ALTER TABLE id_cards ADD COLUMN faculty VARCHAR(40);
