package com.tailtopia.shared.pay;

import java.util.Map;

/**
 * 出款结果（Story 4.6）。对称 {@link ChargeResult}（收款）。{@code disbursementRef} = 网关出款单号
 * （存 {@code refund_requests.payment_proof} 留痕，非 PII）；{@code status} 归一化（{@code COMPLETED}/{@code PROCESSING}/
 * {@code FAILED}）。{@code rawMeta} 脱敏快照（绝不含 PII/凭证）。
 *
 * @param disbursementRef 网关出款单号（对账/留痕，非 PII）
 * @param status          归一化状态（COMPLETED/PROCESSING/FAILED）
 * @param rawMeta         脱敏原始快照
 */
public record DisburseResult(String disbursementRef, String status, Map<String, Object> rawMeta) {

    public boolean isCompleted() {
        return "COMPLETED".equals(status);
    }
}
