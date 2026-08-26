-- 运营发布身份池：真实账号授权关联表（V1.1.6 Story 12.1 · AB-3I）
--
-- 🛡 **刻意不新增 account_type**（AC2）：运营真实账号的 account_type 保持 REAL 不变。
--    一旦真实号被改成别的类型，它在 App 内的一切行为（登录、发帖、被查看）
--    都会走进未被验证的分支 —— 那个代价远大于"后台多一张关联表"。
--
-- 授权是**有历史的**：一个账号可以被纳入、移出、再纳入。所以每次纳入是一行新记录，
-- 移出只是把这一行标成 REMOVED，不删行 —— 否则"谁在什么时候把谁移出去的"就查不到了。
create table seed_real_account_grants (
    id                  bigserial   primary key,
    user_id             bigint      not null references users (id),
    -- 授权说明必填（AC3）。风险边界是"内部人冒充内部人"，技术强绑定挡不住，
    -- 靠的是审计可追责 —— 而说明是这条链上唯一记录"为什么可以"的地方。
    authorization_note  varchar(500) not null,
    granted_by          bigint      not null,
    granted_at          timestamptz not null,
    -- ACTIVE / REMOVED
    status              varchar(20) not null,
    removed_by          bigint,
    removed_at          timestamptz
);

-- 一个账号同时只能有一条生效授权；历史 REMOVED 行不受限（可重复纳入）。
create unique index ux_seed_real_grants_active_user
    on seed_real_account_grants (user_id)
    where status = 'ACTIVE';

create index ix_seed_real_grants_status on seed_real_account_grants (status, id desc);

-- 「以运营真实账号发布的每条内容，记录实际操作的后台账号」（AC7 最后一条）。
--
-- 🔴 这是运营真实账号与虚拟账号最大的差别：虚拟账号是平台身份，
--    真实账号是**某个真人的账号** —— 出事要能追到具体是哪个后台账号操作的。
--    存量行为 NULL（那时还没有这个概念，不回填假数据）。
--    ⚠️ 对虚拟账号也一并记录：少一个分支，且同样有追责价值。
alter table seed_content_hashes add column published_by_admin_id bigint;
