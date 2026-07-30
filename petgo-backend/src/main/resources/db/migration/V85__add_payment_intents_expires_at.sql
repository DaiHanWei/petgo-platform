-- 充值二维码 60 分钟窗口（2026-07-16 产品需求）：payment_intents 增 expires_at。
--
-- 背景：原表无时间过期，意图仅靠 GemPay 回调/对账变 EXPIRED。充值需「60 分钟内可重复打开同一 QR，
-- 超 60 分钟订单过期」。GemPay QR 有效期 65min > 60min，故窗口内码始终存活，且早死 5min 留安全余量。
--
-- 语义：expires_at 可空——仅 PAWCOIN_TOPUP 意图创建时填 now()+60min；其余 purpose（VET_CONSULT/
-- AI_UNLOCK/ID_HD）留 NULL = 无时间过期，保持既有行为不变。时间戳一律 UTC。

ALTER TABLE payment_intents
    ADD COLUMN expires_at TIMESTAMPTZ;

-- 定时过期扫描按 (status, expires_at) 命中 PENDING 且已过期的行；部分索引仅覆盖待扫描集。
CREATE INDEX idx_payment_intents_pending_expiry
    ON payment_intents (expires_at)
    WHERE status = 'PENDING' AND expires_at IS NOT NULL;
