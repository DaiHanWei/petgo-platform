-- V1.1.6 Story 3.1 · AD-5：图片原始宽高入库。
--
-- 🛡 与 image_urls **并排**的新列，**不改动 image_urls 本身** ——
-- 既有五处读取点（首页首图 / 内容详情 / 时间线 / 宠物名片 / 后台）与发布回显 DTO
-- 一处都不动，存量为空、零回填、零迁移。
--
-- 形状：与 image_urls **同序等长**的对象数组 [{"w":1200,"h":1600}, null, ...]；
-- 某张测不出来时用 null 占位**保持下标对齐** —— 等长同序是硬约束，错位即图文不符。
--
-- ⚠️ 存量内容永远为空：客户端的加载期占位策略因此是必做项（AD-6 Rule 4），
-- 不是可选优化。
ALTER TABLE content_posts ADD COLUMN image_sizes JSONB;

COMMENT ON COLUMN content_posts.image_sizes IS
  'V1.1.6 Story 3.1：与 image_urls 同序等长的原始宽高数组 [{w,h}|null]。仅原始尺寸，'
  '不含已 clamp 的比例或已算好的高度（那些一律客户端算，见 AD-6 Rule 6）。存量为 NULL。';
