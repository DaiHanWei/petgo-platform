package com.tailtopia.shared.logistics.biteship.dto;

import java.util.List;

/**
 * 询价结果（v1.4.2 shop Story 1.1）：有报价（{@code rates} 非空）或不可配送（{@code rates} 为空，
 * {@code noCourierCode} 记 Biteship 原码，可空）。询价失败不走本类，抛
 * {@link com.tailtopia.shared.logistics.biteship.BiteshipException}。
 */
public record BiteshipRatesResult(List<BiteshipRate> rates, Integer noCourierCode) {

    public BiteshipRatesResult {
        rates = rates == null ? List.of() : List.copyOf(rates);
    }

    public static BiteshipRatesResult of(List<BiteshipRate> rates) {
        return new BiteshipRatesResult(rates, null);
    }

    public static BiteshipRatesResult noCourier(Integer code) {
        return new BiteshipRatesResult(List.of(), code);
    }

    public boolean serviceable() {
        return !rates.isEmpty();
    }
}
