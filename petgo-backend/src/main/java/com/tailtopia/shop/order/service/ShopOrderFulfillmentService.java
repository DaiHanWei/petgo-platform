package com.tailtopia.shop.order.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.Carrier;
import com.tailtopia.shop.order.domain.CompletionSource;
import com.tailtopia.shop.order.domain.DeliverySource;
import com.tailtopia.shop.order.domain.LogisticsException;
import com.tailtopia.shop.order.domain.Shipment;
import com.tailtopia.shop.order.domain.Shipments;
import com.tailtopia.shop.order.domain.ShippedSource;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.event.ShopOrderShippedEvent;
import com.tailtopia.shop.order.repository.ShipmentRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
 *   <li>{@link #autoDeliverOverdue} —— 发货起 M 日（v1.4.2 起 M=14，D-12）无任何标记则自动置位。</li>
 * </ol>
 *
 * <p>🔴 <b>S-2 一单多包</b>：订单转 {@code DELIVERED} 需<b>所有活动</b>包裹都送达（v1.4.2 shop 5.1：被取代 /
 * 已取消的运单不计，三条出口也只处理活动运单）；7 日自动完成以<b>最后一个</b>包裹送达为起点。
 *
 * <p>🔴 <b>不碰 Epic 3 的既有支付逻辑</b>，只加履约段自己的边。
 */
@Service
public class ShopOrderFulfillmentService {

    private static final Logger log = LoggerFactory.getLogger(ShopOrderFulfillmentService.class);

    private final ShopOrderRepository orders;
    private final ShipmentRepository shipments;
    private final ApplicationEventPublisher events;
    private final AdminAuditService audit;
    private final ShopPaymentAlerts alerts;

    public ShopOrderFulfillmentService(ShopOrderRepository orders, ShipmentRepository shipments,
            ApplicationEventPublisher events, AdminAuditService audit, ShopPaymentAlerts alerts,
            com.tailtopia.shop.order.repository.ShopOrderLineRepository orderLines,
            com.tailtopia.admin.alert.OpsAlertService opsAlerts) {
        this.orderLines = orderLines;
        this.opsAlerts = opsAlerts;
        this.orders = orders;
        this.shipments = shipments;
        this.events = events;
        this.audit = audit;
        this.alerts = alerts;
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
        return ship(orderToken, carrier, trackingNo, carrierCost, null);
    }

    /**
     * 同上，记操作人（V1.4.2 shop-admin 6.1 · AD-14）：首次转已发货写 {@code shipped_by_admin_id}；
     * 运单失败后转手工运单 → 清 {@code waybill_failed_at}（否则一直挂在异常队列）。
     */
    @Transactional
    public Shipment ship(String orderToken, Carrier carrier, String trackingNo, long carrierCost, Long actorAccountId) {
        ShopOrder order = requireOrder(orderToken);
        if (order.getStatus() != ShopOrderStatus.PENDING_SHIPMENT
                && order.getStatus() != ShopOrderStatus.SHIPPED) {
            throw AppException.conflict("该订单当前状态不可发货：" + order.getStatus());
        }
        Carrier c = carrier == null ? null : carrier;
        if (c == null) {
            throw AppException.validation("请选择承运商");
        }
        // V1.4.2 shop-admin 7.2 · AD-11：有未处置的未锁库存行（迟到款重下单部分缺货）→ 一切发货入口拒绝
        if (orderLines.existsUnresolvedStockUnlocked(order.getId())) {
            throw AppException.conflict("该订单有未锁到库存的商品行，请先到异常订单处理（部分取消或联系用户后继续）")
                    .code("admin.err.v142.late.stockUnlocked");
        }
        // v1.4.2 shop 5.1 AC10.1：已有活动 Biteship 运单时不得再登记手工运单（先由 5.2 的处置取消再转手工）
        if (Shipments.hasActiveBiteship(shipments.findByShopOrderIdOrderByIdAsc(order.getId()))) {
            throw AppException.conflict("该订单已有 Biteship 运单，请先取消");
        }
        String no = trackingNo == null ? "" : trackingNo.trim();
        shipments.findByCarrierAndTrackingNo(c.name(), no).ifPresent(existing -> {
            // 库级唯一索引兜底，这里只是把它翻译成运营看得懂的话。
            // 重复录入会让同一笔承运成本被计两次，直接污染 AB-13A 的毛利。
            throw AppException.conflict("该承运商的这个物流单号已录入过");
        });

        Shipment shipment = shipments.save(Shipment.ship(order.getId(), c, no, carrierCost));
        order.markShipped(shipment.getShippedAt(), ShippedSource.MANUAL);
        order.recordShippedBy(actorAccountId);
        if (order.getWaybillFailReason() != null && order.getWaybillFailReason().startsWith("UNKNOWN_RESULT:")) {
            // 抽屉已提示先去 Biteship 控制台核实；这里留痕，便于事后追查可能的孤儿运单
            log.warn("运单失败结果未知时转手工运单，请到 Biteship 控制台核实是否已有运单 token={}", order.getPublicToken());
        }
        order.clearWaybillFailure();
        orders.save(order);
        // 🔒 单号非 PII 可记；同上下文的收件人姓名/电话/地址严禁记录（NFR-5）
        log.info("电商订单发货 token={} carrier={} trackingNo={}", order.getPublicToken(),
                c, shipment.getTrackingNo());
        return shipment;
    }

    /**
     * 手工运单发货 + 审计 + 发货通知（v1.4.2 shop 5.2 复审 3，供揽收失败「转手工运单」）：委托 {@link #ship}；
     * 订单原为 {@code PENDING_SHIPMENT} → 同事务发布 {@link ShopOrderShippedEvent}（提交后由
     * {@code ShopOrderShippedNotifyListener} 推送，事务回滚则不发）；审计 {@code SHOP_ORDER_SHIPPED} 摘要与
     * {@code AdminShopOrderService#ship} 同格式（只记承运商 + 单号，🔒 不记收件人）。
     */
    @Transactional
    public Shipment shipManualWithAudit(String orderToken, Carrier carrier, String trackingNo, long carrierCost,
            Long actorAccountId) {
        ShopOrder before = requireOrder(orderToken);
        boolean firstShip = before.getStatus() == ShopOrderStatus.PENDING_SHIPMENT;
        Shipment shipment = ship(orderToken, carrier, trackingNo, carrierCost, actorAccountId);
        audit.record(actorAccountId, AuditActions.SHOP_ORDER_SHIPPED, "SHOP_ORDER", orderToken,
                "发货（手工运单）：承运商=%s 物流单号=%s 承运成本=%d".formatted(carrier.name(), shipment.getTrackingNo(),
                        carrierCost));
        if (firstShip) {
            ShopOrder order = requireOrder(orderToken);
            events.publishEvent(new ShopOrderShippedEvent(order.getId(), order.getUserId(), order.getPublicToken()));
        }
        return shipment;
    }

    /**
     * 「标记已发货」（v1.4.2 shop 5.1 AC10.2，供后台线；PRD 旅程 D：运营确认打包后标记）：订单须
     * {@code PENDING_SHIPMENT} 且存在活动的 Biteship 运单；订单 → {@code SHIPPED}、{@code shipped_source = MANUAL}；
     * <b>运单状态不动</b>（等 Biteship 回传 {@code picked}，不登记第二个运单）；提交后发 {@code SHOP_ORDER_SHIPPED}；审计。
     */
    @Transactional
    public ShopOrder markShippedWithoutWaybill(String orderToken, Long actorAccountId) {
        ShopOrder order = requireOrder(orderToken);
        if (order.getStatus() != ShopOrderStatus.PENDING_SHIPMENT) {
            throw AppException.conflict("该订单当前状态不可标记发货：" + order.getStatus());
        }
        if (!Shipments.hasActiveBiteship(shipments.findByShopOrderIdOrderByIdAsc(order.getId()))) {
            throw AppException.conflict("该订单没有 Biteship 运单，请先下运单或登记手工运单");
        }
        order.markShipped(Instant.now(), ShippedSource.MANUAL);
        order.recordShippedBy(actorAccountId);   // V1.4.2 shop-admin 6.1：H4 显示操作人
        orders.save(order);
        events.publishEvent(new ShopOrderShippedEvent(order.getId(), order.getUserId(), order.getPublicToken()));
        audit.record(actorAccountId, AuditActions.SHOP_ORDER_MARKED_SHIPPED, "SHOP_ORDER", orderToken,
                "标记已发货（Biteship 运单待揽收）");
        log.info("电商订单标记已发货（无新运单）token={}", order.getPublicToken());
        return order;
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
        markActiveDelivered(order.getId(), now);
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
        if (!target.isActive()) {
            throw AppException.conflict("该运单已被取代或已取消，不能标记送达");
        }
        target.markDelivered(Instant.now());
        shipments.save(target);

        List<Shipment> all = shipments.findByShopOrderIdOrderByIdAsc(order.getId());
        if (!Shipments.allActiveDelivered(all)) {
            return false;   // 还有包裹在路上
        }
        // 以最后一个活动包裹的送达时刻为签收时刻（S-2：7 日自动确认以此起算）
        Instant last = Shipments.lastActiveDeliveredAt(all).orElse(Instant.now());
        order.markDelivered(last, DeliverySource.SHIPMENTS_ALL_DELIVERED);
        orders.save(order);
        return true;
    }

    // ---------- 出口②：用户确认收货 ----------

    /** V1.4.2 shop-admin Story 6.2：O-16 判据（订单行已入库件数）与 OPS 去重提醒。 */
    private final com.tailtopia.shop.order.repository.ShopOrderLineRepository orderLines;
    private final com.tailtopia.admin.alert.OpsAlertService opsAlerts;

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
        // V1.4.2 shop-admin Story 6.2 · O-16：已开始按行确认退回入库 → 409（货已回仓，App 提示联系客服）；其余异常照常可确认
        if (order.getReturnCause() == com.tailtopia.shop.logistics.ReturnCause.USER_FAULT
                && orderLines.findByOrderIdOrderByIdAsc(order.getId()).stream()
                .anyMatch(l -> l.getReturnInboundQty() > 0)) {
            throw AppException.returnInboundConfirmed("退回商品已入库，请联系平台客服");
        }
        Instant now = Instant.now();
        if (order.getStatus() == ShopOrderStatus.SHIPPED) {
            markActiveDelivered(order.getId(), now);
            order.markDelivered(now, DeliverySource.USER_CONFIRM);
        }
        order.markCompleted(now, CompletionSource.USER_CONFIRM);
        // v1.4.2 shop 5.2 AC6：任何物流异常下确认收货照常可点（不阻拦）；完成后清标记并提醒运营，claim_pending 保留
        LogisticsException exception = order.getLogisticsException();
        if (exception != null) {
            order.clearLogisticsException(now);
        }
        orders.save(order);
        log.info("电商订单用户确认收货 token={}", order.getPublicToken());
        if (exception != null) {
            // 运营提醒落点（AC6.3）：4.1 无可复用的持久化提醒 → WARN 日志 + 飞书（提交后发）。🔒 只用展示号
            log.info("物流异常订单用户确认收货 token={} exception={}", order.getPublicToken(), exception);
            log.warn("物流异常订单被用户确认收货，请运营核对 order={} exception={} claimPending={}",
                    order.getDisplayNo(), exception, order.isClaimPending());
            String text = "[物流] 订单 " + order.getDisplayNo() + " 在 " + exception + " 下被用户确认收货，异常已清除，请核对"
                    + (order.isClaimPending() ? "；该单已标「待索赔」，请撤回索赔" : "");
            // V1.4.2 shop-admin 6.2 · AD-5：OPS 群，去重键 receipt-cleared:{orderToken}
            opsAlerts.send(com.tailtopia.admin.alert.OpsChannel.OPS, "receipt-cleared:" + order.getPublicToken(), text);
        }
        return order;
    }

    // ---------- 出口③ + 自动完成：定时兜底 ----------

    /**
     * 出口③：发货起 M 日（v1.4.2 起 M=14，D-12）无任何标记 → 自动置 {@code DELIVERED}（S-1）。
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
        markActiveDelivered(orderId, now);
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

    /**
     * 三条出口逐运单置送达：🔴 只处理活动运单（v1.4.2 shop 5.1 AC11.2）—— 跳过 superseded / CANCELLED，
     * 否则 {@code CANCELLED → DELIVERED} 违反领域规则。
     */
    private void markActiveDelivered(long orderId, Instant now) {
        for (Shipment s : shipments.findByShopOrderIdOrderByIdAsc(orderId)) {
            if (!s.isActive()) {
                continue;
            }
            s.markDelivered(now);
            shipments.save(s);
        }
    }

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
