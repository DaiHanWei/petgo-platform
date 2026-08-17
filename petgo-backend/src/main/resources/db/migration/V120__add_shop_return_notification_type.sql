-- V1.4.0 精选自营电商 · Story 5.3/5.4/5.5 —— 退货进度站内信类型。
-- 工作线：V1.4.0 电商 · 独占号段 V101–V139。
--
-- ⚠️⚠️ 【触碰共享 CHECK：ck_notifications_type】——与 V116 / V117 同一条约束，须一并认领。
--    依产品【临时授权】（HEX-SIGNOFF §临时授权）照推。
-- 🔴 沿用 V97 的处置：重列全集，不在旧列表上想当然地追加。

ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type CHECK (type IN (
    'VET_REPLY', 'CONSULT_CLOSED', 'CONTENT_LIKED', 'CONTENT_COMMENTED', 'NEW_CONSULT_REQUEST',
    'PET_BIRTHDAY', 'COMPANION_ANNIVERSARY', 'MILESTONE_NODE', 'CONTENT_REMOVED', 'REPORT_REVIEWED',
    'CONTENT_REVIEW_APPROVED', 'CONTENT_REVIEW_REJECTED',
    'NAME_RESET', 'AVATAR_RESET', 'CONTENT_REVIEW_TIMED_OUT',
    'REFUND_REJECTED', 'TICKET_RESOLVED', 'CSAT_SURVEY', 'IDENTITY_REQUIRE_MODIFY',
    'SHOP_ORDER_SHIPPED', 'SHOP_ORDER_EXCEPTION',
    -- V1.4.0 Epic 5：退货进度更新（审核 / 质检 / 退款完成共用）
    'SHOP_RETURN_UPDATED'));
