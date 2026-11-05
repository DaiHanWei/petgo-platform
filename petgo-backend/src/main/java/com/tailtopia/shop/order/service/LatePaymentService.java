package com.tailtopia.shop.order.service;

import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shop.coupon.service.CouponInvalidException;
import com.tailtopia.shop.coupon.service.CouponLedgerService;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.dto.CheckoutUnavailableException;
import com.tailtopia.shop.order.dto.ShopOrderDetailView;
import com.tailtopia.shop.order.dto.UnavailableLine;
import com.tailtopia.shop.order.event.ShopOrderReorderedEvent;
import com.tailtopia.shop.order.pricing.ReorderSettlement;
import com.tailtopia.shop.order.pricing.ShopPricingCalculator;
import com.tailtopia.shop.order.refund.ShopOrderRefund;
import com.tailtopia.shop.order.refund.ShopOrderRefundService;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.service.InventoryService;
import com.tailtopia.shop.service.ShopTokenGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 迟到款处置（v1.4.2 shop Story 4.2，架构 D-11「客服处置」、决策 S-2 / S-6 / I4）：重新下单或退款。
 * 后台线 Controller 与 stag 入口（{@code ShopStagController}）调用本类。
 *
 * <p>🔴 <b>同一笔迟到款只能处理一次</b>：两条路径都在同一事务第一步 {@link ShopOrderRepository#claimLatePayment}
 * 条件抢占处理权（影响 0 行 → 409 {@code late-payment-already-resolved}）；之后任一步失败整个事务回滚，处理权一并释放。
 * 选单一律以订单 {@code shop_orders.late_paid_*} 为准，<b>不看</b> {@code payment_intents.paid_after_close}
 * （宽限期内到账也带该标记，但订单正常履约）。
 *
 * <p>🔴 金额只在计价器（{@link ShopPricingCalculator#reorderSettlement}，P-1）；券只经 {@link CouponLedgerService}（P-2）；
 * 退款金额只在 {@code ShopRefundCalculator}（经 {@link ShopOrderRefundService#createLatePaymentRefund}）。
 * 本类无任何外部调用（网关 / Biteship），不重新询价。
 *
 * <p>🔒 日志只记订单 token、金额；不记地址、手机号。
 */
@Service
public class LatePaymentService {

    private static final Logger log = LoggerFactory.getLogger(LatePaymentService.class);

    static final String RESOLUTION_REORDERED = "REORDERED";
    static final String RESOLUTION_REFUNDED = "REFUNDED";

    /** 多出部分退 PawCoin 的 {@code ref_type}（拍板点 ③）：不计任何订单的 {@code refunded_total}，财务可单独统计。 */
    public static final String REF_TYPE_LATE_EXCESS = "SHOP_LATE_EXCESS";

    private final ShopOrderRepository orders;
    private final ShopOrderLineRepository orderLines;
    private final ShopSkuRepository skus;
    private final InventoryService inventory;
    private final CouponLedgerService coupons;
    private final PawCoinWalletService wallet;
    private final ShopTokenGenerator tokens;
    private final ShopOrderDisplayNoGenerator displayNos;
    private final ShopOrderPaidTransition paidTransition;
    private final ShopOrderRefundService refunds;
    private final AdminAuditService audit;
    private final AccountDeletionRepository accountDeletions;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    public LatePaymentService(ShopOrderRepository orders, ShopOrderLineRepository orderLines, ShopSkuRepository skus,
            InventoryService inventory, CouponLedgerService coupons, PawCoinWalletService wallet,
            ShopTokenGenerator tokens, ShopOrderDisplayNoGenerator displayNos, ShopOrderPaidTransition paidTransition,
            ShopOrderRefundService refunds, AdminAuditService audit, AccountDeletionRepository accountDeletions,
            ApplicationEventPublisher events) {
        this(orders, orderLines, skus, inventory, coupons, wallet, tokens, displayNos, paidTransition, refunds,
                audit, accountDeletions, events, Clock.systemUTC());
    }

    LatePaymentService(ShopOrderRepository orders, ShopOrderLineRepository orderLines, ShopSkuRepository skus,
            InventoryService inventory, CouponLedgerService coupons, PawCoinWalletService wallet,
            ShopTokenGenerator tokens, ShopOrderDisplayNoGenerator displayNos, ShopOrderPaidTransition paidTransition,
            ShopOrderRefundService refunds, AdminAuditService audit, AccountDeletionRepository accountDeletions,
            ApplicationEventPublisher events, Clock clock) {
        this.orders = orders;
        this.orderLines = orderLines;
        this.skus = skus;
        this.inventory = inventory;
        this.coupons = coupons;
        this.wallet = wallet;
        this.tokens = tokens;
        this.displayNos = displayNos;
        this.paidTransition = paidTransition;
        this.refunds = refunds;
        this.audit = audit;
        this.accountDeletions = accountDeletions;
        this.events = events;
        this.clock = clock;
    }

    // ===================== 重新下单（AC2 / AC4） =====================

    /**
     * 用迟到款重新下单（AC2 / AC4）。原单保持 {@code CANCELLED}、{@code late_payment_resolution = REORDERED}；
     * 新单 {@code checkout_source = REORDER}、不挂支付意图，经统一「已付款」路径转 {@code PENDING_SHIPMENT}（I4）。
     *
     * @throws AppException 404 原单不存在；409 {@code late-payment-not-reorderable} / {@code late-payment-already-resolved}
     *         / {@code unavailable-lines} / {@code reorder-coupon-unavailable} / {@code reorder-coin-insufficient}
     */
    @Transactional
    public ReorderOutcome reorderFromLatePayment(String orderToken, Long actorAdminId) {
        ShopOrder o = requireOrder(orderToken);
        if (o.getStatus() != ShopOrderStatus.CANCELLED) {
            // 重复付款单（3.3：已付款订单又被另一意图付了一次）本身在履约，重下单会多发一份货 → 只能退款（拍板点 ④）
            throw AppException.latePaymentNotReorderable("只有已取消的订单可以重新下单，请改为退款")
                    .code("admin.err.latePayment.notReorderable");
        }
        requireNotDeleted(o);
        o = claim(o, RESOLUTION_REORDERED);
        Instant now = clock.instant();

        List<ShopOrderLine> lines = orderLines.findByOrderIdOrderByIdAsc(o.getId());
        requireStock(lines);

        ReorderSettlement s = ShopPricingCalculator.reorderSettlement(o.getTotalAmount(), coinOf(o),
                o.getLatePaidAmount());

        String displayNo = displayNos.generateUnique(now, orders::existsByDisplayNo);
        ShopOrder n = orders.save(ShopOrder.placeReorder(o, tokens.generate(), displayNo, now));

        // ② 订单行逐行复制（原快照）+ 原子锁库存
        for (ShopOrderLine l : lines) {
            ShopOrderLine copy = ShopOrderLine.of(n.getId(), l.getSkuId(), l.getProductName(), l.getSpecName(),
                    l.getUnitPrice(), l.getQty(), l.getReturnPolicy());
            copy.attributeTo(l.getEntrySource(), l.getTriggerType());
            copy.markDiscountEligible(l.isDiscountEligible());
            orderLines.save(copy);
            lockOrUnavailable(l);
        }

        // ③ 原券重新锁到新单（不校验有效期；被占用 / 用掉 / 作废 → 409）
        relockCoupons(o, n, now);

        // ④ 扣币（与 3.2 同键规则：refType 必须 SHOP_ORDER，成长值经 ShopOrderPaidEvent 入账）
        if (s.coinAmount() > 0) {
            try {
                wallet.debit(n.getUserId(), s.coinAmount(), PawCoinTxnType.SPEND, "SHOP_ORDER", n.getId(),
                        "shop-order:" + n.getPublicToken());
            } catch (AppException e) {
                if (ErrorTypes.PAWCOIN_INSUFFICIENT.equals(e.getType())) {
                    throw AppException.reorderCoinInsufficient("用户 PawCoin 余额不足以重新下单，请改为退款")
                            .code("admin.err.latePayment.coinInsufficient");
                }
                throw e;
            }
        }

        // ⑤ 多出部分退为 PawCoin（S-6）：无补偿、不发 RefundSucceededEvent、不调任何订单的 recordRefund
        if (s.excessToPawcoin() > 0) {
            wallet.credit(o.getUserId(), s.excessToPawcoin(), PawCoinTxnType.REFUND, REF_TYPE_LATE_EXCESS,
                    o.getId(), "shop-late-excess:" + o.getPublicToken());
        }

        // ⑥ 拆分固化（取计价器出参）；🔴 新单不挂支付意图
        n.applyPaymentSplit(CheckoutService.channelOf(s.coinAmount(), s.cashAmount()), s.coinAmount(),
                s.cashAmount());

        // ⑦ 统一「已付款」路径：commit 库存、paid_at、券 USED、ShopOrderPaidEvent → PENDING_SHIPMENT
        if (!paidTransition.markPaid(n, PaidSource.REORDER)) {
            throw new IllegalStateException("重下单新单未能转为已付款");
        }

        // ⑧ 审计（不含地址 / 手机号）
        audit.record(actorAdminId, AuditActions.SHOP_ORDER_LATE_PAYMENT_REORDERED, "SHOP_ORDER", o.getPublicToken(),
                "迟到款重新下单 → %s；迟到款 %d；新单币 %d / 现金 %d；退回多余 %d".formatted(n.getDisplayNo(),
                        o.getLatePaidAmount(), s.coinAmount(), s.cashAmount(), s.excessToPawcoin()));

        // ⑨ 通知（AFTER_COMMIT 才发）
        events.publishEvent(new ShopOrderReorderedEvent(o.getId(), n.getId(), o.getUserId(), n.getPublicToken(),
                n.getDisplayNo()));
        log.info("迟到款重新下单 original={} new={} coin={} cash={} excess={}", o.getPublicToken(),
                n.getPublicToken(), s.coinAmount(), s.cashAmount(), s.excessToPawcoin());
        return new ReorderOutcome(n.getPublicToken(), n.getDisplayNo(), s.coinAmount(), s.cashAmount(),
                s.excessToPawcoin());
    }

    // ===================== 退款（AC6） =====================

    /**
     * 迟到款退款（AC6）：原单状态不限（已取消单或 3.3 记录的重复付款单）。生成 {@code LATE_PAYMENT} 退款单
     * （现金 = 迟到款、币 0，待用户选去向）。🔴 不调 {@code recordRefund}、不退券、不改原单状态、不发扣回事件、不发推送。
     * 审计由 {@code createLatePaymentRefund} 记（{@code SHOP_ORDER_REFUND_HANDLED}「迟到款退款」，含退款单 token 与金额）。
     */
    @Transactional
    public ShopOrderRefund refundLatePayment(String orderToken, Long actorAdminId) {
        ShopOrder o = requireOrder(orderToken);
        requireNotDeleted(o);
        o = claim(o, RESOLUTION_REFUNDED);
        ShopOrderRefund refund = refunds.createLatePaymentRefund(o, o.getLatePaidAmount(), actorAdminId);
        log.info("迟到款退款 order={} amount={} refund={}", o.getPublicToken(), o.getLatePaidAmount(),
                refund.getPublicToken());
        return refund;
    }

    // ===================== 订单详情关联（AC7） =====================

    /**
     * 详情关联字段（AC7 / C-9）：本单作为原单 → 新单 token；本单作为新单 → 原单展示号与 token。普通订单不查库。
     */
    @Transactional(readOnly = true)
    public ShopOrderDetailView.ReorderLinks linksOf(ShopOrder order) {
        String toToken = null;
        if (RESOLUTION_REORDERED.equals(order.getLatePaymentResolution())) {
            toToken = orders.findByReorderOfOrderId(order.getId()).map(ShopOrder::getPublicToken).orElse(null);
        }
        String fromNo = null;
        String fromToken = null;
        if (order.getReorderOfOrderId() != null) {
            ShopOrder from = orders.findById(order.getReorderOfOrderId()).orElse(null);
            if (from != null) {
                fromNo = from.getDisplayNo();
                fromToken = from.getPublicToken();
            }
        }
        if (toToken == null && fromNo == null) {
            return ShopOrderDetailView.ReorderLinks.NONE;
        }
        return new ShopOrderDetailView.ReorderLinks(toToken, fromNo, fromToken);
    }

    // ===================== 内部 =====================

    private ShopOrder requireOrder(String orderToken) {
        return orders.findByPublicToken(orderToken).orElseThrow(() -> AppException.notFound("订单不存在"));
    }

    /** 注销用户（D1 / D2）：资产已作废，不再建单 / 记币 / 生成退款单 → 409 线下处理（与 4.1 已付款单口径一致）。 */
    private void requireNotDeleted(ShopOrder o) {
        if (accountDeletions.findByUserId(o.getUserId()).isPresent()) {
            throw AppException.conflict("该用户已注销，请线下处理迟到款").code("admin.err.latePayment.userDeleted");
        }
    }

    /**
     * AC1：条件抢占处理权，随后重读订单（native 更新绕过一级缓存，托管实体的旧 {@code version} 会在后续 flush 时撞乐观锁；
     * 仓库方法 {@code clearAutomatically} 已清掉一级缓存）。
     */
    private ShopOrder claim(ShopOrder o, String resolution) {
        if (orders.claimLatePayment(o.getId(), resolution) == 0) {
            throw AppException.latePaymentAlreadyResolved("该笔迟到款已处理过，或订单没有迟到款")
                    .code("admin.err.latePayment.alreadyResolved");
        }
        return orders.findById(o.getId()).orElseThrow(() -> AppException.notFound("订单不存在"));
    }

    /** AC2.3：逐行（原单行全部，按原 qty；同 SKU 多行合并判断）查可售，不足的逐行收集 → 409 {@code unavailable-lines}。 */
    private void requireStock(List<ShopOrderLine> lines) {
        Map<Long, Long> need = new LinkedHashMap<>();
        for (ShopOrderLine l : lines) {
            need.merge(l.getSkuId(), (long) l.getQty(), Long::sum);
        }
        Map<Long, Long> available = inventory.availableBySkuId(new ArrayList<>(need.keySet()));
        List<ShopOrderLine> shortLines = lines.stream()
                .filter(l -> available.getOrDefault(l.getSkuId(), 0L) < need.get(l.getSkuId()))
                .toList();
        if (shortLines.isEmpty()) {
            return;
        }
        Map<Long, String> tokenBySku = skus.findAllById(shortLines.stream().map(ShopOrderLine::getSkuId)
                        .distinct().toList()).stream()
                .collect(Collectors.toMap(ShopSku::getId, ShopSku::getPublicToken, (a, b) -> a));
        List<UnavailableLine> out = shortLines.stream()
                .map(l -> new UnavailableLine(tokenBySku.get(l.getSkuId()), l.getProductName(), l.getSpecName(),
                        UnavailableLine.REASON_INSUFFICIENT_STOCK,
                        Math.max(available.getOrDefault(l.getSkuId(), 0L), 0L), l.getQty()))
                .toList();
        throw new CheckoutUnavailableException(out);
    }

    /**
     * 原子锁库存；查可售之后、锁之前被别单买走（{@code inventory.lock} 影响 0 行抛通用 409）→ 转成 AC2.3 的
     * {@code unavailable-lines}（带该行明细、可售按 0 报），整个事务随之回滚。
     */
    private void lockOrUnavailable(ShopOrderLine l) {
        try {
            inventory.lock(l.getSkuId(), l.getQty());
        } catch (AppException e) {
            if (!ErrorTypes.CONFLICT.equals(e.getType())) {
                throw e;
            }
            String skuToken = skus.findById(l.getSkuId()).map(ShopSku::getPublicToken).orElse(null);
            throw new CheckoutUnavailableException(List.of(new UnavailableLine(skuToken, l.getProductName(),
                    l.getSpecName(), UnavailableLine.REASON_INSUFFICIENT_STOCK, 0L, l.getQty())));
        }
    }

    private void relockCoupons(ShopOrder o, ShopOrder n, Instant now) {
        try {
            for (Long userCouponId : couponIdsOf(o)) {
                coupons.relockForReorder(o.getUserId(), userCouponId, n.getId(), now);
            }
        } catch (CouponInvalidException e) {
            throw AppException.reorderCouponUnavailable("原订单用过的券已不可用，请改为退款")
                    .code("admin.err.latePayment.couponUnavailable");
        }
    }

    private static List<Long> couponIdsOf(ShopOrder o) {
        List<Long> ids = new ArrayList<>(2);
        if (o.getDiscountUserCouponId() != null) {
            ids.add(o.getDiscountUserCouponId());
        }
        if (o.getFreeShippingUserCouponId() != null && !ids.contains(o.getFreeShippingUserCouponId())) {
            ids.add(o.getFreeShippingUserCouponId());
        }
        return ids;
    }

    private static long coinOf(ShopOrder o) {
        return o.getCoinAmount() == null ? 0L : o.getCoinAmount();
    }
}
