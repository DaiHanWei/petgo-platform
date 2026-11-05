package com.tailtopia.shared.pay;

import java.util.Objects;

/**
 * 主动查单三态结果（v1.4.2 shop Story 3.3 AC5.1，决策 I2）。
 *
 * <p>🔴 <b>分清「查到了，没付」与「没查到」</b>：原 {@code Optional<PaymentCallback>} 把网关错误、空响应、
 * {@code 04 data not found} 与「明确未付」混成一个 {@code empty}，关单若据此判「未付」就会把付了钱的单关掉。
 * <ul>
 *   <li>{@link Outcome#PAID}：网关确认已收款，{@code callback.status == PAID}；</li>
 *   <li>{@link Outcome#NOT_PAID}：网关明确返回了该笔，但未收款；{@code callback.status ∈ {PENDING, FAILED, EXPIRED}}
 *       —— 只有 FAILED / EXPIRED 才可据此关单，PENDING 仍是「未决」；</li>
 *   <li>{@link Outcome#UNKNOWN}：查询失败 / 查无结果，{@code callback == null}，<b>不得关单</b>。</li>
 * </ul>
 */
public record ChargeQueryResult(Outcome outcome, PaymentCallback callback) {

    public enum Outcome { PAID, NOT_PAID, UNKNOWN }

    public ChargeQueryResult {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == Outcome.UNKNOWN && callback != null) {
            throw new IllegalArgumentException("UNKNOWN 不带回调");
        }
        if (outcome != Outcome.UNKNOWN && callback == null) {
            throw new IllegalArgumentException(outcome + " 必须带回调");
        }
        if (outcome == Outcome.PAID && callback.status() != GatewayStatus.PAID) {
            throw new IllegalArgumentException("PAID 的回调状态必须为 PAID");
        }
        if (outcome == Outcome.NOT_PAID && callback.status() == GatewayStatus.PAID) {
            throw new IllegalArgumentException("NOT_PAID 的回调状态不能为 PAID");
        }
    }

    public static ChargeQueryResult paid(PaymentCallback cb) {
        return new ChargeQueryResult(Outcome.PAID, cb);
    }

    public static ChargeQueryResult notPaid(PaymentCallback cb) {
        return new ChargeQueryResult(Outcome.NOT_PAID, cb);
    }

    public static ChargeQueryResult unknown() {
        return new ChargeQueryResult(Outcome.UNKNOWN, null);
    }

    /** 由归一化回调定三态：PAID → paid；其余 → notPaid。 */
    public static ChargeQueryResult of(PaymentCallback cb) {
        return cb.status() == GatewayStatus.PAID ? paid(cb) : notPaid(cb);
    }

    /** 网关明确返回了终态未付（FAILED / EXPIRED）—— 关单与推进意图的唯一依据。 */
    public boolean isTerminalNotPaid() {
        return outcome == Outcome.NOT_PAID
                && (callback.status() == GatewayStatus.FAILED || callback.status() == GatewayStatus.EXPIRED);
    }
}
