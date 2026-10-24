package com.tailtopia.merchant.po.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.merchant.po.domain.PoDeliverySource;
import com.tailtopia.merchant.po.domain.PurchaseOrder;
import com.tailtopia.merchant.po.domain.PurchaseOrderShipment;
import com.tailtopia.merchant.po.repository.PurchaseOrderRepository;
import com.tailtopia.merchant.po.repository.PurchaseOrderShipmentRepository;
import com.tailtopia.shared.error.AppException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 进货单履约（契约 5.2 状态迁移后半段，FR-15 / D5 / D6）：TailTopia ops 录入包裹、标记送达，
 * 以及发货 / 送达超时的自动推进（沿用 Toko 7 日 + 7 日）。
 *
 * <p>门店端没有「确认收货」，所以签收只有两条出口：ops 标记送达、发货起 7 日自动送达；完成只有送达起 7 日自动完成。
 */
@Service
public class PurchaseOrderFulfillmentService {

    static final int CARRIER_MAX = 32;
    static final int TRACKING_NO_MAX = 64;
    static final int TRACKING_URL_MAX = 512;

    private final PurchaseOrderRepository orders;
    private final PurchaseOrderShipmentRepository shipments;
    private final AdminAuditService audit;
    private final ObjectProvider<PurchaseOrderFulfillmentService> selfProvider;

    public PurchaseOrderFulfillmentService(PurchaseOrderRepository orders, PurchaseOrderShipmentRepository shipments,
            AdminAuditService audit, ObjectProvider<PurchaseOrderFulfillmentService> selfProvider) {
        this.orders = orders;
        this.shipments = shipments;
        this.audit = audit;
        this.selfProvider = selfProvider;
    }

    /** ops 录入一个包裹：第一个包裹把单推到 {@code SHIPPED}，之后只追加（D5）。 */
    @Transactional
    public PurchaseOrderShipment addShipment(String poToken, String carrier, String trackingNo, String trackingUrl,
            long actorAccountId) {
        String c = requireText(carrier, CARRIER_MAX, "admin.err.po.carrierRequired", "请填写快递公司");
        String t = requireText(trackingNo, TRACKING_NO_MAX, "admin.err.po.trackingNoRequired", "请填写快递单号");
        String url = normalizeTrackingUrl(trackingUrl);
        PurchaseOrder po = orders.findByPublicToken(poToken)
                .orElseThrow(() -> AppException.notFound("进货单不存在").code("admin.err.po.notFound"));
        Instant now = Instant.now();
        po.recordShipment(now, now);
        orders.save(po);
        PurchaseOrderShipment s = shipments.save(
                PurchaseOrderShipment.of(po.getId(), c, t, url, now, actorAccountId));
        audit.record(actorAccountId, AuditActions.MERCHANT_PO_SHIPMENT_ADDED, "PURCHASE_ORDER", po.getPublicToken(),
                "order_no=" + po.getOrderNo());
        return s;
    }

    /** ops 标记送达。 */
    @Transactional
    public void markDelivered(String poToken, long actorAccountId) {
        PurchaseOrder po = orders.findByPublicToken(poToken)
                .orElseThrow(() -> AppException.notFound("进货单不存在").code("admin.err.po.notFound"));
        po.markDelivered(Instant.now(), PoDeliverySource.ADMIN_MARK);
        orders.save(po);
        audit.record(actorAccountId, AuditActions.MERCHANT_PO_DELIVERED, "PURCHASE_ORDER", po.getPublicToken(),
                "order_no=" + po.getOrderNo());
    }

    /** 发货起超时仍未送达 → 自动置送达。逐笔独立事务，单笔失败不阻断。 */
    public int autoDeliverOverdue(int limit) {
        PurchaseOrderFulfillmentService self = selfProvider.getObject();
        Instant before = Instant.now().minus(PurchaseOrder.AUTO_DELIVER_AFTER);
        int n = 0;
        for (Long id : orders.findAutoDeliverDueIds(before, PageRequest.of(0, Math.max(1, limit)))) {
            if (self.autoDeliverOne(id)) {
                n++;
            }
        }
        return n;
    }

    /** 送达起超时 → 自动完成。 */
    public int autoCompleteOverdue(int limit) {
        PurchaseOrderFulfillmentService self = selfProvider.getObject();
        Instant before = Instant.now().minus(PurchaseOrder.AUTO_COMPLETE_AFTER);
        int n = 0;
        for (Long id : orders.findAutoCompleteDueIds(before, PageRequest.of(0, Math.max(1, limit)))) {
            if (self.autoCompleteOne(id)) {
                n++;
            }
        }
        return n;
    }

    @Transactional
    public boolean autoDeliverOne(long id) {
        PurchaseOrder po = orders.findById(id).orElse(null);
        Instant now = Instant.now();
        if (po == null || !po.isAutoDeliverDue(now)) {
            return false;
        }
        po.markDelivered(now, PoDeliverySource.AUTO_TIMEOUT);
        orders.save(po);
        return true;
    }

    @Transactional
    public boolean autoCompleteOne(long id) {
        PurchaseOrder po = orders.findById(id).orElse(null);
        Instant now = Instant.now();
        if (po == null || !po.isAutoCompleteDue(now)) {
            return false;
        }
        po.markCompleted(now);
        orders.save(po);
        return true;
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrderShipment> shipmentsOf(long purchaseOrderId) {
        return shipments.findByPurchaseOrderIdOrderByShippedAtAscIdAsc(purchaseOrderId);
    }

    private static String requireText(String v, int max, String code, String msg) {
        if (v == null || v.isBlank()) {
            throw AppException.validation(msg).code(code);
        }
        String t = v.trim();
        if (t.length() > max) {
            throw AppException.validation(msg).code("admin.err.po.fieldTooLong", max);
        }
        return t;
    }

    /** 查询链接可空；填了就必须是 http/https 绝对地址（商家平台对其它形状不显示链接）。 */
    static String normalizeTrackingUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        try {
            URI u = URI.create(v);
            String scheme = u.getScheme();
            if (v.length() <= TRACKING_URL_MAX && u.getHost() != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return v;
            }
        } catch (IllegalArgumentException ignored) {
            // 落到下面统一报错
        }
        throw AppException.validation("查询链接必须是 http/https 地址").code("admin.err.po.trackingUrlInvalid");
    }
}
