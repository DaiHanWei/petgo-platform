-- 取消 DANA 支付渠道（2026-07-13 产品决策：不做 DANA 支付，仅留 QRIS 现金 + PawCoin 站内余额）。
-- 收紧 pay_channel / channel / unlock_channel 的 CHECK 约束，从 IN 列表移除 'DANA'——枚举
-- PayChannel/UnlockChannel 已删 DANA（任何行不再产生此值），本迁移让 DB 层也字面拒绝（防御纵深）。
--
-- 新起 ALTER 不改旧迁移（决策 E2：已提交迁移冻结）。全新应用时这些表在同一迁移序列里刚建、尚无数据，
-- 收紧安全；DANA 从未在生产写入（Midtrans 未接、V1.1 未上线）。DROP + 重建同名约束（约束名不变，便于对账）。

ALTER TABLE payment_intents DROP CONSTRAINT ck_payment_intents_channel;
ALTER TABLE payment_intents ADD CONSTRAINT ck_payment_intents_channel
    CHECK (channel IN ('QRIS', 'PAWCOIN'));

ALTER TABLE triage_tasks DROP CONSTRAINT ck_triage_tasks_unlock_channel;
ALTER TABLE triage_tasks ADD CONSTRAINT ck_triage_tasks_unlock_channel
    CHECK (unlock_channel IS NULL OR unlock_channel IN ('QRIS', 'PAWCOIN'));

ALTER TABLE ai_consult_orders DROP CONSTRAINT ck_ai_consult_orders_channel;
ALTER TABLE ai_consult_orders ADD CONSTRAINT ck_ai_consult_orders_channel
    CHECK (pay_channel IN ('QRIS', 'PAWCOIN'));

ALTER TABLE consult_orders DROP CONSTRAINT ck_consult_orders_channel;
ALTER TABLE consult_orders ADD CONSTRAINT ck_consult_orders_channel
    CHECK (pay_channel IS NULL OR pay_channel IN ('QRIS', 'PAWCOIN'));
