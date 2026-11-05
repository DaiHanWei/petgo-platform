package com.tailtopia.shop.order.service;

import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.shop.order.domain.Shipment;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.dto.ShopOrderCounts;
import com.tailtopia.shop.order.dto.ShopOrderListItem;
import com.tailtopia.shop.order.dto.ShopOrderListPage;
import com.tailtopia.shop.order.refund.ShopOrderRefundRepository;
import com.tailtopia.shop.order.repository.ShipmentRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 电商订单列表与角标计数（v1.4.2 shop Story 6.2 AC1 / AC2，D-18，PRD FR-133.9）。
 *
 * <p>🔴 <b>归组按 {@code displayStatus}</b>（S-7，宽限期待支付归已取消）：SQL 只负责「归组」，列表项的展示状态在内存里
 * 对查出的订单再调一次 3.3 的唯一判据 {@link ShopOrder#displayStatusAt}，两者对齐由 {@code ShopOrderGroupingTest} 钉住。
 *
 * <p>🔴 <b>禁 N+1</b>：一页内卡片摘要、最新活动运单、待处理退款单、支付意图各一次批量查询。
 * 通用 {@code /api/v1/orders}（{@code OrderCenterService}）不动（AC4）。
 */
@Service
public class ShopOrderListService {

    static final int PAGE_SIZE = 20;

    private final ShopOrderRepository orders;
    private final ShopOrderCardService cards;
    private final ShipmentRepository shipments;
    private final ShopOrderRefundRepository refunds;
    private final ShopOrderPaymentService payments;
    private final Clock clock;

    @Autowired
    public ShopOrderListService(ShopOrderRepository orders, ShopOrderCardService cards, ShipmentRepository shipments,
            ShopOrderRefundRepository refunds, ShopOrderPaymentService payments) {
        this(orders, cards, shipments, refunds, payments, Clock.systemUTC());
    }

    ShopOrderListService(ShopOrderRepository orders, ShopOrderCardService cards, ShipmentRepository shipments,
            ShopOrderRefundRepository refunds, ShopOrderPaymentService payments, Clock clock) {
        this.orders = orders;
        this.cards = cards;
        this.shipments = shipments;
        this.refunds = refunds;
        this.payments = payments;
        this.clock = clock;
    }

    /**
     * 本人电商订单一页。
     *
     * @param groupRaw 缺省 {@code all}；非法值 422
     * @param cursor   上一页的 {@code nextCursor}；坏游标按首页
     */
    @Transactional(readOnly = true)
    public ShopOrderListPage list(long userId, String groupRaw, String cursor) {
        ShopOrderGroup group = ShopOrderGroup.parse(groupRaw);
        ShopOrderListCursor c = ShopOrderListCursor.decode(cursor);
        Instant now = clock.instant();
        PageRequest page = PageRequest.of(0, PAGE_SIZE + 1);
        List<ShopOrder> rows = switch (group) {
            case ALL -> orders.findMyShopOrderPage(userId, c.beforeTs(), c.beforeId(), page);
            case PENDING_PAYMENT -> orders.findMyPendingPaymentPage(userId, now, c.beforeTs(), c.beforeId(), page);
            case PENDING_SHIPMENT -> orders.findMyShopOrderPageByStatuses(userId,
                    Set.of(ShopOrderStatus.PENDING_SHIPMENT), c.beforeTs(), c.beforeId(), page);
            case TO_RECEIVE -> orders.findMyShopOrderPageByStatuses(userId, ShopOrderGroup.TO_RECEIVE_STATUSES,
                    c.beforeTs(), c.beforeId(), page);
            case COMPLETED -> orders.findMyShopOrderPageByStatuses(userId, Set.of(ShopOrderStatus.COMPLETED),
                    c.beforeTs(), c.beforeId(), page);
            case CANCELLED -> orders.findMyCancelledPage(userId, now, c.beforeTs(), c.beforeId(), page);
        };
        if (rows.isEmpty()) {
            return ShopOrderListPage.empty();
        }
        boolean hasMore = rows.size() > PAGE_SIZE;
        List<ShopOrder> pageRows = hasMore ? rows.subList(0, PAGE_SIZE) : rows;
        List<ShopOrderListItem> items = toItems(pageRows, now);
        String next = null;
        if (hasMore) {
            ShopOrder last = pageRows.getLast();
            next = ShopOrderListCursor.after(last.getCreatedAt(), last.getId()).encode();
        }
        return new ShopOrderListPage(items, next, hasMore);
    }

    /** 角标（AC2）：一条 SQL，口径与 {@link #list} 的待付款 / 待收货组一致。 */
    @Transactional(readOnly = true)
    public ShopOrderCounts counts(long userId) {
        List<Object[]> rows = orders.countMyShopOrderBadges(userId, clock.instant());
        if (rows == null || rows.isEmpty() || rows.getFirst() == null) {
            return new ShopOrderCounts(0L, 0L);
        }
        Object[] r = rows.getFirst();
        return new ShopOrderCounts(longOf(r, 0), longOf(r, 1));
    }

    private static long longOf(Object[] row, int i) {
        return row.length > i && row[i] instanceof Number n ? n.longValue() : 0L;
    }

    private List<ShopOrderListItem> toItems(List<ShopOrder> rows, Instant now) {
        List<Long> ids = rows.stream().map(ShopOrder::getId).toList();
        Map<Long, ShopOrderCardService.CardInfo> cardById = cards.ofAll(ids);
        Map<Long, Shipment> latestShipment = shipments.findLatestActiveByOrderIds(ids);
        Set<Long> needAction = new HashSet<>(refunds.findOrderIdsNeedingUserAction(ids));
        // canPay 只对仍在窗内的待支付单有意义；只为它们取意图
        List<ShopOrder> payable = rows.stream()
                .filter(o -> o.displayStatusAt(now) == ShopOrderStatus.PENDING_PAYMENT).toList();
        Map<String, PaymentIntent> intentByToken = payable.isEmpty() ? Map.of() : payments.intentsOf(payable);

        List<ShopOrderListItem> out = new ArrayList<>(rows.size());
        for (ShopOrder o : rows) {
            ShopOrderStatus display = o.displayStatusAt(now);
            ShopOrderCardService.CardInfo card = cardById.getOrDefault(o.getId(),
                    new ShopOrderCardService.CardInfo(null, null, 0));
            Shipment s = latestShipment.get(o.getId());
            String statusKey = s != null && s.isBiteship() ? s.getUserStatusKey() : null;
            PaymentIntent intent = o.getPaymentIntentToken() == null ? null
                    : intentByToken.get(o.getPaymentIntentToken());
            boolean pending = display == ShopOrderStatus.PENDING_PAYMENT;
            out.add(new ShopOrderListItem(
                    o.getPublicToken(),
                    o.getDisplayNo(),
                    o.getStatus().name(),
                    display.name(),
                    o.getTotalAmount(),
                    o.getCreatedAt(),
                    card.thumbnailUrl(),
                    card.itemTitle(),
                    card.itemCount(),
                    pending ? o.getExpiresAt() : null,
                    pending && ShopOrderPaymentService.canPay(o, intent, now),
                    statusKey,
                    o.hasUserVisibleDeliveryIssue(),
                    needAction.contains(o.getId())));
        }
        return out;
    }
}
