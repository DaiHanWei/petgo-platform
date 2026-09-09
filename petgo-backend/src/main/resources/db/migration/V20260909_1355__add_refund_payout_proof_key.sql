-- V1.3.0 后台线 Story 2.8（D-36）：财务打款附出款凭证——走既有对象存储（私密桶），退款单只存 objectKey，不落 URL。
-- 展示层按 objectKey 现签短 TTL URL（SignedUrlService）；审计 detail 只记 objectKey。
ALTER TABLE refund_requests ADD COLUMN payout_proof_key VARCHAR(255);
COMMENT ON COLUMN refund_requests.payout_proof_key IS '出款凭证对象 key（私密桶，Story 2.8 D-36）；可空，展示时现签 URL';
