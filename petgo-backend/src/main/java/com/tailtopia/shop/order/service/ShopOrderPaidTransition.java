package com.tailtopia.shop.order.service;

import com.tailtopia.shop.coupon.service.CouponLedgerService;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.event.ShopOrderPaidEvent;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.service.InventoryService;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 统一「已付款」入口（v1.4.2 shop Story 3.2 AC4，D-8 末段 / D-11 I4 / D-23）。
 *
 * <p>原 {@code ShopOrderPaymentService#fulfillPaid} 改名扩参抽到这里：{@code CheckoutService}（提交即结清的
 * 纯币 / 零元单）与 {@code ShopOrderPaymentService}（网关到账、存量纯币单 {@code /pay}）都要调它，
 * 而后者依赖前者，放在任一方都会成环。{@code ShopOrderPaymentService#markPaid} 保留为委托（4.2 按架构名调用）。
 *
 * <p>内部顺序：逐行 {@code inventory.commit} → {@code order.markPaid}（写 {@code paid_at}，商家平台返佣游标，
 * <b>只在这里写</b>）→ {@code CouponLedgerService.markUsed}（LOCKED → USED，幂等）→ 发 {@link ShopOrderPaidEvent}。
 *
 * <p>🔴 {@code markUsed} <b>只在这里调</b>：不挂 {@code PaymentIntentPaidEvent} —— 3.3 的迟到款意图也会发该事件，
 * 挂在意图事件上会误动已取消订单的券。
 *
 * <p>🔴 双重守卫：非 {@code PENDING_PAYMENT} 直接返回 {@code false}（重复回调 / 已取消）；回调与取消的真并发
 * 由 {@code shop_orders.version} 乐观锁裁决（后提交者整体回滚），{@code commit} 与 {@code release} 不会双双落库。
 */
@Component
public class ShopOrderPaidTransition {

    private final ShopOrderRepository orders;
    private final ShopOrderLineRepository orderLines;
    private final InventoryService inventory;
    private final CouponLedgerService coupons;
    private final ApplicationEventPublisher events;

    public ShopOrderPaidTransition(ShopOrderRepository orders, ShopOrderLineRepository orderLines,
            InventoryService inventory, CouponLedgerService coupons,
            ApplicationEventPublisher events) {
        this.orders = orders;
        this.orderLines = orderLines;
        this.inventory = inventory;
        this.coupons = coupons;
        this.events = events;
    }

    /**
     * @return 本次是否真的把订单从待支付推进为待发货；调用方据此决定是否走过渡期扣币（仅存量单）
     */
    @Transactional
    public boolean markPaid(ShopOrder order, PaidSource source) {
        if (order.getStatus() != ShopOrderStatus.PENDING_PAYMENT) {
            return false;   // 已处理过（重复回调 / 轮询二次到达）或已被取消
        }
        for (ShopOrderLine line : orderLines.findByOrderIdOrderByIdAsc(order.getId())) {
            inventory.commit(line.getSkuId(), line.getQty());
        }
        Instant now = Instant.now();
        // paid_at 与状态迁移同一事务写入（商家平台返佣记账的扫描游标，契约第 2 节）。
        order.markPaid(now);
        orders.save(order);
        coupons.markUsed(order.getId(), now);
        // 「该发货了」这个事实的唯一发点（v1.3.0 复审 #6 / #13）；消费方一律 AFTER_COMMIT。
        events.publishEvent(new ShopOrderPaidEvent(order.getId(), order.getPublicToken(),
                order.getUserId(), order.paidTotal(),
                order.getPayChannel() == null ? null : order.getPayChannel().name(),
                source.name()));
        return true;
    }
}
