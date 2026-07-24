-- bug 20260724-360：账号注销在 stag 全断——cm-9（注销默认隐藏联动）把注销用户内容置
-- AUTHOR_DEACTIVATED（18 字符），超出 content_posts.status / comments.moderation_status 的
-- varchar(16) → 22001 value too long → AccountDeletionService 状态机恒 FAILED（deleted_at
-- 永不落 → feed 实名帖仍可见 + 后台显示「正常」）。V56 当时只放宽了评论 CHECK，漏了列宽。
-- 两列加宽到 32；varchar 加宽在 PG 是纯元数据变更，无表重写、在线安全。
-- 部署后 stag 遗留的 FAILED 注销行由启动重扫（rescanOnStartup）自动续跑补完，无需手工修数。
-- ⚠️ cm-9 上 prod 前本迁移必须先行，否则生产注销同样全断。

ALTER TABLE content_posts
    ALTER COLUMN status TYPE VARCHAR(32);

-- comments.moderation_status 由内容审核工作线号段（V48/V49/V56）创建，v1.1-dev 工作线的
-- scratch 库不含该列（本线号段 V60 起）——存在才加宽，两条工作线的库历史都能安全通过。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'public' AND table_name = 'comments'
                 AND column_name = 'moderation_status') THEN
        ALTER TABLE comments ALTER COLUMN moderation_status TYPE VARCHAR(32);
    END IF;
END $$;
