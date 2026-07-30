package com.tailtopia.pay.dto;

import com.tailtopia.pay.domain.PawCoinTransaction;
import java.time.Instant;

/**
 * PawCoin 流水项（Story 1.4，余额与流水页）。<b>只暴露展示字段</b>——{@code delta}（+入账/-消费）、
 * {@code type}（TOPUP/SPEND/REFUND/BONUS，前端按 code 本地化）、{@code refType}（消费来源类别，
 * 非可枚举 id）、{@code createdAt}。
 *
 * <p>护栏：<b>绝不暴露 {@code id}/{@code refId}/{@code entryGroup}</b>——refId 是内部可枚举关联 id
 * （架构护栏），entryGroup 是对账 UUID，均属内部字段。
 *
 * @param delta     金额（+入账 / -消费，IDR 最小单位 = koin，1 koin=Rp1）
 * @param type      流水类型枚举名
 * @param refType   来源类别（如 PAYMENT_INTENT / AI_UNLOCK 等，可空）
 * @param createdAt 发生时间（UTC）
 */
public record PawCoinTxnItem(long delta, String type, String refType, Instant createdAt) {

    public static PawCoinTxnItem from(PawCoinTransaction t) {
        return new PawCoinTxnItem(t.getDelta(), t.getType().name(), t.getRefType(), t.getCreatedAt());
    }
}
