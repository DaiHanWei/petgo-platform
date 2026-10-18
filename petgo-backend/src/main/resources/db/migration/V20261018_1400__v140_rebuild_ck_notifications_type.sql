-- V1.4.0 地基 · Story 0.1 AC1（① 通知类型）（四条线共用：① Tailrent Level / ② 多宠物 / ③ 送礼 / ④ 推送开关与收藏）。
--
-- 🔴🔴 本迁移全量重建共享 CHECK ck_notifications_type。🔴🔴 四条线的功能分支一律不再改这条约束
--      （归属见 CROSS-STORY-DECISIONS.md「2026-10-18 V1.4.0 四条线共用地基」）。
--
-- 本文件的清单是这样来的：
--
--   ① ck_notifications_type：照抄当前树里最后一条重建它的 V20261012_1300（34 值，与 NotificationType.java 一致），
--      末尾追加 V1.4.0 五个新类型 = 39 值。核对方式：MentionNotifyTest 取最新一条重建迁移与枚举做集合双向比对。
--   ② 支付用途约束拆在同批的 V20261018_1401（MentionNotifyTest 按「整文件里的引号值」解析本约束，
--      两条约束放同一文件会互相污染，故一约束一文件）。
--   ③ 并集核查（2026-10-18 开工当天，git fetch --all --prune 后对全部远端分支跑 Story 0.1 Dev Notes 的命令）：
--      相对 main 只有 origin/epic-6-batch 的 V19、origin/hex/v1.1.6 的 V111 涉及这两条约束，
--      两者的值都已包含在上述基数里 —— 没有其它分支同期加的值需要纳入。
--   ④ 不在旧清单上打补丁：下面是重新列的全集，注释按来源分组只为可读，分组本身不是权威。
--
-- 不回填、不改写任何既有行。
--
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
    -- V1.1.6 Story 6.1（FR-76）：S/M 级里程碑达成 —— 写通知中心、不发系统推送。
    'MILESTONE_SM_NODE',
    -- 留存手册抓手 1（V20260821_1646）：生命周期四节点，单一类型 + target_ref variant 分流。
    'LIFECYCLE_D1', 'LIFECYCLE_D3', 'LIFECYCLE_D7', 'LIFECYCLE_WINBACK',
    -- V1.3.0 batch-b1 Story 3.4（V20260915_1614）：被 @ 提及，帖子 / 评论共用一个值。
    'CONTENT_MENTIONED',
    -- V1.3.4 app 主题 Story 6.1（V20261012_1108）：运营系统通知。
    'OPS_ANNOUNCEMENT',
    -- V1.3.4 follow 主题（FR-141）：被关注 / 我创建的场所被评论。
    'USER_FOLLOWED', 'PLACE_COMMENTED',
    -- V1.4.0 ① Tailrent Level：升级通知。
    'GROWTH_LEVEL_UP',
    -- V1.4.0 ② 多宠物：月租名额即将到期 / 已到期。
    'PET_SLOT_EXPIRING', 'PET_SLOT_EXPIRED',
    -- V1.4.0 ③ 送礼：宠物收到礼物（不叫 GIFT_RECEIVED，避免和成长值规则代码同名）/ 礼物未送达、已退回。
    'PET_GIFT_RECEIVED', 'GIFT_UNDELIVERED_REFUND'));
