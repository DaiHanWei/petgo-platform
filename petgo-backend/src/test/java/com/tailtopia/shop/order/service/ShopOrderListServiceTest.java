package com.tailtopia.shop.order.service;

import static com.tailtopia.shop.order.service.ShopOrderListFixtures.NOW;
import static com.tailtopia.shop.order.service.ShopOrderListFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.error.AppException;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** L0（v1.4.2 shop Story 6.2 AC1 / AC2）：电商订单列表服务（Mockito 仓库）。 */
class ShopOrderListServiceTest {

    private static final long USER = 7L;

    private ShopOrderRepository orders;
    private ShopOrderCardService cards;
    private ShipmentRepository shipments;
    private ShopOrderRefundRepository refunds;
    private ShopOrderPaymentService payments;
    private ShopOrderListService service;

    @BeforeEach
    void setUp() {
        orders = mock(ShopOrderRepository.class);
        cards = mock(ShopOrderCardService.class);
        shipments = mock(ShipmentRepository.class);
        refunds = mock(ShopOrderRefundRepository.class);
        payments = mock(ShopOrderPaymentService.class);
        service = new ShopOrderListService(orders, cards, shipments, refunds, payments,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(cards.ofAll(anyCollection())).thenReturn(Map.of());
        when(shipments.findLatestActiveByOrderIds(anyCollection())).thenReturn(Map.of());
        when(refunds.findOrderIdsNeedingUserAction(anyCollection())).thenReturn(List.of());
        when(payments.intentsOf(anyCollection())).thenReturn(Map.of());
    }

    @Test
    @DisplayName("group 非法 → 422，不查库")
    void invalidGroupIs422() {
        assertThatThrownBy(() -> service.list(USER, "unpaid", null))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        verifyNoInteractions(orders);
    }

    @Test
    @DisplayName("各分组调对应查询：all / pending_payment / pending_shipment / to_receive / completed / cancelled")
    void eachGroupCallsItsQuery() {
        service.list(USER, null, null);
        verify(orders).findMyShopOrderPage(eq(USER), any(), anyLong(), any());
        service.list(USER, "pending_payment", null);
        verify(orders).findMyPendingPaymentPage(eq(USER), eq(NOW), any(), anyLong(), any());
        service.list(USER, "pending_shipment", null);
        verify(orders).findMyShopOrderPageByStatuses(eq(USER),
                org.mockito.ArgumentMatchers.<Collection<ShopOrderStatus>>argThat(
                        c -> c.size() == 1 && c.contains(ShopOrderStatus.PENDING_SHIPMENT)),
                any(), anyLong(), any());
        service.list(USER, "to_receive", null);
        verify(orders).findMyShopOrderPageByStatuses(eq(USER),
                org.mockito.ArgumentMatchers.<Collection<ShopOrderStatus>>argThat(
                        c -> c.size() == 2 && c.contains(ShopOrderStatus.SHIPPED)
                                && c.contains(ShopOrderStatus.DELIVERED)),
                any(), anyLong(), any());
        service.list(USER, "completed", null);
        verify(orders).findMyShopOrderPageByStatuses(eq(USER),
                org.mockito.ArgumentMatchers.<Collection<ShopOrderStatus>>argThat(
                        c -> c.size() == 1 && c.contains(ShopOrderStatus.COMPLETED)),
                any(), anyLong(), any());
        service.list(USER, "cancelled", null);
        verify(orders).findMyCancelledPage(eq(USER), eq(NOW), any(), anyLong(), any());
    }

    @Test
    @DisplayName("空集 → {items: [], hasMore: false}，不做批量查询")
    void emptyPage() {
        ShopOrderListPage p = service.list(USER, "all", null);
        assertThat(p.items()).isEmpty();
        assertThat(p.hasMore()).isFalse();
        assertThat(p.nextCursor()).isNull();
        verify(cards, never()).ofAll(anyCollection());
    }

    @Test
    @DisplayName("宽限期待支付 → displayStatus=CANCELLED、无 paymentDeadline、canPay=false；窗内 → PENDING_PAYMENT、带截止")
    void gracePeriodItem() {
        ShopOrder grace = order(1, ShopOrderStatus.PENDING_PAYMENT, NOW.minusSeconds(60));
        ShopOrder open = order(2, ShopOrderStatus.PENDING_PAYMENT, NOW.plusSeconds(600));
        when(orders.findMyShopOrderPage(eq(USER), any(), anyLong(), any())).thenReturn(List.of(grace, open));

        List<ShopOrderListItem> items = service.list(USER, null, null).items();

        assertThat(items.get(0).status()).isEqualTo("PENDING_PAYMENT");
        assertThat(items.get(0).displayStatus()).isEqualTo("CANCELLED");
        assertThat(items.get(0).paymentDeadline()).isNull();
        assertThat(items.get(0).canPay()).isFalse();
        assertThat(items.get(1).displayStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(items.get(1).paymentDeadline()).isEqualTo(NOW.plusSeconds(600));
        assertThat(items.get(1).canPay()).isTrue();     // 无意图、未出码、出码窗内 → ISSUE_NEW
        assertThat(items.get(0).totalAmount()).isEqualTo(220_000L);
        // 意图只为窗内待支付单取
        verify(payments).intentsOf(org.mockito.ArgumentMatchers.<Collection<ShopOrder>>argThat(
                c -> c.size() == 1 && c.contains(open)));
    }

    @Test
    @DisplayName("21 条 → 返回 20 条、hasMore=true、nextCursor 指向第 20 条；游标传给下一页查询")
    void hasMoreAndNextCursor() {
        List<ShopOrder> rows = new ArrayList<>();
        for (long i = 1; i <= 21; i++) {
            rows.add(order(i, ShopOrderStatus.COMPLETED, null));
        }
        when(orders.findMyShopOrderPage(eq(USER), any(), anyLong(), any())).thenReturn(rows);

        ShopOrderListPage p = service.list(USER, "all", null);

        assertThat(p.items()).hasSize(20);
        assertThat(p.hasMore()).isTrue();
        ShopOrderListCursor c = ShopOrderListCursor.decode(p.nextCursor());
        assertThat(c.beforeId()).isEqualTo(20L);
        assertThat(c.beforeTs()).isEqualTo(rows.get(19).getCreatedAt());

        service.list(USER, "all", p.nextCursor());
        verify(orders).findMyShopOrderPage(eq(USER), eq(rows.get(19).getCreatedAt()), eq(20L),
                org.mockito.ArgumentMatchers.argThat(pg -> pg.getPageSize() == 21));
    }

    @Test
    @DisplayName("坏游标 → 按首页查询（哨兵游标）")
    void badCursorIsFirstPage() {
        service.list(USER, "all", "garbage!!");
        verify(orders).findMyShopOrderPage(eq(USER), eq(ShopOrderListCursor.FIRST.beforeTs()),
                eq(Long.MAX_VALUE), any());
    }

    @Test
    @DisplayName("🔴 无 N+1：一页 3 单，卡片 / 运单 / 退款单 / 意图各只批量查一次；logistics / deliveryIssue / refundActionRequired 正确")
    void batchQueriesOncePerPage() {
        ShopOrder a = order(1, ShopOrderStatus.SHIPPED, null);
        ShopOrder b = order(2, ShopOrderStatus.SHIPPED, null);
        ShopOrder c = order(3, ShopOrderStatus.PENDING_PAYMENT, NOW.plusSeconds(600));
        org.springframework.test.util.ReflectionTestUtils.setField(b, "logisticsException",
                com.tailtopia.shop.order.domain.LogisticsException.RETURNED);
        org.springframework.test.util.ReflectionTestUtils.setField(b, "logisticsExceptionConfirmed", true);
        when(orders.findMyShopOrderPage(eq(USER), any(), anyLong(), any())).thenReturn(List.of(a, b, c));
        Shipment bite = Shipment.biteship(1L, "jne", "jne", "reg", "bs-1", "ref", "WB1", "tr", 10_000L, 0L, NOW);
        Shipment manual = Shipment.ship(2L, com.tailtopia.shop.order.domain.Carrier.values()[0], "TRK2", 0L);
        when(shipments.findLatestActiveByOrderIds(anyCollection())).thenReturn(Map.of(1L, bite, 2L, manual));
        when(refunds.findOrderIdsNeedingUserAction(anyCollection())).thenReturn(List.of(2L));
        when(cards.ofAll(anyCollection())).thenReturn(Map.of(1L,
                new ShopOrderCardService.CardInfo("https://cdn/x.jpg", "Royal · 2kg", 3)));

        List<ShopOrderListItem> items = service.list(USER, null, null).items();

        verify(cards, times(1)).ofAll(anyCollection());
        verify(shipments, times(1)).findLatestActiveByOrderIds(anyCollection());
        verify(refunds, times(1)).findOrderIdsNeedingUserAction(anyCollection());
        verify(payments, times(1)).intentsOf(anyCollection());
        assertThat(items.get(0).logisticsStatusKey()).isEqualTo("awaiting_pickup");
        assertThat(items.get(0).thumbnailUrl()).isEqualTo("https://cdn/x.jpg");
        assertThat(items.get(0).itemCount()).isEqualTo(3);
        assertThat(items.get(1).logisticsStatusKey()).as("手工运单不给物流状态").isNull();
        assertThat(items.get(1).deliveryIssue()).isTrue();
        assertThat(items.get(1).refundActionRequired()).isTrue();
        assertThat(items.get(1).itemCount()).as("无卡片摘要按 0 件").isZero();
        assertThat(items.get(0).deliveryIssue()).isFalse();
        assertThat(items.get(2).logisticsStatusKey()).isNull();
    }

    @Test
    @DisplayName("角标：取计数 SQL 的单行；无行 / SUM 为 null → 0")
    void counts() {
        List<Object[]> one = new ArrayList<>();
        one.add(new Object[] {2L, 5L});
        when(orders.countMyShopOrderBadges(USER, NOW)).thenReturn(one);
        assertThat(service.counts(USER)).isEqualTo(new ShopOrderCounts(2L, 5L));

        List<Object[]> nulls = new ArrayList<>();
        nulls.add(new Object[] {null, null});
        when(orders.countMyShopOrderBadges(USER, NOW)).thenReturn(nulls);
        assertThat(service.counts(USER)).isEqualTo(new ShopOrderCounts(0L, 0L));

        when(orders.countMyShopOrderBadges(USER, NOW)).thenReturn(List.of());
        assertThat(service.counts(USER)).isEqualTo(new ShopOrderCounts(0L, 0L));
    }
}
