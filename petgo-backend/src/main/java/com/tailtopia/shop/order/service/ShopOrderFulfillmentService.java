package com.tailtopia.shop.order.service;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.Carrier;
import com.tailtopia.shop.order.domain.CompletionSource;
import com.tailtopia.shop.order.domain.DeliverySource;
import com.tailtopia.shop.order.domain.Shipment;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.repository.ShipmentRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 履约段推进（Story 4.1，SPEC-2 / SPEC-5 / S-1 / S-2 / FR-102）。
 *
 * <p>🔴 <b>本类的存在理由是「不留死路」。</b>SPEC-2 指出 {@code SHIPPED} 若只有一条出口，
 * 一旦那条出口没被触发，订单就永久卡住 —— 而卡住的订单既不能退货也不能评价，
 * 用户只能来找客服。故三条出口<b>同时</b>提供：
 * <ol>
 *   <li>{@link #markDeliveredByAdmin} —— 运营在后台手动置位（AB-11B）；</li>
 *   <li>{@link #confirmReceipt} —— 用户在<b>已发货</b>态就能确认收货，不必等系统标记送达；</li>
 *   <li>{@link #autoDeliverOverdue} —— 发货起 M=7 日无任何标记则自动置位。</li>
 * </ol>
 *
 * <p>🔴 <b>S-2 一单多包</b>：订单转 {@code DELIVERED} 需<b>所有</b>包裹都送达；
 * 7 日自动确认以<b>最后一个</b>包裹送达为起点。
 *
 * <p>🔴 <b>不碰 Epic 3 的既有支付逻辑</b>，只加履约段自己的边。
 */
@Service
public class ShopOrderFulfillmentService {

    private static final Logger log = LoggerFactory.getLogger(ShopOrderFulfillmentService.class);

    private final ShopOrderRepository orders;
    private final ShipmentRepository shipments;

    public ShopOrderFulfillmentService(ShopOrderRepository orders, ShipmentRepository shipments) {
        this.orders = orders;
        this.shipments = shipments;
    }

    // ---------- 读 ----------

    @Transactional(readOnly = true)
    public List<Shipment> shipmentsOf(long orderId) {
        return shipments.findByShopOrderIdOrderByIdAsc(orderId);
    }

    // ---------- 发货 ----------

    /**
     * 发货：登记一个包裹，订单转 {@code SHIPPED}（S-2：可重复调用登记第 2..N 个包裹）。
     *
     * <p>🔴 第二个包裹<b>不改写订单发货时刻</b> —— M 日自动送达以首次发货起算，
     * 否则运营每补录一个包裹就把兜底往后推一次，最后一个包裹迟迟不录时订单又卡回原地。
     *
     * @return 新登记的包裹
     */
    @Transactional
    public Shipment ship(String orderToken, Carrier carrier, String trackingNo, long carrierCost) {
        ShopOrder order = requireOrder(orderToken);
        if (order.getStatus() != ShopOrderStatus.PENDING_SHIPMENT
                && order.getStatus() != ShopOrderStatus.SHIPPED) {
            throw AppException.conflict("该订单当前状态不可发货：" + order.getStatus());
        }
        Carrier c = carrier == null ? null : carrier;
        if (c == null) {
            throw AppException.validation("请选择承运商");
        }
        String no = trackingNo == null ? "" : trackingNo.trim();
        shipments.findByCarrierAndTrackingNo(c, no).ifPresent(existing -> {
            // 库级唯一索引兜底，这里只是把它翻译成运营看得懂的话。
            // 重复录入会让同一笔承运成本被计两次，直接污染 AB-13A 的毛利。
            throw AppException.conflict("该承运商的这个物流单号已录入过");
        });

        Shipment shipment = shipments.save(Shipment.ship(order.getId(), c, no, carrierCost));
        order.markShipped(shipment.getShippedAt());
        orders.save(order);
        // 🔒 单号非 PII 可记；同上下文的收件人姓名/电话/地址严禁记录（NFR-5）
        log.info("电商订单发货 token={} carrier={} trackingNo={}", order.getPublicToken(),
                c, shipment.getTrackingNo());
        return shipment;
    }

    // ---------- 出口①：后台标记送达 ----------

    /**
     * 出口①：运营手动标记<b>整单</b>已送达（AB-11B 兜底）。
     *
     * <p>连带把所有未送达的包裹一并置为送达 —— 否则订单是 {@code DELIVERED} 而包裹还是
     * {@code SHIPPED}，同一件事有两个互相矛盾的答案。
     */
    @Transactional
    public void markDeliveredByAdmin(String orderToken) {
        ShopOrder order = requireOrder(orderToken);
        requireShipped(order);
        Instant now = Instant.now();
        for (Shipment s : shipments.findByShopOrderIdOrderByIdAsc(order.getId())) {
            s.markDelivered(now);
            shipments.save(s);
        }
        order.markDelivered(now, DeliverySource.ADMIN_MARK);
        orders.save(order);
        log.info("电商订单标记送达（后台）token={}", order.getPublicToken());
    }

    /**
     * 逐包裹标记送达（S-2）。🔴 <b>只有全部包裹都送达时订单才转 {@code DELIVERED}</b>，
     * 且以最后一个包裹的送达时刻为签收时刻。
     *
     * @return 本次调用是否让订单整单转为已送达
     */
    @Transactional
    public boolean markShipmentDelivered(String orderToken, long shipmentId) {
        ShopOrder order = requireOrder(orderToken);
        requireShipped(order);
        Shipment target = shipments.findById(shipmentId)
                .filter(s -> s.getShopOrderId().equals(order.getId()))
                .orElseThrow(() -> AppException.notFound("包裹不存在"));
        target.markDelivered(Instant.now());
        shipments.save(target);

        List<Shipment> all = shipments.findByShopOrderIdOrderByIdAsc(order.getId());
        if (all.stream().anyMatch(s -> !s.isDelivered())) {
            return false;   // 还有包裹在路上
        }
        // 以最后一个包裹的送达时刻为签收时刻（S-2：7 日自动确认以此起算）
        Instant last = all.stream().map(Shipment::getDeliveredAt)
                .max(Instant::compareTo).orElse(Instant.now());
        order.markDelivered(last, DeliverySource.SHIPMENTS_ALL_DELIVERED);
        orders.save(order);
        return true;
    }

    // ---------- 出口②：用户确认收货 ----------

    /**
     * 出口②：用户确认收货。
     *
     * <p>🔴 <b>{@code SHIPPED} 态即可确认，不必等系统标记送达</b> —— 用户比谁都先知道货到没到。
     * 此时经由 {@code DELIVERED} 中转（写入签收时刻）再转 {@code COMPLETED}：
     * 不写签收时刻，Epic 5 的退货窗口就没有起算点。
     *
     * <p>🔴 越权与不存在同为 404（与 Epic 3 同口径：403 会泄漏 token 确实存在）。
     */
    @Transactional
    public ShopOrder confirmReceipt(long userId, String orderToken) {
        ShopOrder order = orders.findByPublicTokenAndUserId(orderToken, userId)
                .orElseThrow(() -> AppException.notFound("订单不存在"));
        if (order.getStatus() == ShopOrderStatus.COMPLETED) {
            return order;   // 幂等：重复点击不报错
        }
        if (order.getStatus() != ShopOrderStatus.SHIPPED
                && order.getStatus() != ShopOrderStatus.DELIVERED) {
            throw AppException.conflict("该订单当前状态不可确认收货");
        }
        Instant now = Instant.now();
        if (order.getStatus() == ShopOrderStatus.SHIPPED) {
            for (Shipment s : shipments.findByShopOrderIdOrderByIdAsc(order.getId())) {
                s.markDelivered(now);
                shipments.save(s);
            }
            order.markDelivered(now, DeliverySource.USER_CONFIRM);
        }
        order.markCompleted(now, CompletionSource.USER_CONFIRM);
        orders.save(order);
        log.info("电商订单用户确认收货 token={}", order.getPublicToken());
        return order;
    }

    // ---------- 出口③ + 自动完成：定时兜底 ----------

    /**
     * 出口③：发货起 M=7 日无任何标记 → 自动置 {@code DELIVERED}（S-1）。
     *
     * <p>逐笔独立事务，单笔失败不阻断其余。
     *
     * @return 实际置位笔数
     */
    public int autoDeliverOverdue(int limit) {
        Instant threshold = Instant.now().minus(ShopOrder.AUTO_DELIVER_AFTER);
        int done = 0;
        for (ShopOrder o : orders.findAutoDeliverDue(threshold,
                PageRequest.of(0, Math.max(1, limit)))) {
            try {
                if (autoDeliverOne(o.getId())) {
                    done++;
                }
            } catch (RuntimeException e) {
                log.warn("自动置送达失败 orderId={} cause={}", o.getId(),
                        e.getClass().getSimpleName());
            }
        }
        return done;
    }

    @Transactional
    public boolean autoDeliverOne(long orderId) {
        ShopOrder order = orders.findById(orderId).orElse(null);
        Instant now = Instant.now();
        if (order == null || !order.isAutoDeliverDue(now)) {
            return false;   // 并发已推进
        }
        for (Shipment s : shipments.findByShopOrderIdOrderByIdAsc(orderId)) {
            s.markDelivered(now);
            shipments.save(s);
        }
        order.markDelivered(now, DeliverySource.AUTO_TIMEOUT);
        orders.save(order);
        return true;
    }

    /**
     * 送达起 7 日用户仍未确认 → 自动置 {@code COMPLETED}（FR-102）。
     *
     * <p>🔴 <b>退货窗口不受影响</b>：它自签收时刻起算，与本状态无关（见
     * {@link ShopOrder#isWithinReturnWindow}）。两者并存不是巧合，是刻意的 ——
     * 「已完成」说的是履约结束，不是「不能再退」。
     */
    public int autoCompleteOverdue(int limit) {
        Instant threshold = Instant.now().minus(ShopOrder.AUTO_COMPLETE_AFTER);
        int done = 0;
        for (ShopOrder o : orders.findAutoCompleteDue(threshold,
                PageRequest.of(0, Math.max(1, limit)))) {
            try {
                if (autoCompleteOne(o.getId())) {
                    done++;
                }
            } catch (RuntimeException e) {
                log.warn("自动完成失败 orderId={} cause={}", o.getId(), e.getClass().getSimpleName());
            }
        }
        return done;
    }

    @Transactional
    public boolean autoCompleteOne(long orderId) {
        ShopOrder order = orders.findById(orderId).orElse(null);
        Instant now = Instant.now();
        if (order == null || !order.isAutoCompleteDue(now)) {
            return false;
        }
        order.markCompleted(now, CompletionSource.AUTO_TIMEOUT);
        orders.save(order);
        return true;
    }

    // ---------- 内部 ----------

    private ShopOrder requireOrder(String orderToken) {
        return orders.findByPublicToken(orderToken)
                .orElseThrow(() -> AppException.notFound("订单不存在"));
    }

    private static void requireShipped(ShopOrder order) {
        if (order.getStatus() != ShopOrderStatus.SHIPPED) {
            throw AppException.conflict("该订单当前状态不可标记送达：" + order.getStatus());
        }
    }
}
