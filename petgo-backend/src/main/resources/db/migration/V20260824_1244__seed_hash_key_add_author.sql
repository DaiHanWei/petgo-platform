-- 去重口径修正：指纹主键加入作者维度（V1.1.6 Story 13.4 · AC4）
--
-- 🔴 **现状的两个实际后果**：
-- ① `seed_content_hashes` 以 **content_hash 单列为主键**（表里有 author_id 但**不参与键**），
--    所以同一文案想用两个不同账号各发一遍 —— 内容运营的常规操作，引入运营真实账号后会更频繁 ——
--    **第二次会被静默吞掉**。
-- ② 命中即静默跳过、界面只显示一个跳过条数；且**没有任何清理逻辑**，
--    已发布内容被删除后指纹仍在，**同样的文案永久无法重发**。
--
-- ⚠️ 改键安全：既有行的 content_hash 本就唯一，所以 (content_hash, author_id) 也必然唯一 ——
--    不需要先去重，也不会有数据丢失。

alter table seed_content_hashes drop constraint seed_content_hashes_pkey;
alter table seed_content_hashes add primary key (content_hash, author_id);

-- 🔴 **清理要按 post_id 找行**（内容被删时只知道 postId）。
--    单列主键时代没有这个查询，所以也没有这条索引。
create index ix_seed_content_hashes_post on seed_content_hashes (post_id);

comment on table seed_content_hashes is
    '种子内容去重指纹（Story 9.8；V1.1.6 Story 13.4 加作者维度）。主键 (content_hash, author_id)：同一文案不同账号各自独立。内容被删除时由 SeedHashCleanupListener 清理对应行。';
