-- V1.4.0 精选自营电商 · Story 4.2 —— 发货通知类型（FR-38 深链第八类目标）。
-- 工作线：V1.4.0 电商 · 独占号段 V101–V139。
--
-- ⚠️⚠️ 【触碰共享 CHECK：ck_notifications_type】——须在群里认领。
--    并行契约 §P-1 列出的两个共享 CHECK 之一（另一个 ck_payment_intents_channel 已由 3-3 用掉）。
--    产品【临时授权】生效（HEX-SIGNOFF §临时授权，点名了 channel 与 notifications_type），
--    故本迁移照推；补签时须与 3-3 / 3-8 一并认领。
--
-- 🔴 沿用 V97 的处置：本约束曾被两条工作线各自 DROP+ADD 而丢值（审核通知全断）。
--    因此这里【重列全集】而不是想当然地在某个旧列表上追加 —— 值必须取自 V97 的权威列表 + 本次新增。

ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type CHECK (type IN (
    'VET_REPLY', 'CONSULT_CLOSED', 'CONTENT_LIKED', 'CONTENT_COMMENTED', 'NEW_CONSULT_REQUEST',
    'PET_BIRTHDAY', 'COMPANION_ANNIVERSARY', 'MILESTONE_NODE', 'CONTENT_REMOVED', 'REPORT_REVIEWED',
    'CONTENT_REVIEW_APPROVED', 'CONTENT_REVIEW_REJECTED',
    'NAME_RESET', 'AVATAR_RESET', 'CONTENT_REVIEW_TIMED_OUT',
    'REFUND_REJECTED', 'TICKET_RESOLVED', 'CSAT_SURVEY', 'IDENTITY_REQUIRE_MODIFY',
    -- V1.4.0 Story 4.2：电商订单已发货 → 深链直跳订单详情
    'SHOP_ORDER_SHIPPED'));
