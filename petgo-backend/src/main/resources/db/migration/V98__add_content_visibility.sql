-- V1.1.2 Story 4.1 · FR-83：内容可见范围（Diary 可以只留给自己）。
--
-- 设计要点（架构 AD-4）：
--   1. **通用字段，不做 Diary 专属布尔** —— 三类内容（Diary / Moment / Tips）统一携带
--      visibility，日后任何类型要「只留给自己」都不必再加列。
--   2. **DEFAULT 'PUBLIC' + NOT NULL**：存量行由 DEFAULT 直接补齐（PostgreSQL 加带默认值的
--      NOT NULL 列不重写整表），语义上等于「存量一律保持公开」。
--   3. **CHECK 约束**兜住取值，防止应用层枚举漂移写入非法值。
--
-- ⚠️ NFR-6：迁移必须在功能开关生效前完成，不接受「先上线再补回填」——否则老用户内容会
--    在开关生效与回填之间的窗口里从 Feed 消失。私密**只由用户主动关同步开关产生**，
--    不存在需要额外回填的私密态。
ALTER TABLE content_posts
    ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC';

ALTER TABLE content_posts
    ADD CONSTRAINT content_posts_visibility_check CHECK (visibility IN ('PUBLIC', 'PRIVATE'));

-- 显式回填留痕（DEFAULT 已覆盖既有行；这条是幂等兜底 + 审计可读性，NFR-6）。
UPDATE content_posts SET visibility = 'PUBLIC' WHERE visibility IS NULL;

-- Feed 主查询在 visibility 上再加一层过滤，且恒按 created_at DESC 翻页 → 组合索引。
CREATE INDEX IF NOT EXISTS idx_content_posts_visibility_created_at
    ON content_posts (visibility, created_at DESC);

COMMENT ON COLUMN content_posts.visibility IS
    'PUBLIC=平台可自动分发（Feed/聚合/他人主页）；PRIVATE=仅作者自视。作者主动分享（名片 H5）不受此约束（OQ-18）。';
