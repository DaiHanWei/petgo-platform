-- 工作线：V1.3.0 batch-b1（feat/1.3.0-batch-b1-places-social）· Story 3.4「被 @ 的通知」AC1。
--
-- 🔴 新增 CONTENT_MENTIONED，按规矩 **DROP + ADD 重列全集**（第五次重建）。
--
-- ⚠️ 这个约束已经出过**四次**漏值事故，每次都是照着一份过期清单抄，结果那几类通知
--    一写库就 23514、服务起不来。所以本文件的清单是这样来的（story AC1 的三步，逐步做完）：
--
--   ① **逐个照抄 NotificationType.java**（唯一权威）：写这份迁移时枚举共 31 个值
--      —— 上一次重建 V20260902_1620 的 30 个，加上本 story 新增的 CONTENT_MENTIONED。
--      核对方式：把枚举里的常量名与下面的字符串做过集合比对，两边差集都为空。
--   ② **复查并行分支**：`git fetch origin` 之后远端只有 origin/main 与本工作分支两条，
--      main 的 NotificationType.java 与本树**逐字节相同**，main 的迁移集合是本树的真子集
--      （只少本批次新增的那 7 个文件）—— 没有别人同期加的类型要纳入。
--   ③ **不在旧清单上打补丁、不照抄上一份迁移的列表**：下面是重新列的全集，
--      注释按来源分组只为可读，分组本身不是权威。
--
-- ⚠️ 下次再有人改这个约束：先打开 NotificationType.java 对全集 → 再查一遍并行分支 →
--    DROP + ADD 重列全集。永远不要只加一行。
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
    -- V1.3.0 batch-b1 Story 3.4（本条新增）：被 @ 提及。
    -- 🔴 帖子提及与评论提及**共用这一个值**，由 target_ref 的 variant 前缀分流
    --    （'POST:{postId}' / 'COMMENT:{postId}'）—— 沿用 NAME_RESET / LIFECYCLE_* 的范式，
    --    正是为了不让这个约束每多一种提及场景就再重列一次。
    'CONTENT_MENTIONED'));
