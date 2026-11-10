package com.tailtopia.shop.logistics;

import static com.tailtopia.shop.logistics.LogisticsFixtures.NOW;
import static com.tailtopia.shop.logistics.LogisticsFixtures.ORDER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.logistics.biteship.BiteshipClient;
import com.tailtopia.shared.logistics.biteship.BiteshipProperties;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipCreateOrderRequest;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipOrderSnapshot;
import com.tailtopia.shared.logistics.biteship.dto.CancelOutcome;
import com.tailtopia.shared.logistics.biteship.dto.CreateOutcome;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.logistics.event.ShopWaybillCancelRequestedEvent;
import com.tailtopia.shop.order.domain.AddressSnapshot;
import com.tailtopia.shop.order.domain.Shipment;
import com.tailtopia.shop.order.domain.ShipmentStatus;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.refund.CancelReason;
import com.tailtopia.shop.order.refund.ShopOrderRefundRepository;
import com.tailtopia.shop.order.repository.ShipmentRepository;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.ShopPaymentAlerts;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.shipping.domain.ShippingSettings;
import com.tailtopia.shop.shipping.repository.ShippingSettingsRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/** L0：v1.4.2 shop Story 5.1 AC4 / AC5 / AC12 —— 下运单编排（BiteshipClient mock，内存运单表）。 */
class BiteshipShipmentServiceTest {

