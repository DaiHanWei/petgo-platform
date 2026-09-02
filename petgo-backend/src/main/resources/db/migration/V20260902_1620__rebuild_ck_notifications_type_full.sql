-- 工作线：V1.1.6（dev/dev_1.1.6）。
--
-- 🔴 ck_notifications_type 第四次漏值事故的兜底重建。
--
-- 事故链：该约束的修改方式是「DROP 掉再按完整清单 ADD 回去」，谁排在版本序最后谁说了算。
-- 提交 2f59598e 给电商线的四份重建迁移（V20260818_0358 / 0359 / 0441 / 0518）补齐了
-- ACCOUNT_WARNED / ACCOUNT_SUSPENDED，但漏掉了排序更靠后的 V20260819_0156 ——
-- 它重建时的清单没有 SHOP_ORDER_SHIPPED / SHOP_ORDER_EXCEPTION / SHOP_RETURN_UPDATED /
-- REPURCHASE_FOOD_LOW 四个在用类型，于是合并后这四类通知一写库就 23514。
-- 此前 V97（两条工作线互踢）、V20260818_0518 头注（漏抄 V104 两值挡死 staging）、
-- 本次是同一模式的第四回。
--
-- 修复方式：不改 V20260819_0156 本身（无法确认它是否已应用到某环境，已应用的迁移
-- 改 checksum 就是启动即拒），按安全默认另起本条新迁移、以终序重建一次全集。
--
-- ⚠️ 取值全集以 NotificationType.java 枚举为唯一权威（当前 30 个值，逐个照抄源码，
--    不凭记忆、不照抄上一份清单）。下次再有人改这个约束：
--    1) 先打开 NotificationType.java 对全集；2) 再查一遍并行分支加了什么；
--    3) DROP + ADD 重列全集，永远不要只在旧清单上打补丁。
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
    -- 正是 V20260819_0156 重建时漏掉的四个在用类型。
    'SHOP_ORDER_SHIPPED', 'SHOP_ORDER_EXCEPTION', 'SHOP_RETURN_UPDATED', 'REPURCHASE_FOOD_LOW',
    -- V1.1.6 Story 6.1（FR-76）：S/M 级里程碑达成 —— 写通知中心、不发系统推送。
    'MILESTONE_SM_NODE',
    -- 留存手册抓手 1（V20260821_1646）：生命周期四节点，单一类型 + target_ref variant 分流。
    -- 🔴 第五次漏值就差点发生在这里：本条写在 0902、跑在 0821 之后，重列全集时漏抄
    --    这四个 = 把生命周期推送悄悄踢掉；petgo_stag 若已有该类行，重建即 23514 启动崩。
    'LIFECYCLE_D1', 'LIFECYCLE_D3', 'LIFECYCLE_D7', 'LIFECYCLE_WINBACK'));
