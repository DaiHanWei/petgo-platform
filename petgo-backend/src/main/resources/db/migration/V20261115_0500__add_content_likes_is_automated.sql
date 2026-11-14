-- V1.5.0 likes Story 1.1（PRD 自动点赞 FR-218）：标记由自动点赞任务创建的赞。
-- 只有 AutoLike 任务写 true；App 点赞、后台暖贴、运营手工点赞一律 false。存量行全部 false（不回填）。
-- 排序 / 推荐 / 热度 / 运营统计必须排除 is_automated；展示赞数、点赞人、主页 Suka 不排除。
ALTER TABLE content_likes ADD COLUMN is_automated BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN content_likes.is_automated IS
    '是否由自动点赞任务创建（V1.5.0）；true 的赞计入展示与通知，但不计入排序、推荐、热度与运营统计';
