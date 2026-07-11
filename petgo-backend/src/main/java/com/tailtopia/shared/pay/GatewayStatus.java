package com.tailtopia.shared.pay;

/**
 * 网关侧支付结果（Story 1.1）。基础设施层枚举，<b>不引业务领域枚举</b>（保持 shared 与 pay 模块解耦）；
 * 由 {@code PaymentIntentService} 映射到领域 {@code PaymentStatus} 并推进意图状态机。
 *
 * <p>{@link #fromMidtrans(String)} 把 Midtrans {@code transaction_status} 收敛到四态（stub 沿用同字段名，
 * 故桩/实共用一套映射）。
 */
public enum GatewayStatus {
    /** 已收款（Midtrans {@code settlement} / {@code capture[accept]}）。 */
    PAID,
    /** 待支付（{@code pending}）——尚未终态，不推进意图。 */
    PENDING,
    /** 失败/拒绝/取消（{@code deny} / {@code cancel} / {@code failure} / {@code refund} 等）。 */
    FAILED,
    /** 过期（{@code expire}）。 */
    EXPIRED;

    /** 映射 Midtrans {@code transaction_status}（大小写不敏感；未知一律保守判 PENDING，不推进）。 */
    public static GatewayStatus fromMidtrans(String transactionStatus) {
        if (transactionStatus == null) {
            return PENDING;
        }
        return switch (transactionStatus.trim().toLowerCase()) {
            case "settlement", "capture" -> PAID;
            case "deny", "cancel", "failure", "refund", "partial_refund", "chargeback" -> FAILED;
            case "expire" -> EXPIRED;
            default -> PENDING; // pending / authorize / 未知 → 不终态
        };
    }
}
