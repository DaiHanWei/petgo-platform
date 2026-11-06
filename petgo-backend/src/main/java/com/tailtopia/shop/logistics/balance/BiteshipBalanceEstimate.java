package com.tailtopia.shop.logistics.balance;

import java.util.List;

/**
 * Biteship 预估余额纯函数（V1.4.2 shop-admin Story 4.1 · AD-13 · G13 / G17）。
 *
 * <p>预估 = 最新快照 {@code amount} − Σ(此后创建、{@code provider = BITESHIP}、<b>未取消</b>运单的
 * {@code carrier_cost + insurance_cost})。{@code price-includes-insurance = true} 时运费已含保价，只扣 {@code carrier_cost}。
 * 🔴 {@code insurance_amount}（申报保价额）不是钱，不进入本函数。从未录入 → {@link Result#recorded()} = false，不预警。
 */
public final class BiteshipBalanceEstimate {

    private BiteshipBalanceEstimate() {
    }

    /** 一张快照之后创建的 Biteship 运单（调用方只取 {@code provider = BITESHIP}）。 */
    public record Waybill(long carrierCost, long insuranceCost, boolean cancelled) {
    }

    /**
     * @param recorded   是否录入过余额
     * @param lastAmount 上次录入的余额（未录入为 0）
     * @param count      扣除的运单数（不含已取消）
     * @param deducted   扣除合计（≥ 0）
     * @param estimate   预估余额（可为负：实际扣的比录入的多）
     */
    public record Result(boolean recorded, long lastAmount, int count, long deducted, long estimate) {

        static final Result NOT_RECORDED = new Result(false, 0, 0, 0, 0);

        /** 低于阈值才预警：须已录入且阈值 &gt; 0（0 = 不预警），严格小于。 */
        public boolean below(long threshold) {
            return recorded && threshold > 0 && estimate < threshold;
        }
    }

    public static Result estimate(Long lastAmount, List<Waybill> since, boolean priceIncludesInsurance) {
        if (lastAmount == null) {
            return Result.NOT_RECORDED;
        }
        int count = 0;
        long deducted = 0;
        for (Waybill w : since == null ? List.<Waybill>of() : since) {
            if (w == null || w.cancelled()) {
                continue;
            }
            count++;
            deducted = Math.addExact(deducted, Math.max(0, w.carrierCost()));
            if (!priceIncludesInsurance) {
                deducted = Math.addExact(deducted, Math.max(0, w.insuranceCost()));
            }
        }
        return new Result(true, lastAmount, count, deducted, Math.subtractExact(lastAmount, deducted));
    }
}