    private final ShopOrderRepository orders = mock(ShopOrderRepository.class);
    private final ShopOrderLineRepository orderLines = mock(ShopOrderLineRepository.class);
    private final ShipmentRepository shipments = mock(ShipmentRepository.class);
    private final ShopSkuRepository skus = mock(ShopSkuRepository.class);
    private final ShippingSettingsRepository settings = mock(ShippingSettingsRepository.class);
    private final ShopOrderRefundRepository refunds = mock(ShopOrderRefundRepository.class);
    private final BiteshipClient client = mock(BiteshipClient.class);
    private final BiteshipProperties props = new BiteshipProperties();
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final ShopPaymentAlerts alerts = mock(ShopPaymentAlerts.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PlatformTransactionManager txm = mock(PlatformTransactionManager.class);

    private BiteshipShipmentService service;
    private ShopOrder order;
    private final List<Shipment> store = new ArrayList<>();
    private ShippingSettings origin;

    @BeforeEach
    void setUp() {
        service = new BiteshipShipmentService(orders, orderLines, shipments, skus, settings, refunds, client, props,
                audit, alerts, redis, events, txm, Clock.fixed(NOW, ZoneOffset.UTC));
        order = LogisticsFixtures.paidOrder(false);
        when(orders.findById(ORDER_ID)).thenAnswer(inv -> Optional.of(order));
        when(orders.findByIdForUpdate(ORDER_ID)).thenAnswer(inv -> Optional.of(order));
        when(orders.save(any(ShopOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        List<ShopOrderLine> lines = List.of(LogisticsFixtures.line(1L, 11L, "Royal Canin", "2kg", 100_000L, 2));
        when(orderLines.findByOrderIdOrderByIdAsc(ORDER_ID)).thenReturn(lines);
        ShopSku sku = ShopSku.create("sku-1", 1L, "2kg", 100_000L, null, ReturnPolicy.RETURNABLE);
        ReflectionTestUtils.setField(sku, "id", 11L);
        sku.applyPackage(1000, 20, 15, 10);
        when(skus.findAllById(any())).thenReturn(List.of(sku));
        origin = BeanUtils.instantiateClass(ShippingSettings.class);
        origin.applyOrigin("12440", "Gudang", "+62811", "Jl. Gudang 1");
        when(settings.findById(ShippingSettings.SINGLETON_ID)).thenAnswer(inv -> Optional.of(origin));
        when(refunds.findByOrderIdOrderByCreatedAtAscIdAsc(ORDER_ID)).thenReturn(List.of());
        when(client.isAvailable()).thenReturn(true);
        when(shipments.save(any(Shipment.class))).thenAnswer(inv -> {
            Shipment s = inv.getArgument(0);
            if (!store.contains(s)) {
                ReflectionTestUtils.setField(s, "id", (long) store.size() + 1);
                store.add(s);
            }
            return s;
        });
        when(shipments.findActiveByShopOrderId(ORDER_ID)).thenAnswer(inv -> store.stream()
                .filter(Shipment::isActive).sorted((a, b) -> Long.compare(b.getId(), a.getId())).toList());
        when(shipments.findById(any())).thenAnswer(inv -> store.stream()
                .filter(s -> s.getId().equals(inv.getArgument(0))).findFirst());
        when(redis.opsForValue()).thenReturn(ops);
    }

    private static BiteshipOrderSnapshot snap(String id, String status) {
        return new BiteshipOrderSnapshot(id, status, "WB-" + id, null, "jne", "reg", 9_500L,
                "https://track.example/" + id, NOW);
    }

    private void created(String id) {
        when(client.createOrder(any())).thenReturn(new CreateOutcome.Created(snap(id, "confirmed")));
    }

    @Test
    @DisplayName("前置不过（非待发货 / 已有运单 / 无快递快照 / 收件信息已清）→ 409，不调 Biteship、不写失败标记")
    void precheckFailuresDoNotCallClient() {
        order.markShipped(NOW);
        assertThatThrownBy(() -> service.createWaybill(ORDER_ID, 1L)).isInstanceOf(AppException.class);

        order = LogisticsFixtures.paidOrder(false, LogisticsFixtures.addr(), null, null);
        assertThatThrownBy(() -> service.createWaybill(ORDER_ID, 1L)).hasMessageContaining("存量订单");

        order = LogisticsFixtures.paidOrder(false,
                new AddressSnapshot("", "", "DKI", "Jaksel", "Kebayoran", "", "12160"), "jne", "reg");
        assertThatThrownBy(() -> service.createWaybill(ORDER_ID, 1L)).hasMessageContaining("收件信息");

        order = LogisticsFixtures.paidOrder(false);
        store.add(LogisticsFixtures.biteshipShipment(1L, "bs_x"));
        assertThatThrownBy(() -> service.createWaybill(ORDER_ID, 1L)).hasMessageContaining("已有运单");

        verify(client, never()).createOrder(any());
        assertThat(order.getWaybillFailedAt()).isNull();
        assertThat(order.getWaybillAttempts()).isZero();
    }

    @Test
    @DisplayName("Created → 写 Biteship 运单（CREATED、carrier 大写、承运成本 = price、https 链接），订单仍 PENDING_SHIPMENT")
    void createdWritesShipment() {
        created("bs_1");
        WaybillResult r = service.createWaybill(ORDER_ID, 1L);

        assertThat(r).isEqualTo(WaybillResult.ok());
        assertThat(order.getStatus()).isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
        assertThat(order.getWaybillAttempts()).isEqualTo(1);
        assertThat(store).hasSize(1);
        Shipment s = store.get(0);
        assertThat(s.getStatus()).isEqualTo(ShipmentStatus.CREATED);
        assertThat(s.getCarrier()).isEqualTo("JNE");
        assertThat(s.getBiteshipOrderId()).isEqualTo("bs_1");
        assertThat(s.getReferenceId()).isEqualTo("TOKO-20261105-ABC123-1");
        assertThat(s.getCarrierCost()).isEqualTo(9_500L);
        assertThat(s.getTrackingNo()).isEqualTo("WB-bs_1");
        assertThat(s.getProviderStatus()).isEqualTo("confirmed");
        assertThat(s.trackingUrl()).isEqualTo("https://track.example/bs_1");
        ArgumentCaptor<BiteshipCreateOrderRequest> req = ArgumentCaptor.forClass(BiteshipCreateOrderRequest.class);
        verify(client).createOrder(req.capture());
        assertThat(req.getValue().referenceId()).isEqualTo("TOKO-20261105-ABC123-1");
        verify(audit).record(eq(1L), eq(AuditActions.SHOP_WAYBILL_CREATED), eq("SHOP_ORDER"),
                eq(LogisticsFixtures.TOKEN), org.mockito.ArgumentMatchers.argThat(
                        (String sum) -> sum.contains("bs_1") && !sum.contains("Budi")));
    }

    @Test
    @DisplayName("Rejected / Unknown → 写失败标记、不抛；Unknown 原因前缀 UNKNOWN_RESULT；成功后清标记")
    void failuresRecorded() {
        when(client.createOrder(any())).thenReturn(new CreateOutcome.Rejected("40002060", "dup"));
        WaybillResult r = service.createWaybill(ORDER_ID, 1L);
        assertThat(r.created()).isFalse();
        assertThat(r.failReason()).isEqualTo("REJECTED:40002060");
        assertThat(order.getWaybillFailReason()).isEqualTo("REJECTED:40002060");
        assertThat(order.getWaybillFailedAt()).isEqualTo(NOW);
        assertThat(order.getStatus()).isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);

        when(client.createOrder(any())).thenReturn(new CreateOutcome.Unknown("NETWORK"));
        WaybillResult u = service.createWaybill(ORDER_ID, 1L);
        assertThat(u.failReason()).startsWith("UNKNOWN_RESULT:");
        assertThat(order.getWaybillAttempts()).as("每次调用换新 reference").isEqualTo(2);

        created("bs_3");
        ArgumentCaptor<BiteshipCreateOrderRequest> req = ArgumentCaptor.forClass(BiteshipCreateOrderRequest.class);
        assertThat(service.createWaybill(ORDER_ID, 1L).created()).isTrue();
        verify(client, times(3)).createOrder(req.capture());
        assertThat(req.getValue().referenceId()).isEqualTo("TOKO-20261105-ABC123-3");
        assertThat(order.getWaybillFailedAt()).isNull();
        assertThat(order.getWaybillFailReason()).isNull();
        verify(audit, times(2)).record(any(), eq(AuditActions.SHOP_WAYBILL_FAILED), any(), any(), any());
    }

    @Test
    @DisplayName("发货地未配置 → 记 ORIGIN_NOT_CONFIGURED，不调 Biteship、不消耗序号")
    void originMissing() {
        origin = BeanUtils.instantiateClass(ShippingSettings.class);
        WaybillResult r = service.createWaybill(ORDER_ID, 1L);
        assertThat(r.failReason()).isEqualTo("ORIGIN_NOT_CONFIGURED");
        assertThat(order.getWaybillFailReason()).isEqualTo("ORIGIN_NOT_CONFIGURED");
        assertThat(order.getWaybillAttempts()).isZero();
        verify(client, never()).createOrder(any());
    }

    @Test
    @DisplayName("第二短事务发现订单已取消 → 不建运单行，立即 cancelOrder(..., others)")
    void orderCancelledMeanwhile() {
        when(client.createOrder(any())).thenAnswer(inv -> {
            order.markRefunding(CancelReason.OUT_OF_STOCK, NOW);       // 外部调用期间订单被取消
            return new CreateOutcome.Created(snap("bs_9", "confirmed"));
        });
        when(client.cancelOrder("bs_9", "others")).thenReturn(new CancelOutcome.Cancelled());
        WaybillResult r = service.createWaybill(ORDER_ID, 1L);
        assertThat(r.created()).isFalse();
        assertThat(store).isEmpty();
        verify(client).cancelOrder("bs_9", "others");
    }

    @Test
    @DisplayName("🔴 复审：建单成功但落库（短事务②）抛错 → 按孤儿运单取消 + 告警（用展示号，不用自增 id）")
    void settleFailureCancelsOrphan() {
        created("bs_7");
        when(shipments.save(any(Shipment.class))).thenThrow(new IllegalStateException("db down"));
        when(client.cancelOrder("bs_7", "others")).thenReturn(new CancelOutcome.Unknown("NETWORK"));
        WaybillResult r = service.createWaybill(ORDER_ID, 1L);
        assertThat(r).isEqualTo(WaybillResult.failed("SETTLE_FAILED"));
        verify(client).cancelOrder("bs_7", "others");
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(alerts).alert(org.mockito.ArgumentMatchers.any(), text.capture());
        assertThat(text.getValue()).contains(LogisticsFixtures.DISPLAY_NO).contains("bs_7")
                .doesNotContain("id=" + ORDER_ID);
    }

    @Test
    @DisplayName("🔴 复审：短事务②对订单行加写锁（findByIdForUpdate）；订单变化的孤儿告警用展示号")
    void settleLocksOrderAndOrphanAlertUsesDisplayNo() {
        when(client.createOrder(any())).thenAnswer(inv -> {
            order.markRefunding(CancelReason.OUT_OF_STOCK, NOW);
            return new CreateOutcome.Created(snap("bs_8", "confirmed"));
        });
        when(client.cancelOrder("bs_8", "others")).thenReturn(new CancelOutcome.NotCancellable("picked"));
        service.createWaybill(ORDER_ID, 1L);
        verify(orders).findByIdForUpdate(ORDER_ID);
        verify(alerts).alert(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.argThat(
                (String t) -> t.contains(LogisticsFixtures.DISPLAY_NO) && !t.contains("id=")));
    }

    @Test
    @DisplayName("🔴 复审：cancelWaybill 在调 Biteship 之前登记 cancel_requested_at")
    void cancelMarksRequestedBeforeCall() {
        created("bs_1");
        service.createWaybill(ORDER_ID, 1L);
        when(client.cancelOrder("bs_1", "others")).thenAnswer(inv -> {
            assertThat(store.get(0).getCancelRequestedAt()).isNotNull();
            return new CancelOutcome.Cancelled();
        });
        service.cancelWaybill(ORDER_ID, "others", 1L);
        assertThat(store.get(0).getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
    }

    @Test
    @DisplayName("5.2 复审 4：NotCancellable → 复位 cancel_requested_at；Unknown → 保留")
    void notCancellableResetsCancelRequested() {
        created("bs_1");
        service.createWaybill(ORDER_ID, 1L);
        Shipment s = store.get(0);
        when(client.cancelOrder("bs_1", "others")).thenReturn(new CancelOutcome.Unknown("NETWORK"));
        assertThatThrownBy(() -> service.cancelWaybill(ORDER_ID, "others", 1L)).isInstanceOf(AppException.class);
        assertThat(s.getCancelRequestedAt()).as("结果不明，保留").isNotNull();

        when(client.cancelOrder("bs_1", "others")).thenReturn(new CancelOutcome.NotCancellable("picked"));
        assertThatThrownBy(() -> service.cancelWaybill(ORDER_ID, "others", 1L)).isInstanceOf(AppException.class);
        assertThat(s.getCancelRequestedAt()).as("Biteship 明确拒绝，运单仍有效").isNull();
        assertThat(s.getStatus()).isEqualTo(ShipmentStatus.CREATED);
    }

    @Test
    @DisplayName("retryWaybill：计数达 10 告警一次，第 11 次不重复告警；Redis 异常不影响重试")
    void retryCounterAlertsOnce() {
        when(client.createOrder(any())).thenReturn(new CreateOutcome.Rejected("400", "x"));
        long[] n = {0};
        when(ops.increment(BiteshipShipmentService.RETRY_COUNTER_KEY)).thenAnswer(inv -> ++n[0]);
        boolean[] alerted = {false};
        when(ops.setIfAbsent(eq(BiteshipShipmentService.RETRY_ALERTED_KEY), anyString(), any(Duration.class)))
                .thenAnswer(inv -> {
                    boolean first = !alerted[0];
                    alerted[0] = true;
                    return first;
                });
        for (int i = 0; i < 11; i++) {
            service.retryWaybill(ORDER_ID, 1L);
        }
        verify(redis).expire(BiteshipShipmentService.RETRY_COUNTER_KEY, Duration.ofMinutes(5));
        verify(alerts, times(1)).alert(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.contains("重下运单"));

        when(ops.increment(anyString())).thenThrow(new IllegalStateException("redis down"));
        assertThat(service.retryWaybill(ORDER_ID, 1L).created()).isFalse();
        verify(client, times(12)).createOrder(any());
    }

    @Test
    @DisplayName("cancelWaybill：Cancelled 落本地 CANCELLED；NotCancellable 409；Unknown 503 不改本地；无运单幂等；原因非四值 422")
    void cancelWaybill() {
        assertThatThrownBy(() -> service.cancelWaybill(ORDER_ID, "bad", 1L)).isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus().value()).isEqualTo(422);
        service.cancelWaybill(ORDER_ID, "others", 1L);         // 无运单：幂等
        verify(client, never()).cancelOrder(anyString(), anyString());

        created("bs_1");
        service.createWaybill(ORDER_ID, 1L);
        Shipment s = store.get(0);

        when(client.cancelOrder("bs_1", "pickup_delay")).thenReturn(new CancelOutcome.Unknown("NETWORK"));
        assertThatThrownBy(() -> service.cancelWaybill(ORDER_ID, "pickup_delay", 1L))
                .extracting(e -> ((AppException) e).getStatus().value()).isEqualTo(503);
        assertThat(s.getStatus()).isEqualTo(ShipmentStatus.CREATED);

        when(client.cancelOrder("bs_1", "pickup_delay")).thenReturn(new CancelOutcome.NotCancellable("picked"));
        assertThatThrownBy(() -> service.cancelWaybill(ORDER_ID, "pickup_delay", 1L))
                .hasMessageContaining("picked").extracting(e -> ((AppException) e).getStatus().value())
                .isEqualTo(409);

        when(client.cancelOrder("bs_1", "pickup_delay")).thenReturn(new CancelOutcome.Cancelled());
        service.cancelWaybill(ORDER_ID, "pickup_delay", 1L);
        assertThat(s.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(s.isActive()).isFalse();
        verify(audit).record(any(), eq(AuditActions.SHOP_WAYBILL_CANCELLED), any(), any(), any());
    }

    @Test
    @DisplayName("provider_status 已是 cancelled → 只落本地，不调 Biteship")
    void cancelAlreadyCancelledRemotely() {
        created("bs_1");
        service.createWaybill(ORDER_ID, 1L);
        store.get(0).applyProviderStatus("cancelled", NOW.plusSeconds(10), null);
        service.cancelWaybill(ORDER_ID, "others", 1L);
        assertThat(store.get(0).getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        verify(client, never()).cancelOrder(anyString(), anyString());
    }

    @Test
    @DisplayName("本地 courier_not_found 仍先调 Biteship：回查已揽收（picked）→ 409 不落本地；回查 courier_not_found → 落本地")
    void courierNotFoundStillAsksBiteship() {
        created("bs_1");
        service.createWaybill(ORDER_ID, 1L);
        store.get(0).applyProviderStatus("courier_not_found", NOW.plusSeconds(10), null);
        when(client.cancelOrder("bs_1", "others")).thenReturn(new CancelOutcome.NotCancellable("picked"));
        assertThatThrownBy(() -> service.cancelWaybill(ORDER_ID, "others", 1L))
                .extracting(e -> ((AppException) e).getStatus().value()).isEqualTo(409);
        assertThat(store.get(0).isActive()).isTrue();

        when(client.cancelOrder("bs_1", "others")).thenReturn(new CancelOutcome.NotCancellable("courier_not_found"));
        service.cancelWaybill(ORDER_ID, "others", 1L);
        assertThat(store.get(0).getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
    }

    @Test
    @DisplayName("本地状态滞后：Biteship 拒绝取消且回查为 courier_not_found / cancelled → 按已取消落本地，不抛 409")
    void notCancellableButEndedRemotely() {
        created("bs_1");
        service.createWaybill(ORDER_ID, 1L);
        when(client.cancelOrder("bs_1", "others")).thenReturn(new CancelOutcome.NotCancellable("courier_not_found"));
        service.cancelWaybill(ORDER_ID, "others", 1L);
        assertThat(store.get(0).getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        verify(audit).record(any(), eq(AuditActions.SHOP_WAYBILL_CANCELLED), any(), any(), any());
    }

    @Test
    @DisplayName("reshipForCourierFault：旧运单 superseded、不调 Biteship 取消；新运单 CREATED、新 reference；订单状态不变")
    void reship() {
        created("bs_1");
        service.createWaybill(ORDER_ID, 1L);
        Shipment old = store.get(0);
        old.markPicked(NOW);
        order.markShipped(NOW);

        created("bs_2");
        WaybillResult r = service.reshipForCourierFault(ORDER_ID, 1L);

        assertThat(r.created()).isTrue();
        assertThat(old.getSupersededAt()).isNotNull();
        assertThat(store).hasSize(2);
        Shipment fresh = store.get(1);
        assertThat(fresh.getStatus()).isEqualTo(ShipmentStatus.CREATED);
        assertThat(fresh.getReferenceId()).isEqualTo("TOKO-20261105-ABC123-2");
        assertThat(order.getStatus()).isEqualTo(ShopOrderStatus.SHIPPED);
        verify(client, never()).cancelOrder(anyString(), anyString());
    }

    @Test
    @DisplayName("reship：待发货订单不可重发（409）；重发失败时旧运单仍为活动运单")
    void reshipGuards() {
        assertThatThrownBy(() -> service.reshipForCourierFault(ORDER_ID, 1L)).isInstanceOf(AppException.class);
        created("bs_1");
        service.createWaybill(ORDER_ID, 1L);
        order.markShipped(NOW);
        when(client.createOrder(any())).thenReturn(new CreateOutcome.Rejected("400", "x"));
        assertThat(service.reshipForCourierFault(ORDER_ID, 1L).created()).isFalse();
        assertThat(store.get(0).isActive()).isTrue();
    }

    @Test
    @DisplayName("4.1 钩子：只登记意图（发布事件），提交后才取消；只取消未揽收运单，失败告警不外抛")
    void platformCancelHook() {
        service.cancelForPlatformCancel(order, 5L);
        verify(events).publishEvent(new ShopWaybillCancelRequestedEvent(ORDER_ID, LogisticsFixtures.DISPLAY_NO, 5L));
        verify(client, never()).cancelOrder(anyString(), anyString());

        created("bs_1");
        service.createWaybill(ORDER_ID, 1L);
        when(client.cancelOrder("bs_1", "others")).thenReturn(new CancelOutcome.NotCancellable("picked"));
        service.cancelAfterPlatformCancel(new ShopWaybillCancelRequestedEvent(ORDER_ID, "TOKO-X", 5L));
        verify(alerts).alert(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.contains("人工取消运单"));

        store.get(0).markPicked(NOW);
        service.cancelAfterPlatformCancel(new ShopWaybillCancelRequestedEvent(ORDER_ID, "TOKO-X", 5L));
        verify(client, times(1)).cancelOrder("bs_1", "others");
    }
}
