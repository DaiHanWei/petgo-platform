-- 商品主图的原始像素宽高（2026-08-27）。
--
-- 为什么需要：Toko 列表改成两列瀑布流后，卡片高度 = 列宽 × (h/w)。
-- 而列表接口此前只给 URL 不给尺寸 ⇒ 高度要等图片解码完才知道 ⇒ 首屏卡片高度突变。
-- 客户端拿到宽高就能用 AspectRatio 预置高度，跳动彻底消失。
--
-- 🔴 与内容侧同一套口径（content_posts.image_sizes，V1.1.6 Story 3.1）：
-- **服务端只存原始宽高，不存比例、不存算好的高度**。比例区间收敛与高度护栏
-- 依赖可视区尺寸（因机而异），只能在客户端施加；服务端若先 clamp 一遍，
-- 客户端再 clamp 一遍就是双重裁切。见 feed_image_layout.dart 的三段口径说明。
--
-- ⚠️ 可空，且**存量不回填**：尺寸是上传时测出来的，存量商品的图早已在对象存储里，
-- 回填需要逐张下载测量。与内容侧「存量内容永远是 null」同一处理 —— 客户端占位兜底不可取消。
--
-- DDL 幂等（决策 E7 的常设要求）：重跑 no-op。
ALTER TABLE shop_products ADD COLUMN IF NOT EXISTS main_image_w INTEGER;
ALTER TABLE shop_products ADD COLUMN IF NOT EXISTS main_image_h INTEGER;

COMMENT ON COLUMN shop_products.main_image_w IS '主图原始像素宽；null=未知（存量或测量失败），客户端走占位兜底';
COMMENT ON COLUMN shop_products.main_image_h IS '主图原始像素高；null=未知（存量或测量失败），客户端走占位兜底';
