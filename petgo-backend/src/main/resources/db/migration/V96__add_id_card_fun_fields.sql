-- bug 20260729-409：KTP/护照卡面展示的出生城市/地址/职业/婚姻状态此前是前端渲染期趣味常量，
-- Edit Info 改不到（编辑字段与卡面不一致）。快照化这 4 个字段：建卡时可编辑并冻结入快照。
-- 旧卡与未填写的新卡为 NULL → 前端继续渲染趣味默认，展示零变化（决策 E2：已提交迁移冻结，新起 ALTER）。
ALTER TABLE id_cards
    ADD COLUMN birth_city     varchar(40),
    ADD COLUMN address        varchar(80),
    ADD COLUMN occupation     varchar(40),
    ADD COLUMN marital_status varchar(24);
