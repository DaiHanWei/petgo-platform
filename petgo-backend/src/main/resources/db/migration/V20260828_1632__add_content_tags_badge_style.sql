-- 内容标签胶囊底色（2026-08-28，UI 稿 `.deco-badge`）。
--
-- 稿子里胶囊底是一道 135° 双色渐变（橙→红），此前写死在 App 里：
-- 运营只能配胶囊上的字与那枚小图，于是「这枚胶囊」在他眼里只有一半是自己的。
-- 产品 2026-08-28 拍板把底色也交给运营（同批：后台要能预览整枚胶囊）。
--
-- 存**枚举名**（ContentTagBadgeStyle），对外由服务端翻成两个色值（渐变起止）下发 ——
-- 客户端不必认识调色板，加一档不用发版。
--
-- 🔴 NOT NULL + DEFAULT 'SUNSET'：SUNSET 正是 UI 稿原始的橙→红，
--    存量标签因此保持现在的样子，不需要任何回填决策。
--
-- ⚠️ 刻意不加 CHECK：取值全集由 Java 枚举把关，CHECK 会让「以后加一档颜色」
--    变成一次必须重列全集的迁移（本仓库在共用 CHECK 上栽过三次，见 CLAUDE.md）。
ALTER TABLE content_tags
    ADD COLUMN badge_style VARCHAR(24) NOT NULL DEFAULT 'SUNSET';

COMMENT ON COLUMN content_tags.badge_style IS
    '胶囊底色（ContentTagBadgeStyle 枚举名）。胶囊上是白色粗体 9.5px，故只提供白字读得出的固定几档。';
