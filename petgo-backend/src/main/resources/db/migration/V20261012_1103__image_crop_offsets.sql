-- V1.3.4 Story 2.1 · AD-5：每张图在 Feed 里的露出位置（FR-125）。
-- 形状：与 image_urls 同序等长的数组 [0.0~1.0 | null, ...]；0 = 贴起始边、0.5 = 居中、1 = 贴结束边
--（宽图为左右、竖图为上下）。null = 该图在容差区间内 / 无需开窗。
-- 存量行一律 NULL、不回填（NFR7：无该值即沿用居中 cover，与改动前逐像素一致）。
-- 与 image_sizes 同一模式（V20260818_1344），不新建图片表。
ALTER TABLE content_posts ADD COLUMN IF NOT EXISTS image_crop_offsets JSONB;

COMMENT ON COLUMN content_posts.image_crop_offsets IS
  'V1.3.4 Story 2.1：与 image_urls 同序等长的露出位置数组 [0~1|null]。'
  '仅超出 0.75~1.78 容差区间的图有值；存量为 NULL、不回填。';
