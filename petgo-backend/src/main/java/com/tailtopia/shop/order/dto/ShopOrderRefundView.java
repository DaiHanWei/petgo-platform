package com.tailtopia.shop.order.dto;

import com.tailtopia.shop.order.refund.ShopOrderRefund;
import java.time.Instant;

/**
 * 取消退款单出参（v1.4.2 shop Story 4.1 AC4.6 / AC6.1，C-9）：订单详情 {@code refunds[]} 的条目与选择去向接口的响应同一结构。
 *
 * <p>🔒 收款账号<b>只给末 4 位</b>（{@code payoutAccountLast4}），户名与全号不下发；内部 id（订单行 id 等）不出现。
 * 金额全部是服务端值，App 只展示不自算。
 *
 * @param compensate            该原因是否给补偿（= {@code CancelReason.compensate()}）
 * @param compensationIfPawcoin 现金段选退为 PawCoin 时将发放的补偿（服务端按当前配置预估；无补偿 / 不可选为 0）
 * @param compensationAmount    已发放补偿（选 PawCoin 完成后）
 */
public record ShopOrderRefundView(
        String refundToken,
        String scope,
        String reason,
        String status,
        long totalRefund,
        long coinRefund,
        long cashRefund,
        long compensationAmount,
        long payoutFee,
        String payoutChannel,
        String payoutAccountLast4,
        boolean canChooseDestination,
        boolean payoutFailed,
        Instant paidOutAt,
        Instant createdAt,
        String cashDestination,
        Instant destinationChosenAt,
        boolean compensate,
        long compensationIfPawcoin) {

    public static ShopOrderRefundView of(ShopOrderRefund r, long compensationIfPawcoin) {
        return new ShopOrderRefundView(
                r.getPublicToken(),
                r.getScope().name(),
                r.getReason().name(),
                r.getStatus().name(),
                r.getTotalRefund(),
                r.getCoinRefund(),
                r.getCashRefund(),
                r.getCompensationAmount(),
                r.getPayoutFee(),
                r.getPayoutChannel() == null ? null : r.getPayoutChannel().name(),
                r.payoutAccountLast4(),
                r.getStatus().canChooseDestination(),
                r.getStatus() == com.tailtopia.shop.order.refund.ShopRefundStatus.PAYOUT_FAILED,
                r.getPaidOutAt(),
                r.getCreatedAt(),
                r.getCashDestination() == null ? null : r.getCashDestination().name(),
                r.getDestinationChosenAt(),
                r.getReason().compensate(),
                compensationIfPawcoin);
    }
}
