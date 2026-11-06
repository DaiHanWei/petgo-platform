package com.tailtopia.shop.logistics;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.Carrier;
import com.tailtopia.shop.order.domain.LogisticsException;
import com.tailtopia.shop.order.domain.Shipment;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.refund.CancelReason;
import com.tailtopia.shop.order.refund.ShopOrderRefundService;
import com.tailtopia.shop.order.repository.ShipmentRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.ShopOrderFulfillmentService;
import com.tailtopia.shop.order.service.ShopPaymentAlerts;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 物流异常运营处置（v1.4.2 shop Story 5.2 AC7 / AC8，D-13、D-14、PRD §3.10 ① ~ ④），供后台线调用（页面不在本线，S-2）。
 *
 * <p>🔴 <b>外部调用（Biteship）一律在事务外</b>（P-7）：本类方法自身不开外层事务，用 {@link TransactionTemplate}
 * {@code REQUIRES_NEW} 包短事务；<b>调用方（后台 Controller）也不得在外层开事务</b>。
 *
 * <ul>
 *   <li>揽收失败三种处置都<b>先取消旧运单</b>（{@link BiteshipShipmentService#cancelWaybill}）；取消失败（不可取消 / 结果不确定）
 *       → 整个处置中止、订单与标记都不动、把原因返回给运营（拍板③：不让仍有效的 Biteship 运单与新运单 / 手工运单并存）；</li>
 *   <li>三种处置互斥：短事务里重新校验「当前异常 = PICKUP_FAILED」与订单状态，订单 {@code @Version} 乐观锁兜底；</li>
 *   <li>{@code claim_pending} 置 true 只经 {@link #confirmReturned}（快递原因）与 {@link #markLostOrDamaged}；置 false 只经
 *       {@link #setClaimPending}（AC8）；</li>
 *   <li>退款 / 取消口径归 4.1（{@link ShopOrderRefundService}），本类只编排调用，<b>不算任何金额</b>（D-14 / D-15）。</li>
 * </ul>
 *
 * <p>🔒 审计摘要 / 日志只记订单 token / 展示号、异常类型、处置方式；不记收件人任何字段。
 */
@Service
public class ShopLogisticsExceptionService {

    private static final Logger log = LoggerFactory.getLogger(ShopLogisticsExceptionService.class);

    static final int PAGE_SIZE = 20;
    private static final String TARGET = "SHOP_ORDER";

    /** 后台列表行（AC7.7）。🔒 不含收件人信息、不含自增 id。 */
    public record ExceptionRow(String displayNo, String orderToken, String exceptionType, Instant flaggedAt,
            boolean confirmed, boolean claimPending, String latestProviderStatus) {
    }

    private final ShopOrderRepository orders;
    private final ShipmentRepository shipments;
    private final BiteshipShipmentService waybills;
    private final ShopOrderFulfillmentService fulfillment;
    private final ShopOrderRefundService refunds;
    private final AdminAuditService audit;
    private final ShopPaymentAlerts alerts;
    private final TransactionTemplate tx;
    private final Clock clock;

    @Autowired
    public ShopLogisticsExceptionService(ShopOrderRepository orders, ShipmentRepository shipments,
            BiteshipShipmentService waybills, ShopOrderFulfillmentService fulfillment, ShopOrderRefundService refunds,
            AdminAuditService audit, ShopPaymentAlerts alerts, PlatformTransactionManager txManager) {
        this(orders, shipments, waybills, fulfillment, refunds, audit, alerts, txManager, Clock.systemUTC());
    }

    ShopLogisticsExceptionService(ShopOrderRepository orders, ShipmentRepository shipments,
            BiteshipShipmentService waybills, ShopOrderFulfillmentService fulfillment, ShopOrderRefundService refunds,
            AdminAuditService audit, ShopPaymentAlerts alerts, PlatformTransactionManager txManager, Clock clock) {
        this.orders = orders;
        this.shipments = shipments;
        this.waybills = waybills;
        this.fulfillment = fulfillment;
        this.refunds = refunds;
        this.audit = audit;
        this.alerts = alerts;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    // ===================== ① 揽收失败 =====================

    /** 重下 Biteship 运单：取消旧运单（{@code pickup_delay}）→ {@code retryWaybill}；建成后清标记，失败标记保留。 */
    public PickupResolution resolvePickupFailureByRetry(long orderId, Long actorAccountId) {
        tx.executeWithoutResult(s -> {
            ShopOrder order = requirePickupFailed(orderId, Set.of(ShopOrderStatus.PENDING_SHIPMENT));
            waybills.requireWaybillData(order);   // 复审 5：数据前提先于取消旧运单校验
        });
        PickupResolution aborted = cancelOldWaybill(orderId, "pickup_delay", actorAccountId);
        if (aborted != null) {
            return aborted;
        }
        WaybillResult w = waybills.retryWaybill(orderId, actorAccountId);              // 🔴 内部事务外调 Biteship
        if (!w.created()) {
            log.warn("揽收失败处置：重下运单失败，标记保留 orderId={} reason={}", orderId, w.failReason());
            return PickupResolution.waybillFailed(w.failReason());
        }
        // 复审 1：清标记前按订单加悲观锁重新校验——期间若被「取消订单」等并发处置，不清标记、不审计，只告警
        String conflict = tx.execute(s -> {
            ShopOrder order = lockOrder(orderId);
            boolean still = order.getLogisticsException() == LogisticsException.PICKUP_FAILED
                    && order.getStatus() == ShopOrderStatus.PENDING_SHIPMENT
                    && shipments.findActiveByShopOrderId(orderId).stream().anyMatch(Shipment::isBiteship);
            if (!still) {
                return order.getDisplayNo();
            }
            clearAndAudit(order, AuditActions.SHOP_LOGISTICS_PICKUP_FAILURE_RESOLVED, "揽收失败处置：重下 Biteship 运单",
                    actorAccountId);
            return null;
        });
        if (conflict != null) {
            return concurrentAbort(conflict, "重下运单");
        }
        return PickupResolution.resolved();
    }

    /** 转手工运单：取消旧运单（{@code change_courier}）→ 登记手工运单（5.1 已放开「无活动 Biteship 运单」前提）→ 清标记。 */
    public PickupResolution resolvePickupFailureByManualWaybill(long orderId, Carrier carrier, String trackingNo,
            long carrierCost, Long actorAccountId) {
        if (carrier == null) {
            throw AppException.validation("请选择承运商");
        }
        String no = trackingNo == null ? "" : trackingNo.trim();
        if (no.isEmpty()) {
            throw AppException.validation("请填写物流单号");
        }
        tx.executeWithoutResult(s -> {
            requirePickupFailed(orderId, Set.of(ShopOrderStatus.PENDING_SHIPMENT, ShopOrderStatus.SHIPPED));
            // 先于取消旧运单校验重复单号：否则旧运单已取消、手工运单却登记不上
            if (shipments.findByCarrierAndTrackingNo(carrier.name(), no).isPresent()) {
                throw AppException.conflict("该承运商的这个物流单号已录入过");
            }
        });
        PickupResolution aborted = cancelOldWaybill(orderId, "change_courier", actorAccountId);
        if (aborted != null) {
            return aborted;
        }
        tx.executeWithoutResult(s -> {
            ShopOrder order = requirePickupFailed(lockOrder(orderId),
                    Set.of(ShopOrderStatus.PENDING_SHIPMENT, ShopOrderStatus.SHIPPED));
            // 复审 3：复用履约服务的发货审计 + 发货通知（原为待发货时提交后推送）
            fulfillment.shipManualWithAudit(order.getPublicToken(), carrier, no, carrierCost, actorAccountId);
            ShopOrder reread = requireOrder(orderId);
            clearAndAudit(reread, AuditActions.SHOP_LOGISTICS_PICKUP_FAILURE_RESOLVED,
                    "揽收失败处置：转手工运单（承运商=%s）".formatted(carrier.name()), actorAccountId);
        });
        return PickupResolution.resolved();
    }

    /**
     * 取消订单：取消旧运单（{@code others}）→ 4.1 整单取消（{@code CancelReason.PICKUP_FAILED}，平台原因：退款口径全在 4.1）；
     * 标记保留作为历史（订单已离开待收货，用户侧不显示）。
     */
    public PickupResolution resolvePickupFailureByCancel(long orderId, Long actorAccountId) {
        tx.executeWithoutResult(s -> requirePickupFailed(orderId,
                Set.of(ShopOrderStatus.PENDING_SHIPMENT, ShopOrderStatus.SHIPPED)));
        PickupResolution aborted = cancelOldWaybill(orderId, "others", actorAccountId);
        if (aborted != null) {
            return aborted;
        }
        // 复审 1：最终事务按订单加悲观锁，校验仍为 PICKUP_FAILED、状态可取消且未被并发处置（旧运单已取消后不应再有活动运单——
        // 有则说明「重下运单 / 转手工」已在进行或完成），不满足只告警、不取消
        String conflict = tx.execute(s -> {
            ShopOrder order = lockOrder(orderId);
            boolean still = order.getLogisticsException() == LogisticsException.PICKUP_FAILED
                    && (order.getStatus() == ShopOrderStatus.PENDING_SHIPMENT
                            || order.getStatus() == ShopOrderStatus.SHIPPED)
                    && shipments.findActiveByShopOrderId(orderId).isEmpty();
            if (!still) {
                return order.getDisplayNo();
            }
            refunds.cancelWhole(order.getPublicToken(), CancelReason.PICKUP_FAILED, "揽收失败", actorAccountId);
            audit.record(actorAccountId, AuditActions.SHOP_LOGISTICS_PICKUP_FAILURE_RESOLVED, TARGET,
                    order.getPublicToken(), "揽收失败处置：取消订单（异常=PICKUP_FAILED，标记保留）");
            log.info("揽收失败处置：取消订单 order={}", order.getDisplayNo());
            return null;
        });
        if (conflict != null) {
            return concurrentAbort(conflict, "取消订单");
        }
        return PickupResolution.resolved();
    }

    /** 并发处置冲突：只告警（展示号）、不清标记、不审计，返回中止。 */
    private PickupResolution concurrentAbort(String displayNo, String how) {
        log.warn("揽收失败处置「{}」收尾时订单已被并发处置，未清标记 order={}", how, displayNo);
        alerts.alert("[物流] 订单 " + displayNo + " 揽收失败处置「" + how
                + "」收尾时发现订单已被其他处置改变，未清标记，请人工核对");
        return PickupResolution.aborted("CONCURRENT_RESOLUTION");
    }

    /** 取消旧 Biteship 运单；失败返回中止结果（订单与标记都不动），成功（或本就没有活动运单）返回 null。 */
    private PickupResolution cancelOldWaybill(long orderId, String reasonCode, Long actor) {
        try {
            waybills.cancelWaybill(orderId, reasonCode, actor);                            // 🔴 内部事务外调 Biteship
            return null;
        } catch (AppException e) {
            log.warn("揽收失败处置中止：旧运单取消失败 orderId={} status={}", orderId, e.getStatus().value());
            return PickupResolution.aborted(e.getMessage());
        }
    }

    // ===================== ② 被退回 =====================

    /**
     * 确认退回原因：要求当前异常 = {@code RETURNED}；确认，快递原因置 {@code claim_pending}。后续：快递原因 →
     * {@link #reshipAfterCourierFault}；用户原因 → 运营调 4.1 整单取消（{@code USER_FAULT_RETURNED}，本类不实现取消本身）。
     */
    public ShopOrder confirmReturned(long orderId, ReturnCause cause, Long actorAccountId) {
        if (cause == null) {
            throw AppException.validation("请选择退回原因");
        }
        return tx.execute(s -> {
            ShopOrder order = requireOrder(orderId);
            requireException(order, LogisticsException.RETURNED);
            order.confirmLogisticsException(LogisticsException.RETURNED, cause == ReturnCause.COURIER_FAULT,
                    clock.instant());
            orders.save(order);
            audit.record(actorAccountId, AuditActions.SHOP_LOGISTICS_EXCEPTION_CONFIRMED, TARGET,
                    order.getPublicToken(), "确认物流异常：异常=RETURNED；原因=%s；待索赔=%s".formatted(cause,
                            order.isClaimPending()));
            log.info("确认退回 order={} cause={}", order.getDisplayNo(), cause);
            return order;
        });
    }

    /**
     * 快递原因重发（组合 5.1 {@code reshipForCourierFault}）：要求当前异常 = 已确认的 {@code RETURNED}；新运单建成后清标记，
     * {@code claim_pending} 保留。建单失败标记保留，返回 5.1 失败结果。
     */
    public WaybillResult reshipAfterCourierFault(long orderId, Long actorAccountId) {
        tx.executeWithoutResult(s -> {
            ShopOrder order = requireOrder(orderId);
            requireException(order, LogisticsException.RETURNED);
            if (!order.isLogisticsExceptionConfirmed()) {
                throw AppException.conflict("请先确认退回原因");
            }
        });
        WaybillResult w = waybills.reshipForCourierFault(orderId, actorAccountId);     // 🔴 内部事务外调 Biteship
        if (w.created()) {
            tx.executeWithoutResult(s -> clearAndAudit(requireOrder(orderId),
                    AuditActions.SHOP_LOGISTICS_EXCEPTION_CLEARED, "快递原因重发新运单后清除（异常=RETURNED）", actorAccountId));
        }
        return w;
    }

    // ===================== ④ 丢件 / 破损 =====================

    /**
     * 运营标记丢件 / 破损（C-6 拆分）：确认并置 {@code claim_pending}；订单状态不变。已签收 / 已完成订单也允许标记（S-9），
     * 但用户侧只在 {@code SHIPPED / DELIVERED} 显示配送异常。后续赔付走 4.1。
     */
    public ShopOrder markLostOrDamaged(long orderId, LogisticsException type, Long actorAccountId) {
        if (type != LogisticsException.LOST && type != LogisticsException.DAMAGED) {
            throw AppException.validation("只能标记丢件或破损");
        }
        return tx.execute(s -> {
            ShopOrder order = requireOrder(orderId);
            ShopOrderStatus st = order.getStatus();
            if (st != ShopOrderStatus.SHIPPED && st != ShopOrderStatus.DELIVERED
                    && st != ShopOrderStatus.COMPLETED) {
                throw AppException.conflict("该订单当前状态不可标记丢件 / 破损：" + st);
            }
            order.confirmLogisticsException(type, true, clock.instant());
            orders.save(order);
            audit.record(actorAccountId, AuditActions.SHOP_LOGISTICS_EXCEPTION_CONFIRMED, TARGET,
                    order.getPublicToken(), "标记物流异常：异常=%s；待索赔=true".formatted(type));
            log.info("标记丢件 / 破损 order={} type={}", order.getDisplayNo(), type);
            return order;
        });
    }

    // ===================== 清除 / 索赔提醒 =====================

    /** 运营清除（核实无事 / 停滞追回）：全清（含确认），写 {@code timer_resumed_at}；{@code claim_pending} 不动。幂等。 */
    public boolean clearException(long orderId, Long actorAccountId) {
        Boolean cleared = tx.execute(s -> {
            ShopOrder order = requireOrder(orderId);
            if (order.getLogisticsException() == null) {
                return false;
            }
            clearAndAudit(order, AuditActions.SHOP_LOGISTICS_EXCEPTION_CLEARED, "运营清除", actorAccountId);
            return true;
        });
        return Boolean.TRUE.equals(cleared);
    }

    /** 索赔提醒（后台「待索赔」完成后撤销）。值未变不写审计。 */
    public void setClaimPending(long orderId, boolean pending, Long actorAccountId) {
        tx.executeWithoutResult(s -> {
            ShopOrder order = requireOrder(orderId);
            if (order.isClaimPending() == pending) {
                return;
            }
            order.setClaimPending(pending);
            orders.save(order);
            audit.record(actorAccountId, AuditActions.SHOP_LOGISTICS_CLAIM_PENDING_CHANGED, TARGET,
                    order.getPublicToken(), "待索赔=%s".formatted(pending));
        });
    }

    // ===================== 只读列表（AC7.7） =====================

    /** 带物流异常的订单（{@code type} 为 null 不筛），按标记时刻倒序，每页 20。 */
    public Page<ExceptionRow> listOpen(LogisticsException type, int page) {
        return tx.execute(s -> orders.findLogisticsExceptionOpen(type, PageRequest.of(Math.max(0, page), PAGE_SIZE))
                .map(this::row));
    }

    /** 待索赔订单，每页 20。 */
    public Page<ExceptionRow> listClaimPending(int page) {
        return tx.execute(s -> orders.findClaimPending(PageRequest.of(Math.max(0, page), PAGE_SIZE)).map(this::row));
    }

    private ExceptionRow row(ShopOrder o) {
        String providerStatus = shipments.findActiveByShopOrderId(o.getId()).stream().filter(Shipment::isBiteship)
                .findFirst().map(Shipment::getProviderStatus).orElse(null);
        return new ExceptionRow(o.getDisplayNo(), o.getPublicToken(),
                o.getLogisticsException() == null ? null : o.getLogisticsException().name(),
                o.getLogisticsExceptionAt(), o.isLogisticsExceptionConfirmed(), o.isClaimPending(), providerStatus);
    }

    // ===================== 内部 =====================

    private void clearAndAudit(ShopOrder order, String action, String how, Long actor) {
        LogisticsException before = order.getLogisticsException();
        if (!order.clearLogisticsException(clock.instant())) {
            return;
        }
        orders.save(order);
        audit.record(actor, action, TARGET, order.getPublicToken(), "%s；异常=%s".formatted(how, before));
        log.info("清除物流异常 order={} exception={} how={}", order.getDisplayNo(), before, how);
    }

    private ShopOrder requirePickupFailed(long orderId, Set<ShopOrderStatus> allowed) {
        return requirePickupFailed(requireOrder(orderId), allowed);
    }

    private ShopOrder requirePickupFailed(ShopOrder order, Set<ShopOrderStatus> allowed) {
        requireException(order, LogisticsException.PICKUP_FAILED);
        if (!allowed.contains(order.getStatus())) {
            throw AppException.conflict("该订单当前状态不可处置揽收失败：" + order.getStatus());
        }
        return order;
    }

    private static void requireException(ShopOrder order, LogisticsException expected) {
        if (order.getLogisticsException() != expected) {
            throw AppException.conflict("订单当前物流异常不是 " + expected);
        }
    }

    private ShopOrder lockOrder(long orderId) {
        return orders.findByIdForUpdate(orderId).orElseThrow(() -> AppException.notFound("订单不存在"));
    }

    private ShopOrder requireOrder(long orderId) {
        return orders.findById(orderId).orElseThrow(() -> AppException.notFound("订单不存在"));
    }
}
