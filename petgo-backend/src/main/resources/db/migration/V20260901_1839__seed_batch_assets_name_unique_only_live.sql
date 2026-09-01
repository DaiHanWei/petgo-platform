-- bug 20260901-474：素材墙支持单张删除（标记废弃，OSS 对象按 F21 不物理删）。
-- 同名唯一索引原来把**已废弃**的行也算在内 —— 删除后重传同名文件（「替换」的正当路径）
-- 必然撞索引被拒。改成部分唯一索引：唯一性只约束在用（未废弃）的素材。
DROP INDEX ux_seed_batch_assets_name;

CREATE UNIQUE INDEX ux_seed_batch_assets_name
    ON seed_batch_assets (batch_id, file_name)
    WHERE orphaned_at IS NULL;
