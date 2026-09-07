-- 批次级默认值 + 行级关联物种（V1.1.6 Story 13.3 · AB-3K Step 0/2）
--
-- 🔴 **为什么要有批次默认**：此前两条录入路径（多行纯文本 / Excel 导入）**各带一个
--    一模一样的账号下拉**，于是同一页面出现两次；而逐行必填意味着 50 行填 50 次、
--    其中大多数是同一个值 —— 纯重复劳动，且手打账号名比选下拉更易错（§7.5 第 2 条）。
--    改成页头选一次，行内留空即继承。
--
-- ⚠️ 这**覆盖**了 V1.1.0 原「发布账号留空 = 校验失败、视为必填缺失」的规则。

alter table seed_batches add column default_author_user_id bigint;
alter table seed_batches add column default_content_type   varchar(20);
alter table seed_batches add column default_scheduled_at   timestamptz;

comment on column seed_batches.default_content_type is
    '批次默认内容类型。🔴 只允许 DAILY / KNOWLEDGE —— 批量不支持 GROWTH_MOMENT（A-10：成长日历需逐行绑宠物与事件日期、属"真实记录"性质，不适合批量灌入）。';
comment on column seed_batches.default_scheduled_at is
    '批次默认计划发布时间。NULL = 立即发布。';

-- 行级「关联物种」（AB-3H / Story 14-1 的字段，本 story 只负责**承载与继承**）。
--
-- 🔴 **刻意没有批次级默认**（A-14）：账号物种定位本身就在扮演账号级默认值的角色、
--    而且扮演得更好（配一次永久生效、跟着账号走）。再加一层批次默认会与它冲突 ——
--    批次默认设「猫」、行留空、而该行发布账号是狗号时，**取谁没有正确答案**。
alter table seed_batch_rows add column species varchar(20);

comment on column seed_batch_rows.species is
    '关联物种。留空的解析规则：虚拟账号 → 继承其「账号物种定位」（该字段由 Story 14-1 落地）；运营真实账号 → 留空入库，由算法按作者宠物档案推导。';
