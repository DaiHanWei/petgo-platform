-- 用户标签徽章底色（2026-08-28，UI 稿 `.utag-icon`）。
--
-- 稿子里同一个圆形徽章按标签取不同底色（官方账号金、「最佳新人」紫），
-- 而实现里只有一个写死的金色，运营配不出第二种。本列把它变成可配。
--
-- 存**枚举名**（UserTagBadgeColor，架构约定：枚举落库 varchar + UPPER_SNAKE），
-- 对外由服务端翻成十六进制色值下发 —— 客户端不必认识调色板，加档不用发版。
--
-- 🔴 NOT NULL + DEFAULT 'GOLD'：GOLD 正是 UI 稿的 CSS 默认值，
--    存量标签因此保持现在的样子，不需要任何回填决策。
--
-- ⚠️ 刻意**不加 CHECK 约束**：取值全集由 Java 枚举把关（未知值读取即失败），
--    而 CHECK 会让「以后加一档颜色」变成一次必须重列全集的迁移 ——
--    本仓库在共用 CHECK 上已经栽过三次（见 CLAUDE.md）。
ALTER TABLE user_tags
    ADD COLUMN badge_color VARCHAR(16) NOT NULL DEFAULT 'GOLD';

COMMENT ON COLUMN user_tags.badge_color IS
    '徽章圆底颜色（UserTagBadgeColor 枚举名）。图标是纯白剪影，故只提供足够深的固定几档。';
