-- V1.4.0 精选自营电商 · Story 4.4 —— 异常订单处置的站内信类型（AB-11D，S-3）。
-- 工作线：V1.4.0 电商 · 独占号段 V101–V139。
--
-- ⚠️⚠️ 【触碰共享 CHECK：ck_notifications_type】——与 V116 同一条约束，须一并认领。
--    依产品【临时授权】（HEX-SIGNOFF §临时授权）照推。
--
-- 🔴 为什么不与 V116 合并：V116 已在本地库应用，改它会让 Flyway 校验和失配。
--    「合并时不重排号、不改已应用的迁移」是本工作线的既定纪律（决策 E2 / sprint-status §Flyway）。
--
-- 🔴 沿用 V97 / V116 的处置：重列全集，不在旧列表上想当然地追加。

ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type CHECK (type IN (
    'VET_REPLY', 'CONSULT_CLOSED', 'CONTENT_LIKED', 'CONTENT_COMMENTED', 'NEW_CONSULT_REQUEST',
    'PET_BIRTHDAY', 'COMPANION_ANNIVERSARY', 'MILESTONE_NODE', 'CONTENT_REMOVED', 'REPORT_REVIEWED',
    'CONTENT_REVIEW_APPROVED', 'CONTENT_REVIEW_REJECTED',
    'NAME_RESET', 'AVATAR_RESET', 'CONTENT_REVIEW_TIMED_OUT',
    'REFUND_REJECTED', 'TICKET_RESOLVED', 'CSAT_SURVEY', 'IDENTITY_REQUIRE_MODIFY',
    'SHOP_ORDER_SHIPPED',
    -- V1.4.0 Story 4.4：异常订单已处置（取消 / 部分取消 / 联系后继续）→ 深链订单详情
    'SHOP_ORDER_EXCEPTION'));
