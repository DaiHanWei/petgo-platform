-- 批次素材（V1.1.6 Story 13.2 · AB-3K Step 1）
--
-- 🔴 **为什么素材必须挂在批次上**：Step 1 的图是**先落对象存储再回显缩略图**的 ——
--    运营刚拖进去，图就已经在存储里，而此时**还没有任何内容行引用它**。
--    不挂批次的话，"拖错文件夹关掉页面 / 填一半放弃 / 整批校验没过" 这些图会永久留在存储里，
--    **不报错、不影响功能、无人会发现**，只是账单慢慢涨（按上限单个废弃批次最多 500MB）。
--
-- ⚠️ **orphaned_at 是"记账"而不是"已删"**（2026-08-24 用户拍板）：
--    既有决策 F21（2026-08-19）明令 OSS 对象**任何情况不物理删除**、删除原语已整体移除，
--    并要求"确需删除先回 F21 重新拍板"。本 story 不去打破它 ——
--    改为把废弃素材**标记出来并留住对象 key 与占用字节**，
--    于是泄漏从「无人知道」变成「有账可查」，将来决定回收时是一条 SQL 的事。
--    🛡 **所以清理时不删这张表的行**，只写 orphaned_at。

create table seed_batch_assets (
    id           bigserial    primary key,
    batch_id     bigint       not null references seed_batches (id),
    -- 运营那边的原始文件名。查重用它，回显清单也用它 —— 运营认的是文件名，不是 URL。
    file_name    varchar(255) not null,
    -- 对象存储里的 key。🔴 回收要靠它，所以**标记废弃时绝不能丢**。
    object_key   varchar(512) not null,
    url          varchar(1024) not null,
    -- 原始宽高（0 = 测不出来）。缩略图墙据此给超出 0.75~1.34 的图打标记。
    width        int          not null,
    height       int          not null,
    size_bytes   bigint       not null,
    created_at   timestamptz  not null,
    -- 非空 = 已判定为废弃、可回收。**不代表已从存储删除**（见上）。
    orphaned_at  timestamptz
);

comment on table seed_batch_assets is
    '批次素材清单（V1.1.6 Story 13.2）。orphaned_at 非空 = 已判定可回收但**尚未物理删除**（决策 F21 未反转）。';
comment on column seed_batch_assets.object_key is
    '对象存储 key。回收要靠它，标记废弃时绝不能丢——丢了泄漏就从"有账可查"退回"无人知道"。';

-- 🛡 **同批文件名不许重复**（AC3）。放在**上传阶段**拦而不是校验阶段：
--    拖到校验阶段才报错时，运营已经把整份表格填完了。
--    DB 唯一约束是兜底 —— 应用层先查一遍给友好提示，但并发下只有这条挡得住。
create unique index ux_seed_batch_assets_name on seed_batch_assets (batch_id, file_name);

-- 缩略图墙按上传顺序展示；清理扫描器按批次拉素材。
create index ix_seed_batch_assets_batch on seed_batch_assets (batch_id, id);
