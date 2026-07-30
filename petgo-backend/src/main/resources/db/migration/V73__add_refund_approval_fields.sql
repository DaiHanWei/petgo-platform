-- Story 4.6（V1.1 Epic 4）：退款第二段审批闭环留痕字段。接 4-4 的 V72 顺延 V73（决策 E2 单调顺延）。
-- V71 建 refund_requests 时第二段仅 approval_status/approver_admin_id/payer_admin_id；本 story 加备注/凭证/时间戳。
-- 全 nullable（无回填）：approval_note（主管通过必填于应用层）、reject_reason（驳回必填）、
-- payment_proof（财务 Iris 出款凭证 disbursementRef，**非 PII**）、approved_at/rejected_at/paid_at。
-- VARCHAR≥16 无 CHAR(1) 坑。冻结迁移新起 ALTER（不改 V71）。
ALTER TABLE refund_requests
    ADD COLUMN approval_note  VARCHAR(500),
    ADD COLUMN reject_reason  VARCHAR(500),
    ADD COLUMN payment_proof  VARCHAR(128),
    ADD COLUMN approved_at    TIMESTAMPTZ,
    ADD COLUMN rejected_at    TIMESTAMPTZ,
    ADD COLUMN paid_at        TIMESTAMPTZ;
