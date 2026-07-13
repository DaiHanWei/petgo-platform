package com.tailtopia.consult.dto;

import com.tailtopia.consult.domain.VetSettlement;
import java.util.List;

/**
 * 兽医收入视图（Story 3.7，{@code GET /api/v1/vet/income}）。到手金额恒读订单 {@code vet_payout} 快照
 * （成交冻结，后台改价不影响历史）。金额 bigint IDR。
 *
 * <ul>
 *   <li>{@code currentMonth}：本 WIB 月 <b>待结算</b>实时聚合（本月 COMPLETED 订单，尚未跑月结，status=PENDING）。</li>
 *   <li>{@code history}：已生成 {@code vet_settlements} 历史行，按 period 倒序。</li>
 * </ul>
 */
public record VetIncomeResponse(VetIncomePeriodItem currentMonth, List<VetIncomePeriodItem> history) {

    /** 收入周期项：一个月的聚合（当月待结算 或 历史月结）。 */
    public record VetIncomePeriodItem(String period, long orderCount, long grossAmount,
            long payoutAmount, String status) {

        /** 当月待结算（实时聚合，status 恒 PENDING）。空聚合 → 零值。 */
        public static VetIncomePeriodItem currentMonth(String period, VetPayoutAggregate agg) {
            return agg == null
                    ? new VetIncomePeriodItem(period, 0, 0, 0, "PENDING")
                    : new VetIncomePeriodItem(period, agg.orderCount(), agg.grossAmount(),
                            agg.payoutAmount(), "PENDING");
        }

        /** 历史月结（已生成行）。 */
        public static VetIncomePeriodItem ofSettlement(VetSettlement s) {
            return new VetIncomePeriodItem(s.getPeriod(), s.getOrderCount(), s.getGrossAmount(),
                    s.getPayoutAmount(), s.getStatus());
        }
    }
}
