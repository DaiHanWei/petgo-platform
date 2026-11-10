-- V1.4.2 shop · Story 2.3 · 迁移 ⑪（架构 D-21 / P-8 ⑪ · CLAUDE.md 纪律 5）：
-- 通知类型约束 DROP + ADD 重列全集 —— 电商线新增两个类型（券快过期提醒 / 迟到款已重新下单）。
--
-- 值的来源（2026-11-05 开工当天核对）：照抄当前树里版本序最后一条重建它的
--   V20261104_2010__rebuild_ck_notifications_type_and_push_mutes.sql 的原文全集（含分组注释），末尾追加电商两值；
--   与 NotificationType.java 一致（MentionNotifyTest 取最新一条重建迁移与枚举做集合双向比对）。
-- 并行分支核查：git fetch 后对全部远端分支（origin/main、origin/stag、origin/dev_1.4.2、
--   origin/feat/1.4.2-social-topic、origin/feat/1.4.2-shop）检索该约束，无晚于 V20261104_2010 的重建。
-- 推送可关分类的约束不动：两个新类型分别归现有的促销与订单账号分类。
--   （注释里不写那条约束的名字 —— ShopNotifyMigrationTest 断言本文件不含它。）
--
-- 🔴 本约束必须是文件最后一段，且本文件注释里不写任何带引号的大写值
--    （MentionNotifyTest 从 ADD 处扫到文件末尾收集引号大写值）。
-- 不回填、不改写任何既有行。
-- ⚠️ 下次改这条约束：先对枚举全集 → 再查并行分支 → DROP + ADD 重列全集，永远不要只加一行。

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
    'PET_FOLLOWED', 'PET_DIARY_POSTED',
    -- V1.4.2 shop Story 2.3：券快过期提醒 / 迟到款已为你重新下单。
    'COUPON_EXPIRING', 'SHOP_ORDER_REORDERED'));
