package com.tailtopia.shop.order.service;

import com.tailtopia.shop.coupon.domain.CouponReturnStatus;
import com.tailtopia.shop.coupon.service.CouponLedgerService;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.dto.ShopOrderDetailView;
import com.tailtopia.shop.order.refund.ShopOrderRefundService;
import com.tailtopia.shop.service.ShopLineImageResolver;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单详情装配（v1.4.2 shop Story 6.2 AC3，D-19）：取代 {@code MeCheckoutController#detailOf} 的拼装，详情 / 取消 /
 * 确认收货后返回详情的路径都经这里。
 *
 * <p>🔴 <b>单次详情查询数恒定</b>：行 1、主图与行 token（SKU + 商品，Story 3.4 起同批取）1、运单 1、意图 1、退款单 1、重下单关联至多 2、券退回 1，
 * 与行数 / 退款单数无关。只搬运订单头快照与退款单金额，不重算任何金额（P-1）；券状态只经 {@link CouponLedgerService}（P-2）。
 */
@Service
public class ShopOrderDetailAssembler {

    /** 有取消原因且已进入这些状态才算「已取消订单」（宽限期待支付尚未关单，券仍锁着，不判退回）。 */
    static final Set<ShopOrderStatus> CANCELLED_LIKE =
            EnumSet.of(ShopOrderStatus.CANCELLED, ShopOrderStatus.REFUNDING, ShopOrderStatus.REFUNDED);

    private final ShopOrderPaymentService payments;
    private final ShopOrderFulfillmentService fulfillment;
    private final ShopLineImageResolver lineImages;
    private final ShopOrderRefundService refunds;
    private final LatePaymentService latePayments;
    private final CouponLedgerService coupons;

    public ShopOrderDetailAssembler(ShopOrderPaymentService payments, ShopOrderFulfillmentService fulfillment,
            ShopLineImageResolver lineImages, ShopOrderRefundService refunds, LatePaymentService latePayments,
            CouponLedgerService coupons) {
        this.payments = payments;
        this.fulfillment = fulfillment;
        this.lineImages = lineImages;
        this.refunds = refunds;
        this.latePayments = latePayments;
        this.coupons = coupons;
    }

    @Transactional(readOnly = true)
    public ShopOrderDetailView assemble(ShopOrder order, Instant now) {
        List<ShopOrderLine> lines = payments.linesOf(order);
        ShopOrderRefundService.DetailRefunds r = refunds.detailRefundsOf(order);
        // analytics-pipeline Story 3.4：主图与商品 / SKU token 同一次取（SKU + 商品 两次查询，与原先相同）
        Map<Long, ShopLineImageResolver.LineRefs> refs =
                lineImages.lineRefsBySkuId(lines.stream().map(ShopOrderLine::getSkuId).toList());
        Map<Long, String> imageUrls = new HashMap<>();
        Map<Long, ShopOrderDetailView.LineTokens> tokens = new HashMap<>();
        refs.forEach((skuId, ref) -> {
            if (ref.mainImageUrl() != null) {
                imageUrls.put(skuId, ref.mainImageUrl());
            }
            tokens.put(skuId, new ShopOrderDetailView.LineTokens(ref.productToken(), ref.skuToken()));
        });
        return ShopOrderDetailView.of(order, lines, fulfillment.shipmentsOf(order.getId()), imageUrls,
                payments.intentOf(order).orElse(null), now, r.views(), latePayments.linksOf(order),
                new ShopOrderDetailView.Extras(r.lineRefundTotals(), couponReturnOf(order, now), tokens));
    }

    private String couponReturnOf(ShopOrder order, Instant now) {
        if (order.getCancelReason() == null || !CANCELLED_LIKE.contains(order.getStatus())) {
            return null;
        }
        List<Long> ids = Arrays.asList(order.getDiscountUserCouponId(), order.getFreeShippingUserCouponId());
        if (ids.stream().allMatch(java.util.Objects::isNull)) {
            return null;
        }
        CouponReturnStatus s = coupons.couponReturnStatusOf(order.getId(), ids,
                order.getCancelReason().couponReleaseMode(), now);
        return s == null ? null : s.name();
    }
}
