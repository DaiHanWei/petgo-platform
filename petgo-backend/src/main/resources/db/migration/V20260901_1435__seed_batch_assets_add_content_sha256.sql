-- bug 20260901-467：素材查重此前只有「同批次 + 同文件名」这一层，文件内容从不摘要 ——
-- 同一张图改个名、或传到另一个批次，完全不设防。加内容哈希列，上传时计算、命中即在素材墙标记
--（标记提示但放行，2026-09-01 产品拍板：不误拦运营刻意跨批复用的场景）。
-- ⚠️ 存量行留 NULL（历史素材字节在 OSS，回填要整批拉回来重算，不值得）；NULL 不参与查重。
ALTER TABLE seed_batch_assets
    ADD COLUMN content_sha256 VARCHAR(64);

CREATE INDEX idx_seed_batch_assets_sha ON seed_batch_assets (content_sha256)
    WHERE content_sha256 IS NOT NULL;
