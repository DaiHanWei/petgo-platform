-- V1.5.0 地基 Story 0.1（CROSS-STORY-DECISIONS「2026-11-14 V1.5.0 六会话共用地基」）：
-- 通知类型约束 DROP + ADD 重列全集，一次放进 V1.5.0 全部新类型，六个云端会话都不再改这条约束。
--
-- 值的来源（2026-11-14 核对）：照抄当前树里版本序最后一条重建它的
--   V20261105_1620__rebuild_ck_notifications_type_shop_coupon_reorder.sql 的原文全集（含分组注释），末尾追加 27 个；
--   与 NotificationType.java 一致（MentionNotifyTest 取最新一条重建迁移与枚举做集合双向比对）。
-- 并行分支核查：git fetch 后对全部远端分支检索该约束，无晚于 V20261105_1620 的重建。
-- 推送可关分类的约束不动：新类型全部归现有分类（宠物提醒 / 追更宠物 / 促销与活动）。
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
    'COUPON_EXPIRING', 'SHOP_ORDER_REORDERED',
    -- V1.5.0 地基 Story 0.1 · 健康记录提醒（23 种提醒，名称 = 提醒目录 ID 加前缀）。
    'HEALTH_VACCINE_D_MINUS_7', 'HEALTH_VACCINE_D_DAY', 'HEALTH_VACCINE_D_PLUS_7',
    'HEALTH_FIRST_VACCINE', 'HEALTH_DEWORM_INTERNAL_D_DAY', 'HEALTH_DEWORM_EXTERNAL_D_DAY',
    'HEALTH_DEWORM_FLEA_WORM_D_DAY', 'HEALTH_DEWORM_HEARTWORM_D_DAY', 'HEALTH_DEWORM_INTERNAL_D_PLUS_3',
    'HEALTH_DEWORM_EXTERNAL_D_PLUS_3', 'HEALTH_DEWORM_LEGACY_D_DAY', 'HEALTH_DEWORM_LEGACY_D_PLUS_3',
    'HEALTH_FIRST_DEWORM', 'HEALTH_VET_VISIT_D_MINUS_1', 'HEALTH_VET_VISIT_D_DAY',
    'HEALTH_STITCH_CHECK', 'HEALTH_HEAT_DOG_D_MINUS_7', 'HEALTH_PYOMETRA_WATCH',
    'HEALTH_HEAT_CAT_SECOND', 'HEALTH_HEAT_MALE_FIRST', 'HEALTH_SPAY_NEUTER_SUGGESTION',
    'HEALTH_CHECKUP', 'HEALTH_CUSTOM_D_DAY',
    -- V1.5.0 地基 Story 0.1 · 推送与召回：关注的人发了新帖。
    'USER_POST_PUBLISHED',
    -- V1.5.0 地基 Story 0.1 · 超值礼包：免费次数快到期（只写通知中心）。
    'PACK_CREDIT_EXPIRING',
    -- V1.5.0 地基 Story 0.1 · 运营活动规则引擎：活动奖励到账 / 排名活动获奖。
    'OPS_ACTIVITY_REWARDED', 'OPS_ACTIVITY_RANK_WON'));
