package com.tailtopia.shop.order.pricing;

import com.tailtopia.pay.refund.domain.PayoutChannel;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.refund.CancelReason;
import com.tailtopia.shop.order.refund.RefundScope;

/**
 * 取消退款金额的<b>唯一实现</b>（v1.4.2 shop Story 4.1 AC2，架构 D-15 / P-1 / m2）。纯函数：无 IO、无 Spring、无时钟。
 *
 * <p>🔴 <b>币段拆分 = 每次 ⌊本次 × C / T⌋，封顶剩余币；本次退到剩余全部时取剩余币</b>（零头入现金，末笔精确归零）。
 * 刻意<b>不复用</b>退货线的累计法 {@code RefundSplit.accumulate}（两者首笔相同、多笔中间单笔可能差 1 盾，story 拍板点 ①）。
 *
 * <p>🔴 <b>PawCoin 段只能退回 PawCoin</b>：本类只算两段金额，不存在把币段折成现金的方法（能力缺席，FR-100A 规则 1）。
 *
 * <p>乘法一律 {@link Math#multiplyExact}，溢出即抛，不静默截断。
 */
public final class ShopRefundCalculator {

    private ShopRefundCalculator() {
    }

    // ---------- 部分取消 ----------

    /**
     * 部分取消一行的若干件（AC2.2）。取消后全单已无剩余件 → 按整单（{@link #whole}）处理并标 {@code becameWhole}。
     *
     * @param reason 只接受平台原因中「全额退剩余」的四类（{@code OUT_OF_STOCK / WAYBILL_FAILED / PICKUP_FAILED / COURIER_FAULT}）
     * @throws AppException 409 取消数量超过可取消数量；404 行不存在；422 原因不适用
     */
    public static RefundQuote partial(RefundOrderSnapshot s, long lineId, int cancelQty, CancelReason reason) {
        if (!isFullRemainingReason(reason)) {
            throw AppException.validation("部分取消只适用于平台原因").code("admin.err.orderRefund.reasonNotAllowed");
        }
        RefundOrderSnapshot.RefundLine line = s.lines().stream()
                .filter(l -> l.lineId() == lineId)
                .findFirst()
                .orElseThrow(() -> AppException.notFound("订单行不存在").code("admin.err.order.lineNotFound"));
        int cancellable = line.qty() - line.refundedQty();
        if (cancelQty < 1 || cancelQty > cancellable) {
            throw AppException.conflict("取消数量超过可取消数量").code("admin.err.orderRefund.qtyExceeded");
        }
        if (s.remainingQty() == cancelQty) {
            // 最后一件按整单处理：退剩余未退应付（含运费、保价），剩余币全退
            RefundQuote w = whole(s, reason, null);
            return new RefundQuote(RefundScope.WHOLE, null, null, reason, w.goodsRefund(), w.shippingRefund(),
                    w.insuranceRefund(), w.totalRefund(), w.coinRefund(), w.cashRefund(), true);
        }
        long lineAmount = Math.multiplyExact(line.unitPrice(), (long) cancelQty);
        long eligible = s.eligibleAmount();
        long share = line.discountEligible() && eligible > 0
                ? Math.floorDiv(Math.multiplyExact(s.discount(), lineAmount), eligible)
                : 0L;
        long total = Math.subtractExact(lineAmount, share);
        if (total <= 0) {
            throw nothingToRefund();
        }
        // 比例向下取整后施加下限：本次现金段不得超过剩余未退现金（T − C − (RT − RC)），
        // 否则近纯币单多次部分取消会把币的零头累计成现金退出、最后一件剩余币大于剩余应付（复审修复）
        long remainingCash = Math.max(0L, Math.subtractExact(Math.subtractExact(s.total(), s.coin()),
                Math.subtractExact(s.refundedTotal(), s.refundedCoin())));
        long coin = Math.max(proportionalCoin(s, total), Math.subtractExact(total, remainingCash));
        coin = Math.max(0L, Math.min(coin, Math.min(s.remainingCoin(), total)));
        return new RefundQuote(RefundScope.LINE, lineId, cancelQty, reason, total, 0L, 0L, total, coin,
                Math.subtractExact(total, coin), false);
    }

    // ---------- 整单 ----------

    /**
     * 整单（AC2.3），{@code remaining = T − RT}：
     * <ul>
     *   <li>平台原因（缺货 / 运单失败 / 揽收失败 / 快递原因）：{@code remaining}，含实付运费与保价费；</li>
     *   <li>{@code USER_FAULT_RETURNED}：{@code max(0, (G − D) − RT)}（扣实付运费与保价费）；</li>
     *   <li>{@code LOST_DAMAGED}：已保价 = {@code max(0, (G − D) − RT)}；未保价 = {@code max(0, N − RT)}，
     *       {@code N} 必填且 {@code 0 ≤ N ≤ G − D}。</li>
     * </ul>
     *
     * @param negotiatedAmount 未保价丢件破损的运营协商额（这单总共赔多少），其余原因忽略
     * @throws AppException 409 已无可退金额；422 协商额缺失或越界 / 原因不生成退款单
     */
    public static RefundQuote whole(RefundOrderSnapshot s, CancelReason reason, Long negotiatedAmount) {
        return wholeOf(s, reason, negotiatedAmount, RefundScope.WHOLE);
    }

    /** 签收 / 完成后才报破损的赔付（S-9，AC2.4）：金额规则与 {@code LOST_DAMAGED} 完全相同，{@code scope = COMPENSATION}。 */
    public static RefundQuote compensation(RefundOrderSnapshot s, Long negotiatedAmount) {
        return wholeOf(s, CancelReason.LOST_DAMAGED, negotiatedAmount, RefundScope.COMPENSATION);
    }

