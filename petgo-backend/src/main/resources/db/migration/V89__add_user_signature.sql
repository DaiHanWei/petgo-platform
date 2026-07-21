-- bug 20260721-327：用户主页「一句话个性签名」。用户级签名（区别于宠物档案 intro）。
-- 可空、≤60 字；ddl-auto=validate → 实体 signature 列须与此对齐。
ALTER TABLE users ADD COLUMN signature varchar(60);
