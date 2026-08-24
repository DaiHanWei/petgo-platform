-- 批量内容的「草稿 / 排期」存储（V1.1.6 Story 13.1 · AB-3K/3L）
--
-- 🔴 **"存下来但还没发"是系统此前完全不具备的能力**：内容状态只有
--    PUBLISHED / UNDER_REVIEW / AUTHOR_DEACTIVATED 三个，没有草稿。
--
-- 🛡 **两条设计约束，改动前先读**：
--
-- ① **草稿绝不写进 content_posts**（AC4）。用状态列在同一张表里区分草稿的话，
--    所有既有的"已发布内容"查询（Feed / 时间线 / 后台内容列表 / 统计 / 举报队列…）
--    都必须记得排除草稿 —— 漏一处就是草稿泄漏到线上。所以是**独立新表**，
--    行发布成功后才在 content_posts 产生真实内容，并把内容 id 回填到该行。
--
-- ② **状态挂在行上，批次不持有状态**（AC2）。「47 已发布 / 5 排期中 / 3 待修正」
--    是常态而非异常；按批次级状态实现就得为这类组合定义一堆合成态，
--    而行级操作（改某一条的计划时间、修某一条的校验错误）根本无处落脚。
--    ⚠️ 所以本文件里 seed_batches **没有 status 列**，这是刻意的，不是漏了。

create table seed_batches (
    id          bigserial   primary key,
    -- 录入方式：ONLINE_PASTE 在线粘贴 / EXCEL 导入（13-3 用它回显"这批是怎么来的"）
    source      varchar(20) not null,
    created_by  bigint      not null,
    created_at  timestamptz not null
);

comment on table seed_batches is
    '批量内容批次容器（V1.1.6 Story 13.1）。🛡 只是分组 + 审计，**刻意没有 status 列**：状态挂在 seed_batch_rows 上。';

create table seed_batch_rows (
    id                bigserial    primary key,
    batch_id          bigint       not null references seed_batches (id),
    -- 原始行号：13-3/13-4 要能回显"第 7 行的正文超字数"，而不是给个内部 id
    row_no            int          not null,
    -- DRAFT / VALIDATED / SCHEDULED / PUBLISHED / FAILED
    status            varchar(20)  not null,
    -- 🔴 发布账号**在行上**，不在批次上：12-1 的「该账号还有 N 条待发布排期」
    --    要按作者统计，挂在批次上就数不出来。
    author_user_id    bigint       not null,
    content_type      varchar(20)  not null,
    pet_id            bigint,
    body              text,
    image_urls        jsonb,
    -- 与 image_urls **同序等长**（口径同 content_posts.image_sizes）
    image_sizes       jsonb,
    -- 计划发布时刻（UTC）。NULL = 还没排期
    scheduled_at      timestamptz,
    -- 发布成功后回填，是「整批撤回」（本版本不做，OQ-22 后移）的数据基础
    content_post_id   bigint,
    -- 校验错误（DRAFT 态）或发布失败原因（FAILED 态）。
    -- ⚠️ 一行不可能同时处于这两个阶段，所以刻意**只有一列** —— 两列会让人不知道该读哪个。
    error_message     varchar(500),
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null
);

comment on column seed_batch_rows.status is
    'DRAFT 草稿 / VALIDATED 校验通过待确认 / SCHEDULED 已排期待发布 / PUBLISHED 已发布 / FAILED 发布失败。合法流转见 SeedBatchRowStatus。';
comment on column seed_batch_rows.error_message is
    'DRAFT 态存校验错误、FAILED 态存发布失败原因。一行不会同时处于两个阶段，故只一列。';

-- 批次聚合视图：按 batch 拉全部行。
create index ix_seed_batch_rows_batch on seed_batch_rows (batch_id, row_no);

-- 🔴 13-5 的到点扫描 + 12-1 的「该账号还有几条排期」都走这条索引。
--    只索引 SCHEDULED：其余状态的行永远不会被这两个查询碰到，
--    而这张表里 PUBLISHED 会长期占绝大多数。
create index ix_seed_batch_rows_scheduled on seed_batch_rows (scheduled_at, author_user_id)
    where status = 'SCHEDULED';