    private static RefundQuote wholeOf(RefundOrderSnapshot s, CancelReason reason, Long negotiatedAmount,
            RefundScope scope) {
        long remaining = s.remaining();
        if (remaining <= 0) {
            throw nothingToRefund();
        }
        long total;
        long shipping = 0L;
        long insurance = 0L;
        switch (reason) {
            case OUT_OF_STOCK, WAYBILL_FAILED, PICKUP_FAILED, COURIER_FAULT -> {
                total = remaining;
                shipping = Math.min(s.shippingPaid(), total);
                insurance = Math.min(Math.max(0L, s.insuranceFee()), Math.subtractExact(total, shipping));
            }
            case USER_FAULT_RETURNED -> total = Math.max(0L, Math.subtractExact(s.goodsPaid(), s.refundedTotal()));
            case LOST_DAMAGED -> total = lostDamagedTotal(s, negotiatedAmount);
            default -> throw AppException.validation("该取消原因不生成取消退款单")
                    .code("admin.err.orderRefund.reasonNotAllowed");
        }
        total = Math.min(total, remaining);
        if (total <= 0) {
            // 不建 0 元退款单（例：用户原因 / 丢件破损的商品实付已被此前部分取消退完）
            throw nothingToRefund();
        }
        long coin = total == remaining ? s.remainingCoin() : proportionalCoin(s, total);
        coin = Math.min(coin, total);
        long goods = Math.subtractExact(Math.subtractExact(total, shipping), insurance);
        return new RefundQuote(scope, null, null, reason, goods, shipping, insurance, total, coin,
                Math.subtractExact(total, coin), false);
    }

    /** 丢件破损赔付额（整单 {@code LOST_DAMAGED} 与 S-9 赔付共用）。 */
    private static long lostDamagedTotal(RefundOrderSnapshot s, Long negotiatedAmount) {
        long goodsPaid = s.goodsPaid();
        if (s.insuranceSelected()) {
            return Math.max(0L, Math.subtractExact(goodsPaid, s.refundedTotal()));
        }
        if (negotiatedAmount == null || negotiatedAmount < 0 || negotiatedAmount > goodsPaid) {
            throw AppException.validation("未保价订单须填写协商赔付额，且不超过商品实付")
                    .code("admin.err.orderRefund.negotiatedInvalid", goodsPaid);
        }
        return Math.max(0L, Math.subtractExact(negotiatedAmount, s.refundedTotal()));
    }

    // ---------- 补偿 / 渠道费 / 迟到款 ----------

    /**
     * 平台责任补偿（AC2.5）= {@code cashToPawcoin × ratePercent / 100}（向下取整），{@code cap > 0} 时封顶。
     * 🔴 基数是<b>现金段选退为 PawCoin 的金额</b>，不是币段（PRD 11-05）。
     */
    public static long compensationPremium(long cashToPawcoin, int ratePercent, long cap) {
        if (ratePercent <= 0 || cashToPawcoin <= 0) {
            return 0L;
        }
        long premium = Math.multiplyExact(cashToPawcoin, (long) ratePercent) / 100;
        if (cap > 0) {
            premium = Math.min(premium, cap);
        }
        return Math.max(0L, premium);
    }

    /**
     * 线下打款实到金额（AC2.6）= 现金段 − 渠道费（{@link PayoutChannel#fee()}，前端传费一律不采信）。
     *
     * @throws AppException 422 {@code refund-below-payout-fee}：现金段不高于渠道费
     */
    public static long payoutNet(long cash, PayoutChannel channel) {
        if (cash <= channel.fee()) {
            throw AppException.refundBelowPayoutFee("退款金额不足以支付该渠道手续费，请选择其他方式");
        }
        return Math.subtractExact(cash, channel.fee());
    }

    /** 迟到款退款（AC2.7，4.2 使用）：全额现金、无币段，{@code scope = WHOLE}、{@code reason = LATE_PAYMENT}。 */
    public static RefundQuote latePayment(long lateAmount) {
        if (lateAmount <= 0) {
            throw AppException.validation("迟到款金额必须为正").code("admin.err.orderRefund.lateAmountInvalid");
        }
        return new RefundQuote(RefundScope.WHOLE, null, null, CancelReason.LATE_PAYMENT, lateAmount, 0L, 0L,
                lateAmount, 0L, lateAmount, false);
    }

    // ---------- 内部 ----------

    /** 平台原因中「整单全额退剩余（含运费保价）」的四类；部分取消只接受它们。 */
    public static boolean isFullRemainingReason(CancelReason reason) {
        return reason == CancelReason.OUT_OF_STOCK || reason == CancelReason.WAYBILL_FAILED
                || reason == CancelReason.PICKUP_FAILED || reason == CancelReason.COURIER_FAULT;
    }

    private static AppException nothingToRefund() {
        return AppException.nothingToRefund("该订单已无可退金额").code("admin.err.orderRefund.nothingToRefund");
    }

    /** {@code min(⌊total × C / T⌋, C − RC)}，{@code T = 0} 时 0。 */
    private static long proportionalCoin(RefundOrderSnapshot s, long total) {
        if (s.total() <= 0 || s.coin() <= 0 || total <= 0) {
            return 0L;
        }
        long coin = Math.floorDiv(Math.multiplyExact(total, s.coin()), s.total());
        return Math.max(0L, Math.min(coin, s.remainingCoin()));
    }
}
