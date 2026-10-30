package com.tailtopia.admin.merchant.service;

import com.tailtopia.merchant.po.domain.PurchaseOrder;
import com.tailtopia.merchant.po.domain.PurchaseOrderLine;
import com.tailtopia.merchant.po.domain.PurchaseOrderShipment;
import com.tailtopia.merchant.po.domain.PurchaseOrderStatus;
import com.tailtopia.merchant.po.repository.PurchaseOrderLineRepository;
import com.tailtopia.merchant.po.repository.PurchaseOrderRepository;
import com.tailtopia.merchant.po.service.PurchaseOrderFulfillmentService;
import com.tailtopia.shared.error.AppException;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 门店进货单 ops 页的只读装配（列表 + 详情）。写操作（录入包裹 / 标记送达）直接走
 * {@link PurchaseOrderFulfillmentService}，校验与审计都在那边，这里不做第二份。
 *
 * <p>🔒 收货人 / 电话 / 地址只在详情页渲染；本类不记任何日志。
 */
@Service
public class AdminPurchaseOrderQueryService {

    private final PurchaseOrderRepository orders;
    private final PurchaseOrderLineRepository lines;
    private final PurchaseOrderFulfillmentService fulfillment;

    public AdminPurchaseOrderQueryService(PurchaseOrderRepository orders, PurchaseOrderLineRepository lines,
            PurchaseOrderFulfillmentService fulfillment) {
        this.orders = orders;
        this.lines = lines;
        this.fulfillment = fulfillment;
    }

    /** 一页进货单（created_at 倒序，同刻 id 倒序；全部门店）。{@code orderNoQuery} 为空 = 不搜。 */
    @Transactional(readOnly = true)
    public Page page(PurchaseOrderStatus status, String orderNoQuery, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.max(1, size);
        var found = orders.adminSearch(status, likePattern(orderNoQuery), PageRequest.of(p, s));
        return new Page(found.getContent(), p, found.getTotalPages(), found.getTotalElements(), found.hasNext());
    }

    /** 状态 chips 计数（每个状态都有一项，没有单的为 0）+ 全部。 */
    @Transactional(readOnly = true)
    public Counts counts() {
        Map<PurchaseOrderStatus, Long> by = new EnumMap<>(PurchaseOrderStatus.class);
        for (PurchaseOrderStatus st : PurchaseOrderStatus.values()) {
            by.put(st, 0L);
        }
        long total = 0;
        for (Object[] row : orders.countGroupByStatus()) {
            long n = ((Number) row[1]).longValue();
            by.put((PurchaseOrderStatus) row[0], n);
            total += n;
        }
        return new Counts(by, total);
    }

    @Transactional(readOnly = true)
    public Detail detail(String poToken) {
        PurchaseOrder po = orders.findByPublicToken(poToken == null ? "" : poToken.trim())
                .orElseThrow(() -> AppException.notFound("进货单不存在").code("admin.err.po.notFound"));
        return new Detail(po, lines.findByPurchaseOrderIdOrderByLineNoAsc(po.getId()),
                fulfillment.shipmentsOf(po.getId()));
    }

    /**
     * 单号模糊搜：去掉 LIKE 通配符与转义符（运营粘进来的 {@code %} / {@code _} 不该变成「全匹配」），
     * 单号是大写 Crockford，统一转大写。不搜时返回 {@code "%"}（仓储约定）。
     */
    static String likePattern(String raw) {
        if (raw == null) {
            return "%";
        }
        String q = raw.trim().replace("\\", "").replace("%", "").replace("_", "").toUpperCase(Locale.ROOT);
        return q.isEmpty() ? "%" : "%" + q + "%";
    }

    /** 一页进货单。 */
    public record Page(List<PurchaseOrder> rows, int page, int totalPages, long totalElements, boolean hasNext) {
    }

    /** 各状态计数。 */
    public record Counts(Map<PurchaseOrderStatus, Long> byStatus, long total) {
    }

    /** 详情：单 + 明细行 + 包裹（按 shipped_at 升序）。 */
    public record Detail(PurchaseOrder order, List<PurchaseOrderLine> lines, List<PurchaseOrderShipment> shipments) {
    }
}
