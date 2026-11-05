-- V1.4.2 social-topic · Story 1.2 · 迁移 ⑦（架构 D-7 / P-8 ⑦ · 决策日志 I6 · CLAUDE.md 第 5 条）：
-- 两条 CHECK 约束 DROP + ADD 重列全集 —— 通知类型 +2（PET_FOLLOWED / PET_DIARY_POSTED）、推送可关分类 +1（PET_DIARY）。
--
-- 值的来源（2026-11-04 开工当天核对）：
--   ① 推送分类约束：照抄当前树里唯一一条定义它的 V20261018_1800__create_user_push_mutes.sql（8 个可关分类，
--      不含订单与账号），末尾追加 PET_DIARY = 9 值；与 PushCategory 中 lockable = true 的集合一致
--      （UserPushMutesMigrationTest 取版本序最后一条定义该约束的迁移比对）。
--   ② 通知类型约束：照抄当前树里版本序最后一条重建它的 V20261018_1400__v140_rebuild_ck_notifications_type.sql
--      原文全集（39 值；其后至 V20261104_2000 的迁移均不重建该约束），末尾追加两值 = 41 值；
--      与 NotificationType.java 一致（MentionNotifyTest 取最新一条重建迁移与枚举做集合双向比对）。
--   ③ 并行分支核查：git fetch --all --prune 后对全部远端分支（含 origin/dev_1.4.2、origin/main、电商线）
--      检索两条约束 —— 没有任何分支有晚于 V20261018_1400 / V20261018_1800 的重建，无需并入其它值。
--
-- 🔴 文件内顺序：推送分类约束在前，通知类型约束必须放在文件最后。
--    原因：守卫 MentionNotifyTest 从通知类型约束的 ADD 处一直扫到文件末尾收集所有引号大写值，
--    推送分类写在它后面会被当成通知类型，守卫必红（同 V20261018_1400 文件头「一约束一文件」的理由）。
--    因此本文件注释里也不写任何带引号的大写值。
--
-- 🔴 与同版本电商线交界：后合并的一方负责在同一迁移里重列全集（planning-artifacts/v1.4.2/README.md、PRD §6）。
--
-- 不回填、不改写任何既有行。
--
-- ⚠️ 下次改这两条约束：先对枚举全集 → 再查并行分支 → DROP + ADD 重列全集，永远不要只加一行。

-- ① 推送可关分类（9 值）：V20261018_1800 的 8 值 + V1.4.2 追更宠物。
ALTER TABLE user_push_mutes DROP CONSTRAINT IF EXISTS ck_user_push_mutes_category;
ALTER TABLE user_push_mutes ADD CONSTRAINT ck_user_push_mutes_category CHECK (category IN
    ('LIKE', 'COMMENT', 'MENTION', 'FOLLOWER', 'PET_DIARY', 'GIFT', 'PET_REMINDER', 'ACHIEVEMENT', 'PROMO'));

-- ② 通知类型（41 值）：V20261018_1400 原文全集 + V1.4.2 追更两值。本约束必须是文件最后一段。
ALTER TABLE notifications DROP CONSTRAINT IF EXISTS ck_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type CHECK (type IN (
    'VET_REPLY', 'CONSULT_CLOSED', 'CONTENT_LIKED', 'CONTENT_COMMENTED', 'NEW_CONSULT_REQUEST',
    'PET_BIRTHDAY', 'COMPANION_ANNIVERSARY', 'MILESTONE_NODE', 'CONTENT_REMOVED', 'REPORT_REVIEWED',
    'CONTENT_REVIEW_APPROVED', 'CONTENT_REVIEW_REJECTED',
    'NAME_RESET', 'AVATAR_RESET', 'CONTENT_REVIEW_TIMED_OUT',
    'REFUND_REJECTED', 'TICKET_RESOLVED', 'CSAT_SURVEY', 'IDENTITY_REQUIRE_MODIFY',
    -- V1.1.4 Story 3.2（V104）：账号级处置两档的用户侧通知。
    'ACCOUNT_WARNED', 'ACCOUNT_SUSPENDED',
    -- 电商线（V20260818_0358/0359/0441/0518）：发货 / 异常处置 / 退货进度 / 粮量见底。
    'SHOP_ORDER_SHIPPED', 'SHOP_ORDER_EXCEPTION', 'SHOP_RETURN_UPDATED', 'REPURCHASE_FOOD_LOW',
    -- V1.1.6 Story 6.1（FR-76）：S/M 级里程碑达成。
    'MILESTONE_SM_NODE',
    -- 留存手册抓手 1（V20260821_1646）：生命周期四节点。
    'LIFECYCLE_D1', 'LIFECYCLE_D3', 'LIFECYCLE_D7', 'LIFECYCLE_WINBACK',
    -- V1.3.0 batch-b1 Story 3.4（V20260915_1614）：被 @ 提及。
    'CONTENT_MENTIONED',
    -- V1.3.4 app 主题 Story 6.1（V20261012_1108）：运营系统通知。
    'OPS_ANNOUNCEMENT',
    -- V1.3.4 follow 主题（FR-141）：被关注 / 我创建的场所被评论。
    'USER_FOLLOWED', 'PLACE_COMMENTED',
    -- V1.4.0 ① Tailrent Level：升级通知。
    'GROWTH_LEVEL_UP',
    -- V1.4.0 ② 多宠物：月租名额即将到期 / 已到期。
    'PET_SLOT_EXPIRING', 'PET_SLOT_EXPIRED',
    -- V1.4.0 ③ 送礼：宠物收到礼物 / 礼物未送达、已退回。
    'PET_GIFT_RECEIVED', 'GIFT_UNDELIVERED_REFUND',
    -- V1.4.2 social-topic Story 1.2：有人追更了我的宠物 / 我追更的宠物发了新 Diary。
    'PET_FOLLOWED', 'PET_DIARY_POSTED'));
