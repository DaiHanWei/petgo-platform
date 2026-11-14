-- V1.5.0 gift-pack Story 1-3（架构 §3.2 · D-5）：送礼祝福语 + 拆开状态 + 祝福语隐藏（举报处理，Story 1-5 写）。
-- · 「未拆」按笔记：opened_at 为空 = 未拆；拆开只写 opened_at（不入账，D-7）。
-- · 上线前的存量订单一律视为已拆（PRD A §6，11-10）。
ALTER TABLE gift_orders ADD COLUMN message           VARCHAR(500) NULL;
ALTER TABLE gift_orders ADD COLUMN opened_at         TIMESTAMPTZ  NULL;
ALTER TABLE gift_orders ADD COLUMN message_hidden_at TIMESTAMPTZ  NULL;
ALTER TABLE gift_orders ADD CONSTRAINT ck_gift_orders_message_len
    CHECK (message IS NULL OR char_length(message) BETWEEN 1 AND 500);
-- 存量一律视为已拆（PRD A §6，11-10）
UPDATE gift_orders SET opened_at = COALESCE(delivered_at, created_at) WHERE opened_at IS NULL;
CREATE INDEX ix_gift_orders_pet_delivered ON gift_orders (pet_id, delivered_at DESC, id DESC) WHERE status = 'DELIVERED';
CREATE INDEX ix_gift_orders_pet_unopened  ON gift_orders (pet_id) WHERE status = 'DELIVERED' AND opened_at IS NULL;
