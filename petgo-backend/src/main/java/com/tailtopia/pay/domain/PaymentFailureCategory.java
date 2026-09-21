package com.tailtopia.pay.domain;

import java.util.Map;

/**
 * 支付失败类别（Story 1-1 · AD-S9(a)）：把「为什么没付成」归成三类下发给 App，
 * 让用户不用对着一个永远不会成功的二维码干等。
 *
 * <p>🔴 <b>判序是「先 status 后 reason」，写反会稳定误判超时为网关拒付</b>。
 * {@link PaymentStatus#EXPIRED} 是独立终态，四个置 EXPIRED 的点 meta 全传 null，
 * 随后订单侧的 {@code failByToken} 又因「已终态即 no-op」写不进 reason ——
 * 所以「EXPIRED 且 meta 无 reason」是常态而非边角。若先看 reason 再兜底成
 * {@code GATEWAY_DECLINED}，App 就会给出一个不该给的重试入口。
 *
 * <p>🔒 <b>本类不暴露 {@code gateway_meta} 原文的任何片段</b>——它是网关回调的 rawMeta，
 * 字段构成由第三方决定，随时可能多出持卡人姓名、手机号一类内容。既不进对外 DTO，也不进日志。
 */
public enum PaymentFailureCategory {

    /** 网关拒付（含未知失败原因）。可引导用户换支付方式重试。 */
    GATEWAY_DECLINED,

    /** 付款窗超时。二维码已作废，只能重新下单。 */
    EXPIRED,

    /** 用户自己取消。不是故障，不提示重试。 */
    USER_CANCELLED;

    private static final String META_KEY_REASON = "reason";
    private static final String REASON_TIMEOUT = "TIMEOUT";
    private static final String REASON_USER_CANCEL = "USER_CANCEL";
    private static final String REASON_CANCELLED = "CANCELLED";

    /**
     * 按支付意图归类失败原因。非失败态（含 {@code null} 意图）一律返回 {@code null}。
     *
     * <p>映射表（判序自上而下，命中即返回）：
     * <table>
     *   <tr><td>{@code intent == null}</td><td>{@code null}</td></tr>
     *   <tr><td>{@code PENDING} / {@code PAID}</td><td>{@code null}</td></tr>
     *   <tr><td>{@code EXPIRED}</td><td>{@link #EXPIRED}（<b>不看 meta</b>）</td></tr>
     *   <tr><td>{@code FAILED} + reason {@code TIMEOUT}</td><td>{@link #EXPIRED}</td></tr>
     *   <tr><td>{@code FAILED} + reason {@code USER_CANCEL} / {@code CANCELLED}</td>
     *       <td>{@link #USER_CANCELLED}</td></tr>
     *   <tr><td>{@code FAILED} + 其它（含 meta 为 null / 无 reason 键）</td>
     *       <td>{@link #GATEWAY_DECLINED}</td></tr>
     * </table>
     */
    public static PaymentFailureCategory of(PaymentIntent intent) {
        if (intent == null) {
            return null;
        }
        PaymentStatus status = intent.getStatus();
        if (status == null || status == PaymentStatus.PENDING || status == PaymentStatus.PAID) {
            return null;
        }
        if (status == PaymentStatus.EXPIRED) {
            return EXPIRED;
        }
        // 此处只剩 FAILED，再看 reason。
        String reason = reasonOf(intent.getGatewayMeta());
        if (REASON_TIMEOUT.equals(reason)) {
            return EXPIRED;
        }
        if (REASON_USER_CANCEL.equals(reason) || REASON_CANCELLED.equals(reason)) {
            return USER_CANCELLED;
        }
        return GATEWAY_DECLINED;
    }

    /**
     * 从 meta 里取 {@code reason}。逐字面量、区分大小写匹配——不做 {@code contains} 或忽略大小写的
     * 模糊匹配：网关 rawMeta 里任何一个碰巧含 "cancel" 的字段值都会把拒付误判成用户取消。
     *
     * @return reason 的字符串形态；meta 为 null、无该键或值为 null 时返回 {@code null}
     */
    private static String reasonOf(Map<String, Object> gatewayMeta) {
        if (gatewayMeta == null) {
            return null;
        }
        Object raw = gatewayMeta.get(META_KEY_REASON);
        return raw == null ? null : String.valueOf(raw);
    }
}
