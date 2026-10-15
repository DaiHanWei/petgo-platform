-- V1.3.4 app 主题 · Story 6.1「运营通知类型与活动数据」AC2（FR-138 / AD-17 / 决策日志 D-26）。
--
-- 🔴 新增 OPS_ANNOUNCEMENT（本主题），并为 follow 主题（feat/1.3.4-follow-notify，FR-141）
--    预占 USER_FOLLOWED、PLACE_COMMENTED —— 按规矩 **DROP + ADD 重列全集**，共 34 值。
--    follow 主题的重建迁移列的是同一个 34 值集合：两条谁后执行，结果都是完整全集（D-26）。
--
-- 本文件的清单是这样来的：
--
--   ① **逐个照抄 NotificationType.java**（唯一权威）：枚举共 34 个值 = 上一次重建
--      V20260915_1614 的 31 个 + 末尾按序追加的 OPS_ANNOUNCEMENT、USER_FOLLOWED、PLACE_COMMENTED。
--      核对方式：枚举常量名与下面的字符串做集合双向比对，两边差集都为空
--      （MentionNotifyTest 机械校验）。
--   ② **并集核查**（2026-10-12 开工当天，git fetch --all 后跑 story 6.1 Dev Notes 的命令）：
--      45 个远端分支；各分支最后一条重建迁移的取值并集 = 31 值，枚举侧并集 = 31 值，两侧一致，
--      与本树上一份全集完全相同 —— 没有其它分支同期加的类型需要纳入。
--   ③ **不在旧清单上打补丁**：下面是重新列的全集，注释按来源分组只为可读，分组本身不是权威。
--
-- ⚠️ 下次改这个约束：先对枚举全集 → 再查并行分支 → DROP + ADD 重列全集，永远不要只加一行。
ALTER TABLE notifications DROP CONSTRAINT IF EXISTS ck_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type CHECK (type IN (
    'VET_REPLY', 'CONSULT_CLOSED', 'CONTENT_LIKED', 'CONTENT_COMMENTED', 'NEW_CONSULT_REQUEST',
    'PET_BIRTHDAY', 'COMPANION_ANNIVERSARY', 'MILESTONE_NODE', 'CONTENT_REMOVED', 'REPORT_REVIEWED',
    'CONTENT_REVIEW_APPROVED', 'CONTENT_REVIEW_REJECTED',
    'NAME_RESET', 'AVATAR_RESET', 'CONTENT_REVIEW_TIMED_OUT',
    'REFUND_REJECTED', 'TICKET_RESOLVED', 'CSAT_SURVEY', 'IDENTITY_REQUIRE_MODIFY',
    -- V1.1.4 Story 3.2（V104）：账号级处置两档的用户侧通知。
    'ACCOUNT_WARNED', 'ACCOUNT_SUSPENDED',
    -- V1.4.0 电商线（V20260818_0358/0359/0441/0518）：发货 / 异常处置 / 退货进度 / 粮量见底。
    'SHOP_ORDER_SHIPPED', 'SHOP_ORDER_EXCEPTION', 'SHOP_RETURN_UPDATED', 'REPURCHASE_FOOD_LOW',
    -- V1.1.6 Story 6.1（FR-76）：S/M 级里程碑达成 —— 写通知中心、不发系统推送。
    'MILESTONE_SM_NODE',
    -- 留存手册抓手 1（V20260821_1646）：生命周期四节点，单一类型 + target_ref variant 分流。
    'LIFECYCLE_D1', 'LIFECYCLE_D3', 'LIFECYCLE_D7', 'LIFECYCLE_WINBACK',
    -- V1.3.0 batch-b1 Story 3.4（V20260915_1614）：被 @ 提及，帖子 / 评论共用一个值。
    'CONTENT_MENTIONED',
    -- V1.3.4 app 主题 Story 6.1（本条新增）：运营系统通知，target_ref = 活动 public_token。
    'OPS_ANNOUNCEMENT',
    -- V1.3.4 follow 主题（FR-141，D-26 预占）：被关注 / 我创建的场所被评论。
    'USER_FOLLOWED', 'PLACE_COMMENTED'));
